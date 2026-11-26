#!/usr/bin/env python3
"""Drive the real app on the iOS Simulator or Android emulator and save evidence.

    scripts/ui-check.py ios|android [scenario ...] [--no-build]

Scenarios (default: translate; SPEC.md §9):
    translate       type a Spanish sentence, translate it, check the result
    airplane        translate with no network: Android airplane mode; iOS checks the app opens no
                    socket (the Simulator shares the Mac's network and cannot go offline alone)
    kill-verifying  model left as a complete .part, app killed during "Comprobando la descarga…",
                    relaunched offline: must verify and install the same file without downloading
    cellular        model hidden, Wi-Fi off (Android): Descargar must ask "¿Usar datos móviles?";
                    answers "Esperar al wifi" (iOS: skipped, no way to fake an expensive network)
    low-space       model hidden, free space below what the download needs (Android, a filler file
                    if needed): Descargar must say there is not enough space (iOS: skipped, the
                    Simulator uses the Mac's disk)
    all             every scenario above

Scenarios that need a missing or partial model only rename the installed model file and always
rename it back; they never tap "Descargar igualmente" and check free space before tapping Descargar,
so no run starts a real download.

Each run writes evidence/<timestamp>-<platform>/ (gitignored): numbered screenshots, the UI tree
at every step, and summary.md with PASS/FAIL per check. Exit code 0 only if every check passed.
Paste summary.md into the PR and attach the screenshots (see .github/pull_request_template.md).

Local only, never in CI. Needs a booted simulator/emulator with the model already installed by
the app itself: this script never downloads the model (AGENTS.md). Tools: iOS uses xcodebuild,
simctl and AXe; Android uses adb (ANDROID_SERIAL picks the device) and Gradle.
Pick the simulator with SIM_UDID (default: the first booted iPhone).
"""
import contextlib
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_ID = "dev.heikki.walter"
ANDROID_ACTIVITY = f"{APP_ID}/dev.heikki.translator.MainActivity"
MODEL_FILE = "gemma-4-E2B-it.litertlm"
PART_FILE = MODEL_FILE + ".part"
HIDDEN_FILE = MODEL_FILE + ".uicheck-hidden"
MODEL_SIZE_BYTES = 2_588_147_712
FREE_SPACE_NEEDED = MODEL_SIZE_BYTES + 200_000_000  # requiredFreeBytes(0) on both platforms
CONTINUE_FULL = r"Continuar \(2\.6 GB de 2\.6 GB\)"
ADB = os.path.expanduser(os.environ.get("ADB", "~/Library/Android/sdk/platform-tools/adb"))
STUDIO_JDK = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# No ':' or trailing '.': AXe types ':' as 'Ñ' with a Spanish keyboard layout, and adb drops a trailing '.'.
SENTENCE = "Necesito 3 cajas antes de las 10, por favor"
LOAD_TIMEOUT_S = 180
TRANSLATE_TIMEOUT_S = 120


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, text=True, capture_output=True, **kw).stdout


class Fail(Exception):
    pass


class Node:
    def __init__(self, label, kind, x, y, w, h):
        self.label, self.kind = label, kind
        self.cx, self.cy = x + w / 2, y + h / 2

    def __repr__(self):
        return f"{self.kind} {self.label!r} @({self.cx:.0f},{self.cy:.0f})"


class Ios:
    name = "ios"

    def __init__(self):
        self.udid = os.environ.get("SIM_UDID") or self._booted_iphone()

    @staticmethod
    def _booted_iphone():
        devices = json.loads(run(["xcrun", "simctl", "list", "devices", "booted", "-j"]))["devices"]
        for runtime in devices.values():
            for d in runtime:
                if "iPhone" in d["name"]:
                    return d["udid"]
        raise Fail("no booted iPhone simulator (boot one or set SIM_UDID)")

    def describe(self):
        devices = json.loads(run(["xcrun", "simctl", "list", "devices", "-j"]))["devices"]
        for runtime, ds in devices.items():
            for d in ds:
                if d["udid"] == self.udid:
                    os_name = runtime.rsplit(".", 1)[-1].replace("iOS-", "iOS ").replace("-", ".")
                    return f"iOS Simulator {d['name']} ({os_name})"
        return f"iOS Simulator {self.udid}"

    def build_and_install(self):
        args = ["-project", "ios/Translator.xcodeproj", "-scheme", "Translator",
                "-destination", f"platform=iOS Simulator,id={self.udid}"]
        subprocess.run(["xcodebuild", "build", "-quiet", *args, "CODE_SIGNING_ALLOWED=NO"], cwd=ROOT, check=True)
        settings = run(["xcodebuild", "-showBuildSettings", *args], cwd=ROOT)
        products = re.search(r"^\s*BUILT_PRODUCTS_DIR = (.+)$", settings, re.M).group(1)
        run(["xcrun", "simctl", "install", self.udid, f"{products}/Translator.app"])

    def prepare(self):
        for key in ("KeyboardAutocorrection", "KeyboardPrediction", "KeyboardAutocapitalization", "KeyboardCheckSpelling"):
            run(["xcrun", "simctl", "spawn", self.udid, "defaults", "write", "com.apple.Preferences", key, "-bool", "NO"])

    def stop(self):
        subprocess.run(["xcrun", "simctl", "terminate", self.udid, APP_ID], capture_output=True)

    def relaunch(self):
        self.stop()
        run(["xcrun", "simctl", "launch", self.udid, APP_ID])

    def _support(self):
        return Path(run(["xcrun", "simctl", "get_app_container", self.udid, APP_ID, "data"]).strip()) / "Library/Application Support"

    def stat(self, name):
        """(size, mtime) of a file in the model directory, or None."""
        try:
            st = (self._support() / name).stat()
            return st.st_size, int(st.st_mtime)
        except FileNotFoundError:
            return None

    def mv(self, src, dst):
        (self._support() / src).rename(self._support() / dst)

    @contextlib.contextmanager
    def offline(self):
        """The Simulator cannot go offline on its own: prove instead that the app opens no socket."""
        found, stop = set(), threading.Event()

        def watch():
            while not stop.is_set():
                pids = subprocess.run(["pgrep", "-f", f"{self.udid}.*/Translator.app/Translator"],
                                      capture_output=True, text=True).stdout.split()
                for pid in pids:
                    out = subprocess.run(["lsof", "-nP", "-a", "-i", "-p", pid], capture_output=True, text=True).stdout
                    found.update(out.splitlines()[1:])
                time.sleep(0.5)

        t = threading.Thread(target=watch, daemon=True)
        t.start()
        proof = Proof()
        try:
            yield proof
        finally:
            stop.set()
            t.join()
            proof.ok = not found
            proof.detail = ("app opened no socket (lsof every 0.5 s; the Simulator shares the Mac's network)"
                            if not found else f"app opened sockets: {sorted(found)[:3]}")

    def nodes(self):
        out = []

        def walk(n):
            if n.get("type") in ("TextArea", "TextField"):
                label = n.get("AXValue") or ""  # its AXLabel is the placeholder
            else:
                label = n.get("AXLabel") or n.get("AXValue") or ""
            f = n.get("frame") or {}
            out.append(Node(label, n.get("type", ""), f.get("x", 0), f.get("y", 0), f.get("width", 0), f.get("height", 0)))
            for c in n.get("children") or []:
                walk(c)

        for n in json.loads(run(["axe", "describe-ui", "--udid", self.udid])):
            walk(n)
        return out

    def is_text_field(self, node):
        return node.kind in ("TextArea", "TextField")

    def tap(self, node):
        run(["axe", "tap", "-x", str(round(node.cx)), "-y", str(round(node.cy)), "--tap-style", "physical", "--udid", self.udid])

    def type(self, text):
        run(["axe", "type", text, "--udid", self.udid])

    def screenshot(self, path):
        run(["axe", "screenshot", "--udid", self.udid, "--output", str(path)])


class Android:
    name = "android"

    def describe(self):
        model = self.adb("shell", "getprop", "ro.product.model").strip()
        sdk = self.adb("shell", "getprop", "ro.build.version.sdk").strip()
        return f"Android {model} (API {sdk})"

    def adb(self, *args):
        return run([ADB, *args])

    def build_and_install(self):
        env = dict(os.environ)
        if "JAVA_HOME" not in env and Path(STUDIO_JDK).is_dir():
            env["JAVA_HOME"] = STUDIO_JDK
        subprocess.run(["./gradlew", "-q", "installDebug"], cwd=ROOT / "android", env=env, check=True)

    def prepare(self):
        self.adb("get-state")

    def stop(self):
        self.adb("shell", "am", "force-stop", APP_ID)

    def relaunch(self):
        self.stop()
        self.adb("shell", "am", "start", "-n", ANDROID_ACTIVITY)

    def run_as(self, *args):
        return subprocess.run([ADB, "shell", "run-as", APP_ID, *args], capture_output=True, text=True)

    def stat(self, name):
        out = self.run_as("stat", "-c", "%s:%Y", f"files/{name}")  # no spaces: adb shell re-splits arguments
        if out.returncode:
            return None
        size, mtime = out.stdout.strip().split(":")
        return int(size), int(mtime)

    def mv(self, src, dst):
        if self.run_as("mv", f"files/{src}", f"files/{dst}").returncode:
            raise Fail(f"could not rename {src} to {dst}")

    def free_bytes(self):
        return int(self.adb("shell", "df", "/data").splitlines()[-1].split()[3]) * 1024

    @contextlib.contextmanager
    def offline(self):
        self.adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
        time.sleep(3)
        proof = Proof()
        try:
            yield proof
            on = self.adb("shell", "settings", "get", "global", "airplane_mode_on").strip() == "1"
            proof.ok, proof.detail = on, "airplane mode on" if on else "airplane mode was turned off during the run"
        finally:
            self.adb("shell", "cmd", "connectivity", "airplane-mode", "disable")
            time.sleep(3)

    @contextlib.contextmanager
    def wifi_off(self):
        self.adb("shell", "svc", "wifi", "disable")
        time.sleep(4)
        try:
            yield
        finally:
            self.adb("shell", "svc", "wifi", "enable")
            time.sleep(4)

    @contextlib.contextmanager
    def low_space(self, below):
        """Makes free space at most `below` bytes with a filler file in the app's cache, if needed."""
        filler = "cache/uicheck-filler"
        free = self.free_bytes()
        try:
            if free >= below:
                if self.run_as("fallocate", "-l", str(free - below + 300_000_000), filler).returncode:
                    raise Fail("could not create the filler file")
            yield self.free_bytes()
        finally:
            self.run_as("rm", "-f", filler)

    def nodes(self):
        for _ in range(8):  # uiautomator fails now and then while the screen animates
            self.adb("shell", "rm", "-f", "/sdcard/ui.xml")
            subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
            xml = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
            if xml.startswith("<?xml"):
                break
            time.sleep(1)
        else:
            raise Fail("uiautomator dump failed")
        out = []
        for n in ET.fromstring(xml).iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
            label = n.get("text") or n.get("content-desc") or ""
            out.append(Node(label, n.get("class", ""), x1, y1, x2 - x1, y2 - y1))
        return out

    def is_text_field(self, node):
        return node.kind.endswith("EditText")

    def tap(self, node):
        self.adb("shell", "input", "tap", str(round(node.cx)), str(round(node.cy)))

    def type(self, text):
        # `input text` drops characters on a busy emulator: one word at a time (the caller checks and retries).
        words = text.split(" ")
        for i, word in enumerate(words):
            self.adb("shell", "input", "text", word + ("%s" if i < len(words) - 1 else ""))
            time.sleep(0.3)

    def clear_field(self):
        self.adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        self.adb("shell", "input", "keyevent", "KEYCODE_DEL")

    def screenshot(self, path):
        with open(path, "wb") as f:
            subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True)


class Proof:
    """Filled in when an offline() block ends: whether the run really had no network."""
    ok = False
    detail = ""


class Run:
    def __init__(self, device, out):
        self.device, self.out = device, out
        self.step = 0
        self.results = []  # (scenario, check, ok (None = skipped), detail, screenshot)

    def evidence(self, name):
        """Screenshot + UI tree for the current screen; returns the screenshot file name."""
        self.step += 1
        stem = f"{self.step:02d}-{name}"
        self.device.screenshot(self.out / f"{stem}.png")
        (self.out / f"{stem}.ui.txt").write_text("\n".join(map(repr, self.device.nodes())) + "\n")
        return f"{stem}.png"

    def check(self, scenario, name, ok, detail="", shot=None):
        """Records a check; takes a new screenshot unless `shot` reuses the last one."""
        shot = shot or self.evidence(name)
        if not ok and any("isn't responding" in l or "no responde" in l for l in self.labels()):
            detail += " [ANR dialog on screen; see `adb shell dumpsys dropbox --print data_app_anr`]"
        self.results.append((scenario, name, ok, detail, shot))
        print(f"{'PASS' if ok else 'FAIL'}  {scenario}: {name}  {detail}")
        if not ok:
            raise Fail(f"{name}: {detail}")

    def skip(self, scenario, reason):
        self.results.append((scenario, "-", None, reason, ""))
        print(f"SKIP  {scenario}: {reason}")

    def find(self, pattern, timeout=15.0, field=False):
        """First node whose label matches `pattern` (or the first text field), polling until `timeout`."""
        deadline = time.monotonic() + timeout
        while True:
            for n in self.device.nodes():
                if (field and self.device.is_text_field(n)) or (not field and re.fullmatch(pattern, n.label.strip())):
                    return n
            if time.monotonic() > deadline:
                return None
            time.sleep(1)

    def labels(self):
        return [n.label.strip() for n in self.device.nodes() if n.label.strip()]

    def tap(self, pattern, timeout=15.0):
        node = self.find(pattern, timeout)
        if not node:
            raise Fail(f"nothing on screen matches {pattern!r}")
        self.device.tap(node)


DOWNLOAD_ERROR = r"Sin conexión.*|No hay espacio suficiente.*|La descarga está dañada.*|No se ha podido guardar.*|El servidor del modelo.*|La descarga ha fallado.*|Esperando al wifi.*"
PROGRESS = r"[\d.]+ GB de 2\.6 GB"


def ready_or_failure(labels):
    """'ready', a failure reason, or None while still loading, verifying or downloading."""
    if "Escribir" in labels:
        return "ready"
    if any(l.startswith("¿Qué idioma habla tu compañero") for l in labels):
        return "first run: pick the partner language once, then rerun"
    errors = [l for l in labels if re.fullmatch(DOWNLOAD_ERROR, l)]
    if errors:
        return f"download screen: {errors[0]}"
    if any(l == "Descargar" or l.startswith("Continuar (") for l in labels):
        return "model not installed: let the app download it first (never fetch it yourself)"
    return None


def wait_until_ready(r, scenario, relaunch=True):
    """(Re)launch and wait for the conversation screen; fails clearly if the model is not installed."""
    if relaunch:
        r.device.relaunch()
    deadline = time.monotonic() + LOAD_TIMEOUT_S
    while time.monotonic() < deadline:
        state = ready_or_failure(r.labels())
        if state == "ready":
            return r.check(scenario, "app-ready", True, "model loaded, conversation screen shown")
        if state:
            r.check(scenario, "app-ready", False, state)
        time.sleep(2)
    r.check(scenario, "app-ready", False, f"conversation screen not shown after {LOAD_TIMEOUT_S}s")


def source_bubbles(r):
    """Sent messages showing SENTENCE (text fields excluded)."""
    return sum(1 for n in r.device.nodes() if not r.device.is_text_field(n) and n.label.strip().rstrip(" .") == SENTENCE)


def translate_steps(r, s):
    before = source_bubbles(r)  # the conversation survives relaunches: only a new bubble counts
    r.tap("Escribir")
    field = r.find("", field=True)
    if not field:
        r.check(s, "write-sheet", False, "no text field")
    r.device.tap(field)
    time.sleep(1)
    for attempt in range(3):
        r.device.type(SENTENCE)
        time.sleep(1)
        field = r.find("", field=True)
        typed = field.label.strip() if field else ""
        if typed.rstrip(" .") == SENTENCE or not hasattr(r.device, "clear_field"):
            break
        print(f"retyping: field held {typed!r}")
        r.device.clear_field()
        time.sleep(0.5)
    r.check(s, "typed", typed.rstrip(" .") == SENTENCE, f"field holds {typed!r}")
    r.tap("Traducir")

    deadline = time.monotonic() + TRANSLATE_TIMEOUT_S
    translation = None
    while time.monotonic() < deadline:
        time.sleep(2)
        labels = r.labels()
        if any(l.startswith("Traduciendo") for l in labels) or source_bubbles(r) <= before:
            continue
        # The bubble lists the translation right before the source text.
        src = [i for i, l in enumerate(labels) if l.rstrip(" .") == SENTENCE]
        if src and src[-1] > 0:
            translation = labels[src[-1] - 1]
            break
    if translation is None:
        r.check(s, "translated", False, f"no translation within {TRANSLATE_TIMEOUT_S}s")
    r.check(s, "translated", translation.rstrip(" .") != SENTENCE, f"{translation!r}")
    shot = r.results[-1][4]
    missing = [d for d in ("3", "10") if d not in translation]
    r.check(s, "numbers-kept", not missing, f"missing {missing}" if missing else "3 and 10 present", shot)
    errors = [l for l in r.labels() if re.search(r"\b(error|fallado|Sin conexión)\b", l, re.I)]
    r.check(s, "no-error", not errors, f"error on screen: {errors[0]!r}" if errors else "no error text on screen", shot)


@contextlib.contextmanager
def model_moved(r, s, to):
    """Renames the installed model to `to` for the block, and always back afterwards."""
    d = r.device
    d.stop()
    original = d.stat(MODEL_FILE)
    if not original or original[0] != MODEL_SIZE_BYTES or d.stat(PART_FILE) or d.stat(HIDDEN_FILE):
        r.check(s, "precondition", False, f"needs only the installed model in the app's files (found model={original}, "
                                          f"part={d.stat(PART_FILE)}, hidden={d.stat(HIDDEN_FILE)})")
    d.mv(MODEL_FILE, to)
    try:
        yield original
    finally:
        d.stop()
        part = d.stat(PART_FILE)
        if not d.stat(MODEL_FILE):
            if d.stat(HIDDEN_FILE):
                d.mv(HIDDEN_FILE, MODEL_FILE)
            elif part and part[0] == MODEL_SIZE_BYTES:
                d.mv(PART_FILE, MODEL_FILE)  # the same file, already verified once
        if d.stat(HIDDEN_FILE) and d.stat(MODEL_FILE):
            print("WARNING: both the model and the hidden copy exist; delete one by hand", file=sys.stderr)
        if not d.stat(MODEL_FILE):
            print("WARNING: the model is no longer installed; let the app download it", file=sys.stderr)


def scenario_translate(r):
    wait_until_ready(r, "translate")
    translate_steps(r, "translate")


def scenario_airplane(r):
    s = "airplane"
    with r.device.offline() as proof:
        wait_until_ready(r, s)
        translate_steps(r, s)
    r.check(s, "offline", proof.ok, proof.detail, r.results[-1][4])


def scenario_kill_verifying(r):
    s = "kill-verifying"
    d = r.device
    with model_moved(r, s, PART_FILE) as original:
        d.relaunch()
        button = r.find(CONTINUE_FULL, 30)
        r.check(s, "offers-continue", bool(button), "complete .part offered as Continuar (2.6 GB de 2.6 GB)"
                if button else "Continuar (2.6 GB de 2.6 GB) not shown within 30 s")
        # Verification takes ~1 s on a fast Mac: tap, screenshot only (no UI tree), kill.
        d.tap(button)
        time.sleep(0.2)
        r.step += 1
        shot = f"{r.step:02d}-{s}-just-before-kill.png"
        d.screenshot(r.out / shot)
        d.stop()
        killed_in_time = d.stat(PART_FILE) == original and not d.stat(MODEL_FILE)
        r.check(s, "killed-while-verifying", killed_in_time,
                "killed before install: complete .part left, no model" if killed_in_time
                else "verification finished before the kill; rerun (the timing is tight on fast Macs)", shot)

        with d.offline() as proof:
            d.relaunch()
            button = r.find(CONTINUE_FULL, 30)
            r.check(s, "relaunch-offers-continue", bool(button), "still Continuar (2.6 GB de 2.6 GB)" if button
                    else "Continuar (2.6 GB de 2.6 GB) not shown after relaunch")
            d.tap(button)
            deadline = time.monotonic() + LOAD_TIMEOUT_S
            state, downloading_since = None, None
            while time.monotonic() < deadline and not state:
                labels = r.labels()
                if any(re.fullmatch(PROGRESS, l) for l in labels):
                    # Android shows the progress state for a moment before verifying; longer means a download.
                    downloading_since = downloading_since or time.monotonic()
                    if time.monotonic() - downloading_since > 10:
                        r.evidence(f"{s}-downloading")
                        r.tap("Cancelar", 5)
                        state = "started downloading again (cancelled)"
                        break
                else:
                    downloading_since = None
                state = ready_or_failure(labels)
                time.sleep(1)
            if state and state.startswith("first run"):
                state = "ready"  # verification and load worked; the picker is not this scenario's concern
            r.check(s, "verified-and-loaded", state == "ready", state or "timed out")
        r.check(s, "same-file", d.stat(MODEL_FILE) == original, f"model installed with the original size and mtime {original}",
                r.results[-1][4])
        r.check(s, "offline", proof.ok, proof.detail, r.results[-1][4])


def scenario_cellular(r):
    s = "cellular"
    d = r.device
    if not hasattr(d, "wifi_off"):
        return r.skip(s, "the Simulator cannot fake an expensive (cellular) network")
    with model_moved(r, s, HIDDEN_FILE), d.wifi_off():
        d.relaunch()
        r.tap("Descargar", 30)
        dialog = r.find(r"¿Usar datos móviles\?", 10)
        if not dialog and r.find(PROGRESS, 2):
            r.tap("Cancelar", 5)
        r.check(s, "asks-before-mobile-data", bool(dialog), "dialog ¿Usar datos móviles? shown" if dialog
                else "no dialog on mobile data (download cancelled if it had started)")
        r.tap("Esperar al wifi")
        time.sleep(1)
        part = d.stat(PART_FILE)
        r.check(s, "wait-for-wifi", r.find("Descargar", 5) is not None and not part,
                "back to Descargar, nothing downloaded" if not part else f"a .part exists: {part}")


def scenario_low_space(r):
    s = "low-space"
    d = r.device
    if not hasattr(d, "low_space"):
        return r.skip(s, "the Simulator uses the Mac's disk; free space cannot be lowered safely")
    with model_moved(r, s, HIDDEN_FILE), d.low_space(FREE_SPACE_NEEDED - 500_000_000) as free:
        if free >= FREE_SPACE_NEEDED:
            r.check(s, "precondition", False, f"free space still {free} bytes; not tapping Descargar")
        d.relaunch()
        r.tap("Descargar", 30)
        msg = r.find(r"No hay espacio suficiente\..*", 15)
        part = d.stat(PART_FILE)
        r.check(s, "blocks-download", bool(msg) and not part,
                f"{msg.label!r} with {free / 1e9:.1f} GB free" if msg else f"no space message (.part: {part})")


SCENARIOS = {
    "translate": scenario_translate,
    "airplane": scenario_airplane,
    "kill-verifying": scenario_kill_verifying,
    "cellular": scenario_cellular,
    "low-space": scenario_low_space,
}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True).stdout.strip()


def write_summary(r, scenarios, errors):
    ok = not errors and all(res[2] is not False for res in r.results)
    dirty = " (uncommitted changes)" if git("status", "--porcelain", "--untracked-files=no") else ""
    lines = [
        f"## UI check: {'PASS' if ok else 'FAIL'}",
        "",
        f"- Device: {r.device.describe()}",
        f"- Commit: `{git('rev-parse', '--short', 'HEAD')}` on `{git('branch', '--show-current')}`{dirty}",
        f"- Scenarios: {', '.join(scenarios)}",
        f"- Run: {datetime.now().isoformat(timespec='seconds')}, `scripts/ui-check.py {r.device.name} {' '.join(scenarios)}`",
        "",
        "| Scenario | Check | Result | Detail | Screenshot |",
        "|---|---|---|---|---|",
    ]
    for scenario, name, passed, detail, shot in r.results:
        result = "SKIP" if passed is None else "PASS" if passed else "**FAIL**"
        lines.append(f"| {scenario} | {name} | {result} | {detail.replace('|', '/')} | {shot} |")
    if errors:
        lines += ["", "Errors:", *[f"- {e}" for e in errors]]
    (r.out / "summary.md").write_text("\n".join(lines) + "\n")
    return ok


def main(argv):
    build = "--no-build" not in argv
    args = [a for a in argv if a != "--no-build"]
    if not args or args[0] not in ("ios", "android") or any(a not in SCENARIOS and a != "all" for a in args[1:]):
        print(__doc__, file=sys.stderr)
        return 2
    platform, scenarios = args[0], args[1:] or ["translate"]
    if "all" in scenarios:
        scenarios = list(SCENARIOS)

    out = ROOT / "evidence" / f"{datetime.now():%Y%m%d-%H%M%S}-{platform}"
    out.mkdir(parents=True)
    errors = []
    try:
        device = Ios() if platform == "ios" else Android()
        r = Run(device, out)
        if build:
            device.build_and_install()
        device.prepare()
    except (Fail, subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    for name in scenarios:  # a failed scenario does not stop the others
        try:
            SCENARIOS[name](r)
        except (Fail, subprocess.CalledProcessError, FileNotFoundError) as e:
            errors.append(f"{name}: {e}")
            print(f"ERROR: {name}: {e}", file=sys.stderr)
    ok = write_summary(r, scenarios, errors)
    print(f"\n{'PASS' if ok else 'FAIL'}: evidence in {out.relative_to(ROOT)}/ (summary.md)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
