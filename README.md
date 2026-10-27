# llm-on-mobile-device

A minimalist **on-device translator** for Android and iOS. It runs **Gemma 4 E2B** locally with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (CPU backend, thinking off).

- Type or paste text in any language, pick a target language, get a streamed translation.
- Internet is used **once**, on first launch, to download the model (~2.6 GB) from Hugging Face. After that the app is fully offline (works in airplane mode).
- No analytics, telemetry or any other network call. Your text never leaves the device.

> **Status:** work in progress. The implementation follows [`SPEC.md`](SPEC.md) milestone by milestone.

## Apps

| Platform | Folder | Stack | Requirements |
|---|---|---|---|
| Android | `android/` | Kotlin, Jetpack Compose, Material 3 Expressive | `compileSdk`/`targetSdk` 37 (Android 17), `minSdk` 26, JDK 17+ |
| iOS | `ios/` | Swift, SwiftUI, Liquid Glass | iOS 27, Xcode 27 |

**Devices:** 8 GB RAM recommended; ~2.8 GB free storage for the first download.

## Model

| | |
|---|---|
| Model | [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) → `gemma-4-E2B-it.litertlm` |
| Size | 2,588,147,712 bytes |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Licence | Apache-2.0 (not gated). Gemma is provided by Google. |

The model is downloaded by the app at runtime and is **never** committed to this repo or bundled in the apps.

## Docs

- [`SPEC.md`](SPEC.md): full specification and milestones (source of truth).
- [`AGENTS.md`](AGENTS.md): rules for coding agents.

## Testing on emulator / simulator

Besides unit tests, the apps are checked end to end on the Android emulator and the iOS Simulator by driving the real UI from the command line, so coding agents can do it too:

- **Android:** `adb` (`uiautomator dump` to read the screen, `input tap` / `input text` to interact, `screencap` for screenshots).
- **iOS:** [AXe](https://github.com/cameroncooke/AXe) for UI interaction (`describe-ui`, `tap`, `type`, `screenshot`), plus `xcodebuild` or Xcode's MCP server (`xcrun mcpbridge`) for building, running and logs.

The app performs its own first-run model download there; nothing fetches the model separately. Details in [`AGENTS.md`](AGENTS.md).

## CI

GitHub Actions build the apps and run the unit tests (`.github/workflows/android.yml`, `.github/workflows/ios.yml`). They never download the model.
