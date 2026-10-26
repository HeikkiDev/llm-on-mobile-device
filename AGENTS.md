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

**CI:** two workflows in `.github/workflows/` (`android.yml`, `ios.yml`) only build and run tests (SPEC.md §3.1). No release, signing or distribution steps. Keep them green.

**Done means:** builds without the model present, tests pass locally and in CI, network-scope and airplane-mode checks in SPEC.md §8–§9 pass.
