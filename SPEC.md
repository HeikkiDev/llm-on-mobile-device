# SPEC — On-device translator, offline after a one-time model download (Android + iOS)

> Source of truth for the implementation. Written for a coding agent (GitHub Copilot).
> Last verified against upstream sources: 2026-10-26 (download URL, redirect, Range support and no-auth verified with live requests). Model: **Gemma 4 E2B** from [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (the model's source of truth).

## 0. Simplicity first (read this before writing any code)

This is a **tiny, minimalist app**. Simplicity beats everything else, including "best practices" meant for large apps.

- **One module per platform.** Android: a single `:app` Gradle module. iOS: a single Xcode app target.
- **A handful of files.** Target ≈ 5–7 source files per platform (see §3). If you need more, justify it in the PR.
- **One state holder per app.** Android: one `AndroidViewModel`. iOS: one `@Observable` class. No repositories, use cases, interactors, mappers or "domain" layers.
- **No DI frameworks** (no Hilt, Dagger, Koin, Swinject). Construct objects directly.
- **No navigation library.** Two states of one screen: a first-run "Download model" state and the translator, plus a bottom sheet / sheet for the language picker. Switch with a plain `if`/`when` on the model status.
- **No extra libraries** beyond what is listed in this spec. No networking libraries (the one download uses the platform's built-in HTTP: `HttpURLConnection` / `URLSession`), no analytics, crash reporting, logging, or image libraries.
- **UI toolkit is fixed:** **Jetpack Compose** on Android, **SwiftUI** on iOS. No XML layouts, no UIKit views (except what SwiftUI needs implicitly).
- Prefer plain, readable code over abstractions. No interfaces/protocols with a single implementation.

## 1. Overview

A minimalist app that translates text **on-device** using **Gemma 4 E2B** through **LiteRT-LM** (Google AI Edge). The app needs internet **only once**, on first use, to download the model directly from Hugging Face; after that it works **offline forever**.

- The user types or pastes text in any language (source language is **not** selected; the model infers it).
- The user picks a **target language** from a list.
- The app streams the translation token by token.
- **Network rule:** the *only* network access in the whole app is the one-time model download (§2.1), from the pinned Hugging Face URL. Translation is 100 % local and must work in airplane mode once the model is installed. No analytics, telemetry, update checks, crash reporting, fonts/images from the web, or any other network call. Once a valid model is present, the app makes **zero** network requests.

### Non-goals
- No chat / multi-turn conversation, no history, no accounts, no settings screen beyond the target language.
- No speech input/output, OCR, camera, images. Gemma 4 E2B is multimodal, but v1 is **text-only**: never set `visionBackend` / `audioBackend` (the card says vision and audio models are loaded only as needed).
- No model updates, model choice, or second download source in v1. No Play for On-Device AI packs / Play Asset Delivery and no Apple Background Assets (possible later, see §11).
- No NPU builds, no model conversion, no fine-tuning, no speculative decoding (MTP) and no thinking mode in v1.
- No cross-platform framework (no Flutter / KMP / React Native): two small native apps.

## 2. Model (verified)

Source of truth: the model card of [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (base model card: [`google/gemma-4-E2B-it`](https://huggingface.co/google/gemma-4-E2B-it)).

| Item | Value |
|---|---|
| Model | **Gemma 4 E2B instruction-tuned** (2.3B effective params, 5.1B with embeddings). Smallest Gemma 4 model; LiteRT-LM supports E2B and E4B. It is also the default model recommended by the Android litert-lm skill and the one used by the official LiteRT-LM iOS sample. |
| Hugging Face repo | [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| File to use | **`gemma-4-E2B-it.litertlm`** (Gemma 4 mobile quantization: mix of 2/4/8-bit weights) |
| Size | **2,588,147,712 bytes (~2.41 GiB / ~2.59 GB)**; the card rounds it to "2.58 GB" / 2583 MB |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Pinned revision | last change to the file: commit `6e5c4f1e395deb959c494953478fa5cec4b8008f` (2026-05-04). **Download URL used by the apps:** `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm` |
| License | **Apache-2.0**. The repo is **not gated** (no login, token or terms acceptance needed). Still: **never commit the model file to git** (2.6 GB). |
| Backends | **CPU and GPU** (both benchmarked on the card and listed in the skill). v1 uses **CPU** on both platforms (see §4.3 / §5.2 for why and how to switch). |
| Context | Up to 32K tokens in LiteRT-LM per the card; this app caps the KV cache with `maxNumTokens = 4096` to bound memory. |

Do **not** use the other files in that repo: `gemma-4-E2B-it-gpu.litertlm` (not documented on the card; the card benchmarks the main file on GPU), `*-web.litertlm` / `*-web.task` (web builds), `*_Google_Tensor_G5/G6`, `*_Qualcomm_*`, `*_intel_*`, `*_NPU_Uncompiled*` (NPU builds).

**Performance and memory (model card; 1024 prefill + 256 decode tokens, context 2048):**

| Device | Backend | Decode tok/s | Time to first token | Peak memory |
|---|---|---|---|---|
| Samsung S26 Ultra | CPU | 46.9 | 1.8 s | 1733 MB (CPU) |
| Samsung S26 Ultra | GPU | 52.1 | 0.3 s | 676 MB (CPU) |
| iPhone 17 Pro | CPU | 25.0 | 1.9 s | 607 MB |
| iPhone 17 Pro | GPU | 56.5 | 0.3 s | 1450 MB |

Main weights (~0.8 GB) stay in memory; the 1.12 GB of embedding parameters are memory-mapped. **Minimum RAM:** neither the card nor the LiteRT-LM docs state one. Google's AI Edge Gallery allowlist sets `minDeviceMemoryInGb: 8` for this exact file on Android → treat **8 GB RAM as the recommended minimum**; on smaller devices show a non-blocking warning (§4.5) instead of refusing to run. iOS: no official minimum; verify on the test device (§5.1).

**Android note from the card:** on supported Android devices Gemma 4 is also available through Android AICore as Gemini Nano, which the card calls the recommended path for production. Out of scope here (this project ships its own `.litertlm` via LiteRT-LM by design).

**Model updates:** the file was re-uploaded on 2026-05-04 (MTP drafter + vision encoder update; per the repo discussions, older runtimes then failed with "The Vision Encoder model must have exactly one signature", fixed by `litertlm-android` 0.11.0). Our pinned runtime (0.18.1) is far newer. Because the apps download from the **pinned commit**, a future upstream re-upload does not affect them; moving to a new upload means updating URL + size + SHA-256 together (§2.1).

**Fallback (only if the owner decides; open question §11):** if 2.6 GB / 8 GB RAM is too heavy for target devices, the closest verified smaller LiteRT-LM files are `litert-community/Gemma3-1B-IT` → `gemma3-1b-it-int4.litertlm` (584,417,280 bytes) or `litert-community/gemma-3-270m-it` → `gemma3-270m-it-q8.litertlm` (304,005,120 bytes). Both are Gemma 3 (gated, Gemma Terms of Use; no native system role) and translate noticeably worse. Keep file name, URL, size and SHA-256 in **one constants block per platform** so swapping is a four-line change (note: Gemma 3 is gated, so its download would need a Hugging Face token, which this app does not support; switching would need a rethink).

### 2.1 Model provisioning: one-time download from Hugging Face

The app downloads the model itself on first use, straight from Hugging Face, and stores it in app-private storage. Nothing is bundled in the APK / `.app`.

Verified on 2026-10-26 with anonymous requests (no token, no cookies):
- `GET <pinned URL>` → **HTTP 302** to a **signed, expiring** CDN URL (`*.cdn.hf.co`, `Expires=…`). Response headers on the 302 include `x-repo-commit: 6e5c4f1e395deb959c494953478fa5cec4b8008f`, `x-linked-size: 2588147712`, `x-linked-etag: "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"` (= the SHA-256) and `accept-ranges: bytes`.
- The CDN honours **HTTP Range**: `Range: bytes=100-199` → **206** with `content-range: bytes 100-199/2588147712`. So resume works.
- No authentication is needed (the repo is not gated).
- Consequence: **always request the pinned `huggingface.co/.../resolve/<commit>/...` URL** and let the HTTP client follow the redirect. Never store or reuse the CDN URL (it expires); on every resume, start again from the pinned URL.

Constants (one place per platform, e.g. top of the downloader file):
```
MODEL_FILE       = "gemma-4-E2B-it.litertlm"
MODEL_URL        = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm"
MODEL_SIZE_BYTES = 2588147712
MODEL_SHA256     = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
VERIFY_SHA256    = true   // one streaming hash after download; set false if it proves too slow on the test phone
```

Download rules (both platforms):
1. **Where:** Android `context.filesDir/gemma-4-E2B-it.litertlm` (the litert-lm skill's **Path B**, "download at runtime into `filesDir`"; never `assets/`, never the APK). iOS `Application Support/gemma-4-E2B-it.litertlm`, with `isExcludedFromBackup = true`.
2. **Model valid ⇔** the final file exists and its length `== MODEL_SIZE_BYTES`. On launch, check only the size (instant) and go straight to the translator. The SHA-256 is computed **once**, right after a download, never on every launch.
3. **Free space before starting:** the file is written once (no import copy), so the need is `MODEL_SIZE_BYTES − bytesAlreadyDownloaded + 200 MB` margin, i.e. **≈ 2.8 GB free for a fresh download** (2,588,147,712 B ≈ 2.59 GB + 200 MB). If not available, show "Free up about X GB" and don't start.
4. **Cellular warning:** if the current network is metered/cellular (or "expensive" on iOS), show a dialog "This is a 2.6 GB download. Use mobile data?" with *Download anyway* / *Wait for Wi-Fi*. No warning on Wi-Fi.
5. **Progress:** determinate progress bar + "1.2 GB of 2.6 GB". Throttle UI updates (e.g. every 1 % or 500 ms).
6. **Resume:** an interrupted download (no network, app killed, cancel) keeps its partial data and resumes from where it stopped on the next *Download* tap (Android: `.part` file + `Range`; iOS: resume data, falling back to restart).
7. **Completion:** verify size (and SHA-256 if enabled), then **atomically** move the temp file to the final name, then load the engine. On size/hash mismatch delete the temp file and show "Download corrupted, try again".
8. **Retry / cancel:** a *Cancel* button during download (keeps partial data), a *Retry* button on error. No automatic retry loops beyond the client's own redirect handling.
9. **Errors to surface in plain words:** no connection, HTTP error (show the code; 403/404/410/429/5xx → "Model server unavailable, try later"), not enough space, corrupted file.
10. **No other network code anywhere.** All networking lives in a single downloader file per platform (§8).

**Backgrounding (decision: foreground-only download with resume).** A 2.6 GB download takes minutes. The simplest thing that works: the download runs while the app is open, the screen is kept awake during it (Android `FLAG_KEEP_SCREEN_ON` via `View.keepScreenOn`; iOS `UIApplication.shared.isIdleTimerDisabled = true`), and the UI says "Keep the app open until the download finishes". If the user leaves and the OS stops the app, the next launch shows "Download paused — 1.2 GB of 2.6 GB" with a *Resume* button. This is a one-time event, so we deliberately do **not** add a foreground service / WorkManager on Android (extra service class, notification, `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE_DATA_SYNC` permissions, worker state plumbing) or a background `URLSession` on iOS (app-delegate event handling, delegate-based session that must be re-created on relaunch). Revisit only if on-device testing shows the foreground approach is unusable (§11).

**Developer shortcut (Android only, optional):** to avoid re-downloading 2.6 GB while iterating, the README documents copying a locally downloaded file with `adb` (§4.4). The app needs no code for this: it simply finds a valid file in `filesDir` and skips the download screen. No file-picker import in v1.

## 3. Repository layout (keep it this small)

```
/
├── AGENTS.md
├── SPEC.md
├── README.md                  # build/run steps, first-run download, adb shortcut, requirements
├── .gitignore                 # must ignore *.litertlm, *.task
├── .github/workflows/
│   ├── android.yml            # CI: Android unit tests + assembleDebug
│   └── ios.yml                # CI: iOS build + tests on a macOS runner
├── android/                   # one Gradle project, ONE module
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/libs.versions.toml
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           └── java/dev/heikki/translator/
│               ├── MainActivity.kt        # setContent { TranslatorTheme { TranslatorScreen() } }
│               ├── TranslatorViewModel.kt # engine + download + state (the only state holder)
│               ├── ModelDownloader.kt     # the ONLY network code: resumable HTTP download + verify
│               ├── TranslatorScreen.kt    # all Compose UI (download state + translator)
│               ├── Languages.kt           # target language list
│               ├── Prompt.kt              # system instruction builder (pure function, unit-tested)
│               └── Theme.kt               # MaterialExpressiveTheme setup
└── ios/
    ├── Translator.xcodeproj
    └── TranslatorApp/
        ├── TranslatorApp.swift        # @main
        ├── TranslatorModel.swift      # @Observable: engine + download + state (the only state holder)
        ├── ModelDownloader.swift      # the ONLY network code: URLSession download + verify
        ├── ContentView.swift          # all SwiftUI UI (download state + translator)
        ├── Languages.swift
        └── Prompt.swift
```

`ModelDownloader` is a plain class/struct with one or two functions that report progress through a callback (or `Flow`/`AsyncStream`); it holds no UI state. The ViewModel / `@Observable` remains the only state holder. No model files live anywhere in the repo.

Application ID / bundle ID: `dev.heikki.translator` (change if the owner prefers another; see §11).

## 3.1 CI: two GitHub Actions workflows (build + tests only)

The repo has exactly two workflows, shipped alongside this spec in `.github/workflows/`. They only compile the app and run the tests. **No distribution, signing, release, deploy or artifact-upload steps** (out of scope for now).

- `android.yml`: on push to `main` and on pull requests touching `android/**`. Ubuntu runner, JDK 17, Gradle; runs `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` in `android/`. Only JVM unit tests run in CI; the instrumented UI tests (§9) need an emulator and are run locally.
- `ios.yml`: on push to `main` and on pull requests touching `ios/**`. `xcode-27` runner (macOS 27, Xcode 27 / iOS 27 SDK, Apple Silicon); picks an available iPhone simulator and runs `xcodebuild test -project Translator.xcodeproj -scheme Translator` with `CODE_SIGNING_ALLOWED=NO`. This also builds the app, so no separate build step is needed. The `Translator` scheme must be **shared** (committed under `xcshareddata/xcschemes`) and include a unit-test target (the `Prompt`/post-processing tests from §9), otherwise CI cannot run it.
- Rules for both: no secrets, no model download (the model must never be fetched in CI), tests must pass without the model file. If the `xcode-27` label is unavailable on the repo's runners, use the newest macOS image that ships Xcode 27 and note it in the PR. Keep the files short; no matrix builds, no caching tricks beyond the Gradle action.
- The Android grep checks from §8 may run as a unit test so they are enforced by CI.

Copilot runs on the owner's MacBook with Xcode, so it can build and test both apps locally with the same commands the workflows use; CI is the safety net on each PR. Copilot may also install and run the apps on the Android emulator and iOS Simulator (including instrumented/UI tests). It must never download the model itself (no `curl`, scripts, etc.), but letting the app perform its own first-run download on the emulator/simulator is allowed.

## 4. Android

Follow the official skill **[android/skills › device-ai/litert/litert-lm/SKILL.md](https://github.com/android/skills/blob/main/device-ai/litert/litert-lm/SKILL.md)** (and its `references/get-a-model.md`). Where this spec and the skill disagree on LiteRT-LM API usage, the skill + the [LiteRT-LM Android guide](https://ai.google.dev/edge/litert-lm/android) win; where they disagree on product scope (e.g. foreground-only download, single downloader file), this spec wins. Model delivery is the skill's **Path B** (runtime download into `filesDir`).

Other android/skills that may help (optional): `system/edge-to-edge`, `jetpack-compose/theming/styles`, `testing/testing-setup`, `security/android-permissions-security`. There is no Material 3 Expressive–specific skill in that repo at the time of writing.

### 4.1 SDK and dependencies (verified 2026-10-26)

- `minSdk = 26` (LiteRT-LM AAR requires ≥ 24; 26 keeps things simple), `compileSdk = 37` and `targetSdk = 37` (Android 17, stable since 2026-06-16); use an AGP version that supports API 37.
- Kotlin + Compose compiler plugin matching the Kotlin version. Note from the skill: `litertlm-android` 0.18.x carries Kotlin 2.4 metadata → use **Kotlin 2.4.x**. (Hilt incompatibility mentioned in the skill does not apply: no Hilt.)
- Gemma 4 E2B needs **no version bump**: the skill (written for 0.18.0) recommends this exact file, and the current upload works from `litertlm-android` 0.11.0 onwards. `ThinkingConfig`, `maxNumTokens` and `systemInstruction` used below exist in 0.18.1.
- Dependencies (Google Maven):

```kotlin
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1") // latest on Google Maven (skill documents 0.18.0)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta02")    // pinned above the BOM (1.4.0) for the Expressive APIs
    implementation("androidx.activity:activity-compose")                    // version from BOM / latest stable
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

If `1.5.0-beta02` causes problems, fall back to the BOM's `1.4.0` and keep only Expressive APIs that compile there. Do not add any other dependency without a reason in the PR.

### 4.2 Manifest

- `<uses-permission android:name="android.permission.INTERNET"/>`: needed only for the one-time download.
- `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>`: needed for the cellular/metered warning (`ConnectivityManager.isActiveNetworkMetered`). Both are normal (install-time) permissions; no runtime prompt.
- No other permissions: no `FOREGROUND_SERVICE*`, no `POST_NOTIFICATIONS`, no storage permissions (`filesDir` needs none).
- `android:usesCleartextTraffic="false"` (HTTPS only; it is the default on API 28+, stated explicitly for API 26–27).
- v1 runs on CPU, so the GPU `uses-native-library` entries are not needed. If the owner switches to GPU (§4.3), add exactly the two entries from the skill inside `<application>` (`libvndksupport.so`, `libOpenCL.so`, `android:required="false"`); without them the engine initializes but the first reply fails with `Can not find OpenCL library on this device`.
- `android:allowBackup="false"` (the 2.6 GB model must not go into Auto Backup); keep the manifest minimal.

### 4.3 Engine usage (align with the skill)

Use exactly the skill's pattern (Steps 4, 5, 7):
- `Engine(EngineConfig(modelPath = <absolute path>, backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = app.cacheDir.path))`, then `initialize()` **on a single-thread executor**, never on the main thread. Loading a 2.6 GB model can take several seconds: show the loading state.
- **Backend choice:** `Backend.CPU()` in v1 (the skill's default, needs nothing from the device). Keep the backend in one constant. GPU is supported for this file and on the card's S26 Ultra benchmark uses far less CPU memory (676 MB vs 1733 MB) with much faster prefill; measuring GPU vs CPU on the test phone is part of milestone A3, and switching is: constant → `Backend.GPU()` + the two manifest entries (§4.2). Do not write automatic backend fallback logic.
- One single-thread executor + `CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())` + `Mutex` owns the `Engine` and `Conversation`. Creation, streaming, `cancelProcess()` and `close()` all run on it.
- Store the engine only after `initialize()` returns. Close conversation before engine, each exactly once. Release in `onCleared` on the private scope (not `viewModelScope`).
- **Translation is single-shot, not a chat:** for each translation, close the previous `Conversation` (if any) and create a new one with `engine.createConversation(ConversationConfig(systemInstruction = Contents.of(buildSystemInstruction(target)), samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), thinkingConfig = ThinkingConfig(enableThinking = false)))`, then `sendMessageAsync(sourceText)` and append chunks (`toString()`) to state. This keeps history out of the prompt and saves memory. (`SamplerConfig` accepts `temperature = 0.0`; see §6 for sampling.)
- Load the engine once at app start if a valid model file exists (size check, §2.1), or right after a successful download; keep it loaded for the lifetime of the ViewModel.

State (single data class):

```kotlin
data class UiState(
    val modelStatus: ModelStatus = ModelStatus.Missing,
    // Missing(partialBytes), NeedsCellularConsent, Downloading(bytes, total), Verifying, Loading, Ready, Error(message)
    val input: String = "",
    val target: Language = Languages.default,
    val output: String = "",
    val translating: Boolean = false,
    val error: String? = null,
)
```

### 4.4 Downloading the model (`ModelDownloader.kt`)

Plain `java.net.HttpURLConnection` on `Dispatchers.IO`, launched from `viewModelScope` (so it is cancelled with the screen and resumes later). No OkHttp, Ktor, Retrofit, DownloadManager or WorkManager. Roughly 60–100 lines:

1. `val part = File(filesDir, "$MODEL_FILE.part")`; `val done = part.length()`.
2. Free space: `filesDir.usableSpace >= MODEL_SIZE_BYTES - done + 200 MB`, else error.
3. Metered check: `ConnectivityManager.isActiveNetworkMetered` (or no active network → "No connection") → `NeedsCellularConsent` unless the user already accepted in this session.
4. `HttpURLConnection` to `MODEL_URL` (HTTPS → HTTPS redirect is followed automatically; `instanceFollowRedirects = true`), `connectTimeout`/`readTimeout` ≈ 30 s, header `Range: bytes=$done-` if `done > 0`.
5. Response `206` → append to `part`; `200` → server ignored Range: truncate `part` and write from 0; anything else → error with the code. Write with a 1 MB buffer via `FileOutputStream(part, append)`, check `isActive` (cancellation) every chunk, report progress (throttled).
6. When `part.length() == MODEL_SIZE_BYTES`: if `VERIFY_SHA256`, stream the file through `MessageDigest.getInstance("SHA-256")` (state `Verifying`; tens of seconds on a phone) and compare with `MODEL_SHA256`; on mismatch delete `part`. If `part.length() > MODEL_SIZE_BYTES` → delete and error.
7. `part.renameTo(File(filesDir, MODEL_FILE))` (same directory → atomic) → ViewModel calls `load()`.
8. Keep the screen on while `Downloading`/`Verifying` (`LocalView.current.keepScreenOn = true` in a `DisposableEffect`).

**Developer shortcut (optional, README only, skill Path A):** copy a file downloaded on the computer instead of downloading on the phone:
```
adb push gemma-4-E2B-it.litertlm /data/local/tmp/model.litertlm
adb shell run-as dev.heikki.translator cp /data/local/tmp/model.litertlm files/gemma-4-E2B-it.litertlm
adb shell rm /data/local/tmp/model.litertlm   # free 2.6 GB
```
No app code is needed: a valid file in `filesDir` makes the app skip the download screen. The agent never downloads the model itself and CI never downloads it (§3.1); the app downloading it on an emulator is fine.

### 4.5 Errors and low memory

- `initialize()` failure → `ModelStatus.Error(message)` with a "Delete and download again" action (deletes the model file, back to the download screen); never close an engine whose `initialize` threw.
- Input limit: 1,000 characters (counter shown; button disabled above the limit). Keeps input + output well inside `maxNumTokens = 4096` and prefill short.
- Low-RAM warning: at startup read `ActivityManager.MemoryInfo.totalMem`; if below ~7.5 GB (i.e. not an 8 GB device), show a one-line non-blocking warning on the download screen (before spending 2.6 GB) and on the translator ("This model is recommended for phones with 8 GB RAM or more"). Do not block download or loading.
- Free-space and download errors: see §2.1 / §4.4.
- Cancel: a "Stop" action calls `conversation.cancelProcess()` on the executor.
- `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` / `onLowMemory` in `MainActivity` → if not translating, release the engine; reload lazily on next translate. Keep it to a few lines. This matters more with a 2.6 GB model (peak ~1.7 GB on CPU).
- Catch exceptions from `sendMessageAsync` and show them in a Snackbar; never crash.

### 4.6 UI — Jetpack Compose, Material 3 Expressive

Single screen with two states (download / translator), edge-to-edge (`enableEdgeToEdge()`), dynamic color on Android 12+.

- Theme: `MaterialExpressiveTheme(colorScheme = …, motionScheme = MotionScheme.expressive())` with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Top: `LargeFlexibleTopAppBar` (or `MediumFlexibleTopAppBar`) titled "Translate". If unavailable in the pinned version, use `LargeTopAppBar`.
- Input: `OutlinedTextField` (multi-line, character counter in `supportingText`, clear icon).
- Target language: a tonal button / `FilterChip` showing the current language that opens a `ModalBottomSheet` with a searchable list of languages (§6).
- Primary action: a prominent "Translate" button (large `Button` with expressive shape, or `ExtendedFloatingActionButton`), swapping to "Stop" while translating.
- While loading the model: `ContainedLoadingIndicator` / `LoadingIndicator` (Expressive). While translating: streamed text appears in an output `Card` (`ElevatedCard`) with a small `LoadingIndicator`.
- Output card actions: Copy (`ClipboardManager`/`LocalClipboard`) and Clear, e.g. in a `ButtonGroup` or simple `IconButton`s.
- **First-run download state** (shown whenever no valid model file exists; skipped entirely when it does):
  - Title + 2–3 lines: "To translate offline, this app needs to download a language model once (2.6 GB). After that it works without internet." Wi-Fi recommended; free space needed (~2.8 GB).
  - Primary `Button` "Download" (or "Resume (1.2 GB of 2.6 GB)" if a `.part` exists).
  - While downloading: `LinearWavyProgressIndicator` (Expressive; fall back to `LinearProgressIndicator`) with "x.x GB of 2.6 GB", a "Keep the app open" hint, and a "Cancel" `OutlinedButton`.
  - `Verifying` / `Loading`: `LoadingIndicator` with a label.
  - Errors: message + "Retry". Cellular consent: `AlertDialog` (§2.1 rule 4).
  - Then the translator state appears automatically.
- Verify every Expressive API name against the pinned Material3 version; if one doesn't exist, use the closest standard M3 component — do not invent APIs.

Accessibility: content descriptions on icon buttons; output region `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` (announce once on completion, not per token: set the live region text only when finished); support font scaling (no fixed heights for text); min 48dp touch targets; TalkBack labels for language picker items.

## 5. iOS

### 5.1 Inference stack (verified)

**LiteRT-LM ships an official Swift API ("Early Preview")** — Swift Package `LiteRTLM` from `https://github.com/google-ai-edge/LiteRT-LM.git` (git tag `v0.18.1`; in SPM use **Exact Version `0.18.1`**; binary `CLiteRTLM.xcframework`; package declares iOS 15+). Use it; it reads the **same `.litertlm` file** as Android. Docs: [ai.google.dev/edge/litert-lm/swift](https://ai.google.dev/edge/litert-lm/swift), sample: `samples/ios_and_mac` in the LiteRT-LM repo. **The official sample loads exactly `gemma-4-E2B-it.litertlm`** (it bundles it; this app downloads it instead, §5.5), and the model card publishes iPhone 17 Pro benchmarks for it, so this model + Swift API combination is officially supported. No version bump needed.

Caveats (be honest in the README):
- The Swift API is labelled **Early Preview**; APIs may change between versions → pin the exact version `0.18.1` in SPM.
- There is **no MediaPipe fallback** for this model: the repo has no iOS `.task` file for Gemma 4 E2B (only `-web.task`), and the card says the MediaPipe route is in maintenance mode. If LiteRT-LM Swift fails on device, stop and report (milestone I2).
- **Memory on iOS:** the card measures 607 MB (CPU) / 1450 MB (GPU) physical footprint on iPhone 17 Pro. No official minimum device is documented; test on the oldest iPhone the owner wants to support. If the system kills the app during load, the first remedy is adding Apple's `com.apple.developer.kernel.increased-memory-limit` entitlement; record it in the PR.
- If SPM resolution has Git LFS issues, follow the sample README (`GIT_LFS_SKIP_SMUDGE=1 xcodebuild -resolvePackageDependencies …`).
- Test on a **physical device**; the simulator is fine for UI but not for performance.

### 5.2 API usage (from `swift/*.swift` in the repo)

```swift
import LiteRTLM

let config = try EngineConfig(modelPath: path, backend: .cpu(), maxNumTokens: 4096, cacheDir: cachesDir.path)
let engine = Engine(engineConfig: config)      // Engine is an actor
try await engine.initialize()

// per translation: fresh conversation
let conv = try await engine.createConversation(with: ConversationConfig(
    systemMessage: Message(buildSystemInstruction(target), role: .system),
    samplerConfig: try SamplerConfig(topK: 1, topP: 1.0, temperature: 0.0),
    thinkingConfig: ThinkingConfig(enableThinking: false)))
for try await chunk in conv.sendMessageStream(Message(sourceText)) {
    for content in chunk.contents { if case .text(let t) = content { output += t } }
}
// stop: try conv.cancel()
```

Hold the engine in the single `@Observable @MainActor final class TranslatorModel`. Run inference in a `Task`; keep a reference to cancel it and call `conversation.cancel()`.

Backend: `.cpu()` in v1 on iOS too. On the card's iPhone 17 Pro benchmark CPU uses less memory (607 MB vs 1450 MB) at 25 tok/s, enough for translation; `.gpu` (Metal) roughly doubles decode speed and is what the official sample uses. Keep it in one constant; compare both on device in I2 and record the numbers.

### 5.3 Targets and settings

- **Minimum iOS 27.0** (latest iOS; includes the Liquid Glass APIs and keeps the code free of availability checks). Xcode 27+. Swift 6 language mode is fine; keep concurrency simple.
- No `NSAppTransportSecurity` exceptions (HTTPS only). `URLSession` is used **only** in `ModelDownloader.swift`; no `Network` framework, no other networking. The only SPM dependency is `LiteRTLM`.
- The model is **not** bundled in the app (no resource folder, no Copy Bundle Resources entry); the app is small and the model is downloaded on first use (§2.1, §5.5).
- Handle `didReceiveMemoryWarningNotification`: release engine if idle.
- Downloaded model in Application Support (create the directory if needed): set `isExcludedFromBackup = true` on the final file (2.6 GB must not go to iCloud backup).

### 5.4 UI — SwiftUI, Liquid Glass

Same single screen, two states and behaviours as Android:
- `NavigationStack` with large title "Translate".
- Input `TextEditor` in a rounded container; output text in a container with `.glassEffect(.regular, in: .rect(cornerRadius: 24))`.
- Bottom controls grouped in a `GlassEffectContainer`: target-language button (`.buttonStyle(.glass)`) opening a `.sheet` with a searchable `List` (`.searchable`), and Translate/Stop button (`.buttonStyle(.glassProminent)`).
- Use `.glassEffect(.regular.interactive())` only on interactive elements; don't stack glass on glass.
- Loading: `ProgressView`. Copy via `UIPasteboard.general.string`.
- First-run download state: same copy as Android (§4.6) in a glass card; "Download" / "Resume" (`.buttonStyle(.glassProminent)`), `ProgressView(value:)` with "x.x GB of 2.6 GB", "Keep the app open" hint, "Cancel"; errors with "Retry"; cellular consent via `.confirmationDialog` or `.alert`. Skipped when a valid model exists.
- Accessibility: Dynamic Type, VoiceOver labels, `accessibilityAddTraits(.updatesFrequently)` not needed — announce completion with `AccessibilityNotification.Announcement`; respect Reduce Transparency (glass falls back automatically; verify readability).

### 5.5 Downloading the model (`ModelDownloader.swift`)

Foreground `URLSession` (default configuration, delegate for progress) — no background session (§2.1). Roughly 60–100 lines:

1. Free space: `URLResourceValues.volumeAvailableCapacityForImportantUsage` on Application Support ≥ `MODEL_SIZE_BYTES + 200 MB` (≈ 2.8 GB), else error.
2. Cellular: first attempt with `configuration.allowsExpensiveNetworkAccess = false`. If it fails with `URLError.networkUnavailableReason == .expensive`, show the 2.6 GB cellular dialog; on *Download anyway* retry with `allowsExpensiveNetworkAccess = true`. (No `Network` framework needed.)
3. `session.downloadTask(with: MODEL_URL)` (URLSession follows the 302 to the CDN), or `downloadTask(withResumeData:)` if resume data is saved. Progress from `urlSession(_:downloadTask:didWriteData:…)`, throttled, published via the `@Observable` model.
4. Cancel → `task.cancel(byProducingResumeData:)`; on failure read `NSURLSessionDownloadTaskResumeData` from the error's `userInfo`. Save resume data to `Caches/model.resume` so it survives relaunch. If resuming fails (e.g. the signed CDN URL expired), delete the resume data and restart from the pinned URL (accept the lost bytes; simplicity over cleverness).
5. In `didFinishDownloadingTo` (the temp file is deleted when the callback returns): check size `== MODEL_SIZE_BYTES`, then **move** (not copy) it to `Application Support/gemma-4-E2B-it.litertlm.part`; if `VERIFY_SHA256`, hash it with CryptoKit `SHA256` streaming in 1 MB chunks off the main actor (state `Verifying`); then `FileManager.replaceItemAt`/`moveItem` to the final name (atomic, same volume), set `isExcludedFromBackup = true`, then load the engine.
6. `UIApplication.shared.isIdleTimerDisabled = true` while downloading/verifying, back to `false` afterwards.

## 6. Shared prompt design

Gemma 4 has **native `system` role support** (model card). The `.litertlm` file embeds the chat template (`<|turn>system … <|turn>user … <|turn>model`) and LiteRT-LM applies it, so pass the instruction as `systemInstruction` (Android) / `systemMessage` (iOS) and never hand-build control tokens. Keep the instruction short and direct.

**Thinking mode:** Gemma 4 can reason before answering (`<|think|>` token in the system turn). In the litert-community file it is **off by default** (confirmed by a Google engineer in the repo discussions), and for E2B no empty thought block is emitted when off (base model card). Translation must not think: set `ThinkingConfig(enableThinking = false)` explicitly on both platforms (cheap insurance) and never pass `enable_thinking` via `extraContext`.

`buildSystemInstruction(target: Language): String` (identical text on both platforms, pure function, unit-tested):

```
You are a translation engine. Detect the language of the user's text and translate it into {TARGET_NAME_IN_ENGLISH} ({TARGET_NATIVE_NAME}).
Rules:
- Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
- Keep the meaning, tone, line breaks, numbers, names, URLs and emojis.
- If the text is already in {TARGET_NAME_IN_ENGLISH}, return it unchanged.
```

User message = the raw input text (trimmed), nothing else.

Post-processing (tiny, unit-tested): trim whitespace; strip one leading label like `Translation:` / `{Target}:` if the model adds it; strip matching wrapping quotes. Do not attempt anything smarter.

Sampling: greedy (`topK = 1`, `topP = 1.0`, `temperature = 0`) for faithful, repeatable translations. Note: the Gemma 4 model card recommends `temperature = 1.0, topP = 0.95, topK = 64` as a general default (AI Edge Gallery uses the same); if greedy output shows repetition loops on device, switch to those values and record it in the PR. Capping output at ~2× input length is **not** required; rely on EOS + Stop button (the KV cache is capped at 4096 tokens anyway).

Source language: not selected and not displayed in v1 (auto-detected implicitly by the model).

## 7. Target languages

Default target: **English** if the device locale is not English, else **Spanish**.

| Code | English name | Native name |
|---|---|---|
| en | English | English |
| es | Spanish | Español |
| fr | French | Français |
| de | German | Deutsch |
| it | Italian | Italiano |
| pt | Portuguese | Português |
| nl | Dutch | Nederlands |
| ca | Catalan | Català |
| pl | Polish | Polski |
| ru | Russian | Русский |
| uk | Ukrainian | Українська |
| tr | Turkish | Türkçe |
| ar | Arabic | العربية |
| hi | Hindi | हिन्दी |
| zh | Chinese (Simplified) | 简体中文 |
| ja | Japanese | 日本語 |
| ko | Korean | 한국어 |

Gemma 4 has out-of-the-box support for 35+ languages (pre-trained on 140+), so no "best quality" note is needed. Keep the list in one file per platform; persist the last choice (`SharedPreferences` / `@AppStorage`) — this is the only persisted setting.

## 8. Network scope and offline verification

Android:
- Merged manifest requests exactly `INTERNET` and `ACCESS_NETWORK_STATE` and no other network/foreground-service permission (check with `aapt2 dump permissions app-debug.apk` or `apkanalyzer manifest permissions`).
- Grep check (CI or a unit test reading the sources): `HttpURLConnection`, `java.net.URL`, `URLConnection`, `Socket` appear **only** in `ModelDownloader.kt`; `okhttp`, `ktor`, `retrofit`, `volley`, `DownloadManager`, `WorkManager`, `firebase`, `analytics` appear nowhere in `android/`. The only URL constant is `MODEL_URL` (grep `https://` in `android/app/src/main` → only that line).

iOS:
- Grep check: `URLSession` appears **only** in `ModelDownloader.swift`; `NWConnection`, `import Network` nowhere; the only `https://` string in `ios/TranslatorApp` is `MODEL_URL`.

Both:
- After the model is installed, the app makes no network request: with the model present, launch in **Airplane Mode** and run the full translate flow (acceptance criterion 3).
- Optional: watch traffic once (Android Studio Network Inspector / a proxy) on a launch with the model present → zero requests.
- README states: internet is used once to download the model from Hugging Face; everything else is offline; no data leaves the device.

**Privacy:** text typed by the user is never sent anywhere and is not persisted (only the last target language is). The single download request sends nothing but a standard HTTP GET to Hugging Face (which, like any server, sees the IP address and user agent); no identifiers, no tokens. No analytics or crash reporting SDKs. The download screen should mention "Downloaded from Hugging Face" so the user knows where the request goes.

## 9. Testing and acceptance criteria

Unit tests (fast, no model needed):
- `buildSystemInstruction` contains the target names and the "Output ONLY" rule.
- Post-processing strips labels/quotes and leaves normal text untouched.
- Language list: unique codes, default selection logic.

Instrumented/UI (Android, no model): app launches and shows the download state with a "Download" button; with a dummy file of the wrong size in `filesDir` it still shows the download state (size check); requested permissions are exactly `INTERNET` + `ACCESS_NETWORK_STATE`.

Unit test (JVM, no network): the `Range`/response-code decision (`206` append, `200` restart, other → error) and the size validation, if they are written as small pure functions.

Manual on device with the model (document results in the PR):
- First-run download on Wi-Fi completes: progress shown, size (+ SHA-256) verified, translator appears; time recorded.
- Interrupted download resumes: toggle airplane mode mid-download → clear error → Retry continues from the same byte count (not 0). Kill the app mid-download → relaunch shows "Resume (x GB of 2.6 GB)" and continues (Android `.part`; iOS resume data or documented restart).
- Cellular warning appears on mobile data and not on Wi-Fi; low free space blocks the download with a clear message.
- Relaunch with the model present skips the download screen instantly (no hash on launch).
- Load time and peak memory recorded for CPU (and GPU if tried) on the test phone (indicative, not a hard gate).
- Answers contain no thinking text and no chat-template tokens.
- "Hola, ¿cómo estás?" → English produces a short English translation with no extra text.
- "The weather is nice today." → Spanish, French, German.
- Stop during streaming works; rotating the device (Android) does not reload the model or crash.
- **Airplane mode (model installed): launch + translate + stop all work.**

Acceptance criteria:
1. Both apps build from a clean checkout (`./gradlew assembleDebug`; `xcodebuild -scheme Translator -destination 'generic/platform=iOS Simulator' build`) with no model file in the repo or the app bundle.
2. First launch shows the download screen; the model downloads from the pinned Hugging Face URL with progress, free-space check, cellular warning, cancel/retry and resume, and is verified (size `2588147712`, SHA-256 if enabled) before use. A valid model on later launches skips straight to the translator.
3. **Offline forever after:** with the model installed, the full flow (launch, translate, stop) works in **airplane mode** on both platforms, and the app makes no network requests.
4. Network code is limited to the single downloader file per platform; no other network calls, analytics or SDKs (§8). Android permissions are exactly `INTERNET` + `ACCESS_NETWORK_STATE`.
5. Translation streams on both platforms with `gemma-4-E2B-it.litertlm` (2,588,147,712 bytes), thinking disabled.
6. UI follows §4.6 / §5.4 and the "Simplicity first" rules (§0): one module/target, one state holder, one downloader file, no DI, no navigation library.
7. README explains: first use needs internet once for a 2.6 GB download (Wi-Fi recommended, ~2.8 GB free space), then the app is fully offline; source, licence (Apache-2.0, not gated) and SHA-256 of the model; 8 GB RAM recommendation; build steps; the optional `adb` developer shortcut (Android).

## 10. Milestones (do them in order; one PR each is fine, or one PR with one commit per milestone)

- **A1 — Android skeleton:** single-module Compose app, theme, manifest with `INTERNET` + `ACCESS_NETWORK_STATE` only, `.gitignore` (`*.litertlm`, `*.part`, `*.task`), builds.
- **A2 — Download + engine:** `ModelDownloader.kt` (pinned URL, Range resume, progress, free-space + metered checks, size/SHA-256 verify, atomic rename, cancel/retry); first-run download state; `TranslatorViewModel` per skill (executor, Mutex, load/release) loading from `filesDir`; low-RAM warning.
- **A3 — Translate:** prompt + single-shot conversation (thinking off) + streaming + stop + errors. Measure CPU vs GPU on the test phone and record it.
- **A4 — UI polish:** Material 3 Expressive components, language bottom sheet, copy/clear, accessibility.
- **A5 — Tests & network checks** (§8, §9), including the airplane-mode run. Add `.github/workflows/android.yml` (§3.1) and make sure it is green.
- **I1 — iOS skeleton:** SwiftUI app (iOS 27), SPM `LiteRTLM` 0.18.1, builds; no bundled model.
- **I2 — Download + engine + translate:** `ModelDownloader.swift` (URLSession download, resume data, progress, free-space + expensive-network handling, verify, move to Application Support, exclude from backup), `TranslatorModel`, streaming, cancel, thinking off. Verify on a physical device (record CPU vs GPU, memory); if LiteRT-LM Swift fails with this model, stop and report (no fallback stack).
- **I3 — Liquid Glass UI + accessibility + tests** (incl. airplane-mode run). Add a shared `Translator` scheme with a unit-test target and `.github/workflows/ios.yml` (§3.1), and make sure it is green.
- **D1 — README** (first-run download, offline-after, model source/licence/SHA-256, build steps, RAM/storage requirements, `adb` shortcut, known limits).

## 11. Open questions and risks

- **Size and RAM:** 2.6 GB on disk, ~1.7 GB peak on Android CPU (0.7 GB on GPU), 8 GB RAM recommended (Gallery's threshold). Mid/low-end phones may be slow or get killed. If that's unacceptable, the owner may pick a verified smaller fallback (§2): only a constant + size change, but those are Gemma 3 (gated, weaker translation).
- **Hugging Face availability / link stability:** the app depends on Hugging Face for the first run. The pinned `resolve/<commit>/` URL survives re-uploads of the file, but not deletion of the repo/commit, a repo becoming gated, or HF URL changes; then first-run downloads fail (already-installed apps keep working). Mitigation: clear "server unavailable" error; if it happens, ship an update with a new URL (e.g. a mirror the owner controls — GitHub Releases can't hold a 2.6 GB single file, so it would need object storage). The redirect target is a signed, expiring CDN URL: never cache it (§2.1).
- **Rate limits:** anonymous HF downloads are rate-limited per IP (exact limits not published for this case); HTTP 429 → "Too many requests, try again later" + Retry. No automatic retry loops.
- **Storage:** ~2.8 GB free needed for the download, 2.6 GB kept permanently. Users with low space are blocked with a clear message; the README and the download screen state the number.
- **Interrupted downloads:** handled by resume (Android `Range` on `.part`; iOS resume data, which can become invalid → restart from 0). Foreground-only means the user must keep the app open; if testing shows that is unacceptable, the next step is an Android foreground service (`dataSync`) / iOS background `URLSession` (more code; owner's decision).
- **Data cost:** 2.6 GB on mobile data can be expensive → explicit cellular confirmation.
- **Upstream file changes:** the HF file was re-uploaded before (2026-05-04). The pinned commit keeps the app on that exact file; adopting a new upload means updating URL + size + SHA-256 together.
- **Thinking mode** must stay off; `ThinkingConfig(enableThinking = false)` keeps it off even if a template change ever enables it by default.
- **iOS LiteRT-LM Swift is Early Preview**; API churn risk. Pin versions.
- **GPU** is supported for this file but v1 uses CPU for simplicity; one community report in the repo mentions GPU precision loss on an S23 Ultra, so check output quality if switching.
- **Licence:** Apache-2.0, not gated; downloading it on the user's device needs no token. The README and an "About" line credit Gemma (Google) and link the licence. The model is never committed to this repo (size).
- **Distribution:** with the first-run download, store builds are small and need nothing special. Play for On-Device AI packs (skill Path C) and Apple Background Assets / bundling are **out of scope** (possible later if a fully offline install is ever required). Store listings must disclose the 2.6 GB first-run download. (Store data-safety forms: the app collects no data.)
- **Material3 1.5.0-beta02** is a beta; Expressive APIs are `@ExperimentalMaterial3ExpressiveApi` and may change.
- Application/bundle ID `dev.heikki.translator` is a placeholder.

## 12. References

- Android skill: https://github.com/android/skills/blob/main/device-ai/litert/litert-lm/SKILL.md
- LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM (release v0.18.1, 2026-10-20) · https://ai.google.dev/edge/litert-lm
- Kotlin guide: https://ai.google.dev/edge/litert-lm/android · Swift guide: https://ai.google.dev/edge/litert-lm/swift
- Model: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm (download pinned to commit `6e5c4f1e395deb959c494953478fa5cec4b8008f`) · base card: https://huggingface.co/google/gemma-4-E2B-it · LiteRT-LM Gemma 4 page: https://ai.google.dev/edge/litert-lm/models/gemma-4
- Reference app: https://github.com/google-ai-edge/gallery
