# AGENTS.md

**Project:** minimalist on-device translator app. Runs Gemma 4 E2B
(`gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`, 2,588,147,712 bytes) locally with LiteRT-LM, CPU backend, thinking off.
On first use the app downloads the model once from Hugging Face (pinned commit URL in SPEC.md §2.1); after that it is offline forever.
Two native apps in this repo: `android/` (Kotlin + Jetpack Compose) and `ios/` (Swift + SwiftUI).

**Source of truth:** [`SPEC.md`](SPEC.md) at the repo root. Read it fully before coding; follow its milestones in order.

**Hard constraints**
- Network is used ONLY for the one-time model download, and all network code lives in one file per platform (`ModelDownloader.kt` / `ModelDownloader.swift`). Translation is 100% local and must work in airplane mode. No analytics, telemetry, SDKs or any other network call.
- Android permissions: exactly `INTERNET` + `ACCESS_NETWORK_STATE`. Download with plain `HttpURLConnection` (no OkHttp/WorkManager/DownloadManager); iOS with plain `URLSession`.
- Never commit or bundle model files (`*.litertlm`, `*.part`, `*.task`); the model is ~2.6 GB (Apache-2.0, not gated). URL, name, size and SHA-256 live in one constants block per platform; don't swap models silently.
- Model download: agents must never fetch the model themselves (no `curl`/`wget`/`huggingface-cli`/scripts), and CI never downloads it. Agents may build, install and run the apps on the Android emulator / iOS Simulator; letting the app perform its own first-run download there is allowed.
- Platforms: Android `compileSdk`/`targetSdk` 37 (Android 17), `minSdk` 26. iOS deployment target 27.0, built with Xcode 27.
- Android UI: Jetpack Compose with Material 3 Expressive. iOS UI: SwiftUI with Liquid Glass.
- Android LiteRT-LM code must follow the official skill (model delivery = its Path B, runtime download into `filesDir`):
  https://github.com/android/skills/blob/main/device-ai/litert/litert-lm/SKILL.md

**Simplicity first**
- One Gradle module (`:app`) and one Xcode target. A handful of files per platform.
- One state holder per app (one `AndroidViewModel`; one `@Observable` class). No DI frameworks, no clean-architecture layers, no navigation library, no single-implementation interfaces.
- Don't add dependencies beyond those in SPEC.md. Don't invent APIs: verify against the skill and official LiteRT-LM docs.

**Verifying on emulator / simulator (local only, never in CI)**

Drive the real app UI, read the screen from the accessibility/UI tree (take coordinates from it, never guess them from a screenshot), and take screenshots as evidence. Uninstalling the app deletes the downloaded model; the next launch downloads it again through the app.

*Android emulator* (`ADB=~/Library/Android/sdk/platform-tools/adb`; JDK from Android Studio: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`)
- Start: `~/Library/Android/sdk/emulator/emulator -avd <avd> -no-snapshot-save -no-boot-anim` (list AVDs with `-list-avds`), then wait until `$ADB shell getprop sys.boot_completed` is `1`. Stop with `$ADB emu kill`.
- Install and launch: `./gradlew installDebug` in `android/`, then `$ADB shell am start -n dev.heikki.translator/.MainActivity` (`am force-stop` to restart).
- Read the UI: `$ADB shell uiautomator dump /sdcard/ui.xml && $ADB shell cat /sdcard/ui.xml` (use `text`, `content-desc` and `bounds`).
- Interact: `$ADB shell input tap <x> <y>`, `input text "Hello%sworld"` (`%s` is a space; tap the field first), `input keyevent KEYCODE_BACK`.
- Screenshot: `$ADB exec-out screencap -p > file.png`. Logs: `$ADB logcat -d`.
- Model files: `$ADB shell run-as dev.heikki.translator ls -l files` (the `.part` size shows download progress).
- Airplane mode: `$ADB shell cmd connectivity airplane-mode enable|disable`.

*iOS Simulator*
- Build, test, run: `xcodebuild` (see `.github/workflows/ios.yml`), or Xcode's MCP server (`xcrun mcpbridge`). For the MCP, enable Xcode → Settings → Intelligence → "Allow external agents to use Xcode tools", then call `XcodeOpenWorkspace` on `ios/Translator.xcodeproj` first so Xcode asks for approval. Its `DeviceInteractionInstallAndRun`, console output and crash logs are useful.
- Interact with [AXe](https://github.com/cameroncooke/AXe) for everything (every command needs `--udid <UDID>` and a booted simulator): `axe describe-ui` (JSON tree), `axe tap -x <x> -y <y>` or `axe tap --label "Translate"`, `axe type "<text>"` (tap the field first), `axe swipe`, `axe button home`, `axe screenshot --output file.png`. Right after a cold boot, label taps can time out; retry or use coordinates. Use `--tap-style physical` on taps (the default style may not focus a `TextEditor` or fire glass buttons). Before typing, turn off autocorrect: `xcrun simctl spawn <UDID> defaults write com.apple.Preferences KeyboardAutocorrection -bool NO` (same for `KeyboardPrediction`, `KeyboardAutocapitalization`, `KeyboardCheckSpelling`). If typing doesn't reach a field, `xcrun simctl pbcopy` and tap Paste. Check copy with `xcrun simctl pbpaste <UDID>`; Dynamic Type with `xcrun simctl ui <UDID> content_size accessibility-extra-extra-extra-large`.
- Don't use Xcode MCP's `DeviceInteractionSynthesize` for input: it can tap (`t x y`) and wait (`w <s>`) but can't type, and it reports `Crashed` when the app is relaunched outside its session.
- Boot / relaunch: `xcrun simctl boot <UDID> && xcrun simctl bootstatus <UDID> -b`, `xcrun simctl launch|terminate|uninstall <UDID> dev.heikki.translator`.

**CI:** two workflows in `.github/workflows/` (`android.yml`, `ios.yml`) only build and run tests (SPEC.md §3.1). No release, signing or distribution steps. Keep them green.

**Done means:** builds without the model present, tests pass locally and in CI, network-scope and airplane-mode checks in SPEC.md §8–§9 pass.
