#!/usr/bin/env python3
"""Drive the real app on the iOS Simulator or Android emulator and save evidence.

    scripts/ui-check.py ios|android [scenario ...] [--no-build]

Scenarios (default: translate):
    translate   type a Spanish sentence, translate it, check the result

Each run writes evidence/<timestamp>-<platform>/ (gitignored): numbered screenshots, the UI tree
at every step, and summary.md with PASS/FAIL per check. Exit code 0 only if every check passed.
Paste summary.md into the PR and attach the screenshots (see .github/pull_request_template.md).

Local only, never in CI. Needs a booted simulator/emulator with the model already installed by
the app itself: this script never downloads the model (AGENTS.md). Tools: iOS uses xcodebuild,
simctl and AXe; Android uses adb (ANDROID_SERIAL picks the device) and Gradle.
Pick the simulator with SIM_UDID (default: the first booted iPhone).
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_ID = "dev.heikki.walter"
ANDROID_ACTIVITY = f"{APP_ID}/dev.heikki.translator.MainActivity"
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

    def relaunch(self):
        subprocess.run(["xcrun", "simctl", "terminate", self.udid, APP_ID], capture_output=True)
        run(["xcrun", "simctl", "launch", self.udid, APP_ID])

    def nodes(self):
        out = []

        def walk(n):
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

    def relaunch(self):
        self.adb("shell", "am", "force-stop", APP_ID)
        self.adb("shell", "am", "start", "-n", ANDROID_ACTIVITY)

    def nodes(self):
        for _ in range(3):  # uiautomator fails now and then while the screen animates
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
        self.adb("shell", "input", "text", text.replace(" ", "%s"))

    def screenshot(self, path):
        with open(path, "wb") as f:
            subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, check=True)


class Run:
    def __init__(self, device, out):
        self.device, self.out = device, out
        self.step = 0
        self.results = []  # (scenario, check, ok, detail, screenshot)

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
        self.results.append((scenario, name, ok, detail, shot))
        print(f"{'PASS' if ok else 'FAIL'}  {scenario}: {name}  {detail}")
        if not ok:
            raise Fail(f"{name}: {detail}")

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


def wait_until_ready(r, scenario):
    """Relaunch and wait for the conversation screen; fails clearly if the model is not installed."""
    r.device.relaunch()
    deadline = time.monotonic() + LOAD_TIMEOUT_S
    while time.monotonic() < deadline:
        labels = r.labels()
        if "Escribir" in labels:
            return r.check(scenario, "app-ready", True, "model loaded, conversation screen shown")
        if "Descarga el modelo de idiomas" in labels and not any("Comprobando" in l or "Cargando" in l for l in labels):
            r.check(scenario, "app-ready", False, "model not installed: let the app download it first (never fetch it yourself)")
        if any(l.startswith("¿Qué idioma habla tu compañero") for l in labels):
            r.check(scenario, "app-ready", False, "first run: pick the partner language once, then rerun")
        time.sleep(2)
    r.check(scenario, "app-ready", False, f"conversation screen not shown after {LOAD_TIMEOUT_S}s")


def source_bubbles(r):
    """Sent messages showing SENTENCE (text fields excluded)."""
    return sum(1 for n in r.device.nodes() if not r.device.is_text_field(n) and n.label.strip().rstrip(" .") == SENTENCE)


def scenario_translate(r):
    s = "translate"
    wait_until_ready(r, s)
    before = source_bubbles(r)  # the conversation survives relaunches: only a new bubble counts
    r.tap("Escribir")
    field = r.find("", field=True)
    if not field:
        r.check(s, "write-sheet", False, "no text field")
    r.device.tap(field)
    time.sleep(1)
    r.device.type(SENTENCE)
    time.sleep(1)
    r.evidence("typed")
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


SCENARIOS = {"translate": scenario_translate}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True).stdout.strip()


def write_summary(r, scenarios, error):
    ok = error is None and all(res[2] for res in r.results)
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
        lines.append(f"| {scenario} | {name} | {'PASS' if passed else '**FAIL**'} | {detail.replace('|', '/')} | {shot} |")
    if error and not any(not res[2] for res in r.results):
        lines += ["", f"Aborted: {error}"]
    (r.out / "summary.md").write_text("\n".join(lines) + "\n")
    return ok


def main(argv):
    build = "--no-build" not in argv
    args = [a for a in argv if a != "--no-build"]
    if not args or args[0] not in ("ios", "android") or any(a not in SCENARIOS for a in args[1:]):
        print(__doc__, file=sys.stderr)
        return 2
    platform, scenarios = args[0], args[1:] or ["translate"]

    out = ROOT / "evidence" / f"{datetime.now():%Y%m%d-%H%M%S}-{platform}"
    out.mkdir(parents=True)
    error = None
    try:
        device = Ios() if platform == "ios" else Android()
        r = Run(device, out)
        if build:
            device.build_and_install()
        device.prepare()
        for name in scenarios:
            SCENARIOS[name](r)
    except (Fail, subprocess.CalledProcessError, FileNotFoundError) as e:
        error = e
        print(f"ERROR: {e}", file=sys.stderr)
        if "r" not in locals():
            return 1
    ok = write_summary(r, scenarios, error)
    print(f"\n{'PASS' if ok else 'FAIL'}: evidence in {out.relative_to(ROOT)}/ (summary.md)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
