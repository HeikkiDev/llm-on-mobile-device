# SPEC-WALTER — Voice conversation translator for Walter (Android + iOS)

> Branch: `walter`. Source of truth for this variant. Written for a coding agent (GitHub Copilot).
> **This is a delta over [`SPEC.md`](SPEC.md).** Everything in `SPEC.md` still applies (model, one-time download, engine lifecycle, CPU backend, offline-forever, simplicity rules, CI) **unless this document overrides it**. When the two disagree, this document wins on the `walter` branch.
> Last verified against upstream sources: 2026-10-28 (LiteRT-LM Kotlin/Swift multimodal API, Gemma 4 E2B audio capabilities).

## 0. Who it is for and what "done" feels like

**Walter** is a Spanish-only speaker who works on construction sites in Spain. His coworkers often come from **Pakistan** (Urdu, Punjabi, Pashto, Sindhi…) and other countries, and don't speak Spanish. Walter uses an **Android** phone (iOS is built too, for the owner's testing).

The app lets Walter and **one** coworker hold a fluid, spoken back-and-forth conversation, face to face, with the phone in Walter's hand:

1. Walter taps **"Hablo yo"**, speaks Spanish, the phone **says it aloud** in the coworker's language.
2. Walter taps **"Habla él"** (or hands the phone over), the coworker speaks his language, the phone **says it aloud in Spanish**.
3. Repeat. Typing remains available as a secondary option (the current translator's behaviour).

Success = Walter can use it **without reading instructions, with gloves, in a noisy site, without internet**, and never has to pick the language again once it's set.

Design principles (in order): **big and obvious → few taps → fast feedback → forgiving**. Every screen must be usable by someone who doesn't read well: icons + short Spanish words + sound + haptics.

## 1. Scope

### In scope (new compared to SPEC.md)
- **Voice input** for both sides, recognized **on-device by Gemma 4 E2B's audio encoder** (same `.litertlm` file; no new model, no new download).
- **Spoken output** of each translation using the platform's **offline** system voices (Android `TextToSpeech`, iOS `AVSpeechSynthesizer`).
- **Automatic language detection** of the coworker's language from one spoken sentence, plus manual **search in a list**.
- **Remember the coworker's language** across launches and auto-select it (plus a short "recent languages" list).
- **Conversation view**: the current session's turns as bubbles (in memory only).
- **Spanish-only UI** (strings in Spanish; no localization machinery).
- **Separate app identity** so it installs next to the original translator.
- A **larger language list** (§6).

### Still out of scope
- Cloud anything (speech, translation, voices). The network rule of SPEC.md §1 is unchanged: the **only** network access is the one-time model download.
- Persisted conversation history, accounts, sharing, settings screen.
- More than two people per conversation; group mode; continuous "always listening" mode; wake words.
- Split-screen / face-to-face rotated layout (decided: single orientation, Walter holds the phone).
- Image/camera/OCR; vision backend stays off.
- Custom TTS engines or downloaded voice packs bundled in the app.
- Changing Walter's own language (fixed: Spanish).

## 2. App identity (override of SPEC.md §3)

| | Original app (`main`) | Walter app (`walter` branch) |
|---|---|---|
| Android `applicationId` | `dev.heikki.translator` | **`dev.heikki.walter`** |
| Android code `namespace` / package | `dev.heikki.translator` | unchanged (`dev.heikki.translator`) — avoids renaming every file; only `applicationId` changes |
| iOS bundle ID | `dev.heikki.translator` | **`dev.heikki.walter`** |
| App name (launcher / home screen) | "Translate" | **"Traductor Walter"** |
| Xcode target / scheme | `Translator` | unchanged (`Translator`) |

Consequences:
- Both apps can be installed side by side; each downloads its own copy of the model (2.6 GB each). Walter will only have this one.
- Launch command on the emulator becomes `$ADB shell am start -n dev.heikki.walter/dev.heikki.translator.MainActivity`; `run-as dev.heikki.walter`. **AGENTS.md must be updated in milestone W0** (app id, permissions, pointer to this spec).
- This branch is long-lived and is **not merged back into `main`**. Fixes useful to both apps are cherry-picked.

## 3. Model usage (extends SPEC.md §2, §4.3, §5.2)

Same file, URL, size, SHA-256, download code. What changes is **how the engine is configured and prompted**.

### 3.1 Verified capabilities
From the Gemma 4 model card ([google/gemma-4-E2B-it](https://huggingface.co/google/gemma-4-E2B-it)) and the LiteRT-LM docs:
- E2B has a ~300M-param **audio encoder**; tasks: **ASR** (speech recognition) and **AST** (speech → translated text) "across multiple languages". Output is text only (no audio out → we use system TTS).
- **Max audio length: 30 s** per input. Input: **16 kHz, mono**; ~25 tokens per second of audio (30 s ≈ 750 tokens, fits in `maxNumTokens = 4096`).
- Prompt order: **text first, audio after** the text in the same user message.
- The litert-community card: "the Vision and Audio models are loaded on demand to further reduce memory consumption".
- **No per-language audio accuracy table is published.** Urdu/Punjabi/Pashto/Sindhi speech quality is **unverified** → milestone W5 is a real-world evaluation with the owner (§10).

### 3.2 Engine config (override of SPEC.md §1 non-goal "never set audioBackend")
- Android: `EngineConfig(modelPath, backend = Backend.CPU(), audioBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = …)`. `visionBackend` stays unset.
- iOS: `EngineConfig(modelPath:, backend: .cpu(), audioBackend: .cpu(), maxNumTokens: 4096, cacheDir:)`.
- Audio content: Android `Content.AudioBytes(wavBytes)` (or `Content.AudioFile(path)`); iOS `Content.audioFile(path)` (the Swift docs only show the file variant). Write each recording as a **16 kHz, mono, 16-bit PCM WAV** to the cache dir, pass it, **delete it right after inference**. Milestone W1 must confirm the runtime accepts this WAV format on both platforms (the litert-community card's NPU example uses "a short 16 kHz mono WAV"); if not, adapt the encoding to what the runtime documents — don't guess.
- Keep **single-shot conversations** (fresh `Conversation` per turn, no history in the prompt), thinking off, greedy sampling — as in SPEC.md.
- **Backend:** still CPU by default. Conversational latency now matters more than in v1, so W1 measures **CPU vs GPU** (and MTP speculative decoding, GPU-only) for an audio turn on Walter's phone and the owner picks one constant. Target: **< 4 s from end of recording (auto-stop or tap) to start of spoken output** for a 5-second sentence; the silence wait (§4.1) comes on top, and tapping to stop skips it. Not a hard gate.
- Memory: the audio encoder adds memory while loaded. Re-measure peak memory in W1 and keep the low-RAM warning from SPEC.md §4.5.

### 3.3 Prompts (all in `Prompt.kt` / `Prompt.swift`, pure functions, unit-tested)

All prompts reference languages by their **English name** (what the model understands best), e.g. `Urdu`, `Punjabi (Shahmukhi script, as used in Pakistan)`.

**A. Voice turn, known languages (both directions)** — uses the card's official AST template so the model gets a format it was trained on. User message = this text, then the audio:
```
Transcribe the following speech segment in {SOURCE}, then translate it into {TARGET}.
When formatting the answer, first output the transcription in {SOURCE}, then one newline, then output the string '{TARGET}: ', then the translation in {TARGET}.
```
- Walter's turn: `SOURCE = Spanish`, `TARGET = <coworker language>`.
- Coworker's turn: `SOURCE = <coworker language>`, `TARGET = Spanish`.
- Parser `parseSpeechTranslation(raw, target) -> (transcript, translation)`: split at the first newline; strip the `"{TARGET}: "` label (case-insensitive, also accepts the native/Spanish name); reuse `cleanTranslation` from SPEC.md §6. If there is no newline/label, treat the whole output as the translation and the transcript as empty. Unit-test all of these shapes.
- System instruction (short): `You are a speech translation engine. Follow the requested output format exactly. Never add explanations.`

**B. Language detection** (one audio clip, coworker side). User message = this text, then the audio:
```
Identify the language spoken in this audio.
Answer with ONLY one code from this list, nothing else: {CODES}.
If the language is not in the list, answer: unknown
```
`{CODES}` = all codes from §6 (e.g. `ur, pa-Arab, ps, sd, hi, …`). Parser `parseDetectedLanguage(raw) -> Language?`: trim, lowercase, strip punctuation/quotes, take the first token, look it up (also accept English names as a fallback). `unknown` / anything else → `null`. Unit-tested.

**C. Typed text (secondary mode)** — bidirectional with one prompt:
```
You are a translation engine. If the user's text is in Spanish, translate it into {TARGET}. Otherwise, translate it into Spanish.
Rules:
- Output ONLY the translation. No explanations, notes, quotes, labels, or the original text.
- Keep the meaning, tone, line breaks, numbers, names and emojis.
```
The UI can't know the direction in advance; it shows the result in a bubble on the side of whoever's language it ended up in (if output script/language looks Spanish → coworker typed; simple heuristic: the input contains the coworker's script or the model output is Spanish. If ambiguous, put it on Walter's side). Keep this heuristic tiny; it's cosmetic.

### 3.4 Output limits
- Recording hard cap: **30 s** (model limit). At 25 s show "Termina la frase…", at 30 s stop automatically and process.
- Typed input limit stays 1,000 characters.

## 4. Voice I/O (new)

### 4.1 Recording ("tap to talk, auto-stop on silence")
- **Tap** a mic button → haptic + short visual "Escuchando…" + live level meter → speak → recording **stops automatically after 2.5 s of silence** (constant, tunable between 2 and 3 s) following detected speech, so natural pauses mid-sentence don't end the turn, or when the user **taps again**, or at 30 s.
- If no speech is detected within 8 s → cancel silently with "No he oído nada" and no inference.
- Silence detection: a tiny energy-based VAD in the recorder (RMS per 30 ms frame vs an adaptive noise floor measured in the first ~300 ms; "speech" when RMS > floor × factor). No ML VAD, no extra library. Construction sites are noisy: the factor and timeouts are constants, tuned in W5; **tap-to-stop always works** regardless of VAD.
- Trim leading/trailing silence before sending (fewer audio tokens = faster).
- Android: `AudioRecord` (`MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz, mono, `ENCODING_PCM_16BIT`) on a background thread; write the 44-byte WAV header + PCM. Runtime permission `RECORD_AUDIO` requested on the **first mic tap** (never at launch), with a Spanish rationale; if permanently denied, show a button to open app settings.
- iOS: `AVAudioEngine` input tap → `AVAudioConverter` to 16 kHz mono Int16 → WAV via `AVAudioFile`. `AVAudioSession` category `.playAndRecord`, options `[.defaultToSpeaker, .allowBluetoothHFP]`, mode `.default`. `NSMicrophoneUsageDescription` in Spanish ("Para escuchar lo que dices y traducirlo. El audio nunca sale del teléfono.").
- Recording and speaking are mutually exclusive: **tapping a mic while the phone is speaking stops the speech immediately** and starts recording (barge-in), so TTS is never recorded.
- Audio is **never persisted**: the WAV lives in the cache dir only until inference finishes (deleted in `finally`).

### 4.2 Speaking ("auto speak-aloud")
- After each voice turn's translation completes, speak it automatically: coworker's language after Walter's turn, Spanish after the coworker's turn. Each bubble has a **🔊 Repetir** button.
- Speech rate slightly slower than default (≈ 0.9×) for the coworker's language; normal for Spanish. Loudspeaker output (media stream).
- **Offline only.** Android: pick a `Voice` for the locale with `!isNetworkConnectionRequired` and without `KEY_FEATURE_NOT_INSTALLED`, and pass `KEY_FEATURE_NETWORK_SYNTHESIS = "false"`; if none exists, treat the language as "no voice". iOS: `AVSpeechSynthesisVoice(language:)`; `nil` → "no voice".
- Android 11+ package visibility: add `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>` to the manifest (required to bind the system TTS engine).
- **No voice for the language** (expected for Punjabi, Pashto, Sindhi and others, §6): no error dialog. The bubble shows the translation **extra large** with a "Enséñale la pantalla 👀" hint and a **full-screen "Mostrar"** button (big text, white on black, max font size that fits) for the coworker to read. For Urdu-script languages, a `Language.fallbackVoice` may be set (e.g. Punjabi-Pakistan → no fallback by default; owner decides in W5 whether Urdu voice is understandable enough).
- Voice availability is checked **on device at runtime** (Google publishes no fixed list of offline voices; Apple neither). Show a small 🔇 icon next to languages without a voice in the picker.
- Missing Spanish voice (unlikely): on Android, offer one button "Instalar voz española" → `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` (system settings; the download is done by the system, not by this app). Same button available in the picker for the selected coworker language. The README tells the owner to install Spanish + Urdu voices on Walter's phone once, on Wi-Fi.
- Haptics: light tick on record start, double tick on stop. Android `View.performHapticFeedback` (no `VIBRATE` permission); iOS `.sensoryFeedback`.
- If media volume < 40 %, show a dismissible chip "Sube el volumen 🔊" (Android `AudioManager.getStreamVolume`; iOS `AVAudioSession.outputVolume`).

## 5. UX (overrides SPEC.md §4.6 / §5.4 layout; theming rules stay: Material 3 Expressive / Liquid Glass)

### 5.1 States of the single screen
1. **Download** (unchanged from SPEC.md, Spanish copy).
2. **Choose language** — shown only when no coworker language has ever been chosen:
   - Title: "¿Qué idioma habla tu compañero?"
   - Big primary button: **"🎤 Que hable él"** → detection flow (§5.3).
   - Secondary: **"🔍 Buscar en la lista"** → language picker.
3. **Conversation** — the main screen (everything below). App opens directly here on every later launch with the remembered language.

### 5.2 Conversation screen (portrait, phone in Walter's hand)
```
┌─────────────────────────────────────┐
│  Compañero: [ اردو  Urdu  ▾ ]  [🎤?] │  ← language chip (opens picker) + "Detectar"
├─────────────────────────────────────┤
│                                     │
│ ┌───────────────────────┐           │  coworker bubble (left, tonal):
│ │ ¿Dónde pongo el       │ 🔊         │   Spanish translation LARGE
│ │ cemento?              │           │   original transcript small below
│ │ سیمنٹ کہاں رکھوں؟        │           │
│ └───────────────────────┘           │
│           ┌───────────────────────┐ │  Walter bubble (right, primary):
│        🔊 │ سیمنٹ وہاں دیوار کے     │ │   translation LARGE (for coworker)
│           │ پاس رکھو               │ │   what Walter said small below
│           │ Ponlo junto al muro   │ │
│           └───────────────────────┘ │
│                                     │
├─────────────────────────────────────┤
│ ┌───────────────┐ ┌───────────────┐ │
│ │      🎤        │ │      🎤        │ │  two huge buttons (≥ 120 dp tall,
│ │   HABLO YO    │ │   HABLA ÉL    │ │   ≈ half width each), distinct colors
│ │               │ │   بولیں        │ │   "Habla él" also shows the coworker-
│ └───────────────┘ └───────────────┘ │   language word for "speak"
│   ⌨ Escribir            🗑 Nueva     │  small secondary actions
└─────────────────────────────────────┘
```
- **Turn states shown on the active button** (and announced with sound/haptic): idle → *Escuchando…* (pulsing, level meter, "toca para terminar") → *Traduciendo…* (loading indicator; transcript streams into the new bubble) → *Hablando…* (tap to stop) → idle.
- **Turn hint:** after Walter's translation is spoken, the **"Habla él" button gently pulses** (and vice versa). No automatic listening (noise would trigger it); one tap per turn.
- Only one turn at a time: while translating, both mic buttons are disabled except the active one's **Stop**.
- The list auto-scrolls to the newest bubble. Newest bubble's translation uses the largest text style.
- **Writing mode** (`⌨ Escribir`): a bottom sheet with a text field + "Traducir" (prompt C, §3.3). Result appears as a normal bubble and is spoken aloud like a voice turn.
- **Nueva** clears the in-memory conversation (confirm with a small dialog). The conversation is also lost when the app process dies — by design (privacy, simplicity).
- **Errors** in plain Spanish inside the bubble with a "Reintentar" button (re-runs the last audio, kept in memory until the next turn): "No he entendido, repite más despacio", "No he oído nada", "Falta permiso de micrófono".
- Screen kept on while the conversation screen is visible (`keepScreenOn` / `isIdleTimerDisabled`).
- Portrait only.
- Accessibility: minimum 64 dp touch targets on primary actions (gloves), Dynamic Type / font scaling, TalkBack/VoiceOver labels in Spanish, live region announcement when a translation completes (once, not per token), content descriptions for 🔊/🎤 buttons, high contrast in sunlight (avoid low-contrast glass over busy content; glass only on controls).

### 5.3 Language detection flow
Triggered from the "Choose language" state or the 🎤? button next to the language chip.
1. Sheet: "Pídele que diga una frase en su idioma" + a big mic (recording rules §4.1).
2. Run prompt B (§3.3). Show "Detectando idioma…".
3. **Detected:** the chip switches to that language (saved), a confirmation banner shows **native name large + Spanish name** ("اردو · Urdu — ¿Correcto?") with **"Sí"** and **"Cambiar"** (opens the picker filtered to related languages first). The coworker can confirm by recognizing his language's native name.
4. **The same clip is then translated** with prompt A (coworker → Spanish) and added as the first bubble, so detection costs no extra turn.
5. **Not detected** (`unknown`/unparseable): "No lo he reconocido" + "Probar otra vez" / "Buscar en la lista".
6. Known ambiguity: spoken **Urdu and Hindi are nearly identical**; detection may return either. For Pakistan the script matters (Urdu = Perso-Arabic). The confirmation banner (native names in their own scripts) is the safeguard; the picker shows related languages (Urdu, Hindi, Punjabi) at the top after a detection.

### 5.4 Language picker
- Bottom sheet / sheet with search field (`searchable`), matching **Spanish name, English name, native name or code**, accent-insensitive (reuse current `search`).
- Sections: **Recientes** (last 5 used, most recent first) then **Todos** (alphabetical by Spanish name).
- Each row: native name (large, own script) + Spanish name + 🔇 if no offline voice on this device.
- Selecting a language saves it and closes the sheet.

### 5.5 Persistence (override of SPEC.md §7 "only persisted setting")
- `coworkerLanguage` (code) — auto-selected on every launch.
- `recentLanguages` (up to 5 codes, comma-separated string).
- Nothing else: no conversation text, no audio. Android `SharedPreferences`, iOS `UserDefaults` (as today).

## 6. Languages (replaces SPEC.md §7)

Walter's language is fixed: **Spanish (`es`, voice `es-ES`)**. The coworker list below is the default set; Gemma 4 claims 35+ languages out of the box and 140+ in pre-training but **Google publishes no per-language list** for text or audio, so quality marks are owner-verified in W5 and the list may be trimmed. Codes are BCP-47; `voice` is the locale requested from the TTS engine.

`Language(code, spanishName, englishName /*used in prompts*/, nativeName, voiceLocale, speakWord /*"speak" in that language, shown on "Habla él"*/)`

| Group | Code | Español | English (prompt) | Native | Voice locale |
|---|---|---|---|---|---|
| South Asia | ur | Urdu | Urdu | اردو | ur-PK |
| | pa-Arab | Panyabí (Pakistán) | Punjabi (Shahmukhi script, as used in Pakistan) | پنجابی | pa-PK¹ |
| | pa | Panyabí (India) | Punjabi (Gurmukhi script) | ਪੰਜਾਬੀ | pa-IN |
| | ps | Pastún | Pashto | پښتو | ps-AF¹ |
| | sd | Sindi | Sindhi (Arabic script) | سنڌي | sd-PK¹ |
| | hi | Hindi | Hindi | हिन्दी | hi-IN |
| | bn | Bengalí | Bengali | বাংলা | bn-BD |
| | ne | Nepalí | Nepali | नेपाली | ne-NP |
| | fa | Persa / Dari | Persian | فارسی | fa-IR¹ |
| Africa & Middle East | ar | Árabe | Arabic (Modern Standard) | العربية | ar-SA |
| | ary | Árabe marroquí (dariya) | Moroccan Arabic (Darija) | الدارجة | ar-MA¹ |
| | wo | Wolof | Wolof | Wolof | —¹ |
| | bm | Bambara | Bambara | Bamanankan | —¹ |
| | ha | Hausa | Hausa | Hausa | —¹ |
| | sw | Suajili | Swahili | Kiswahili | sw-KE¹ |
| | am | Amárico | Amharic | አማርኛ | am-ET¹ |
| | fr | Francés | French | Français | fr-FR |
| | en | Inglés | English | English | en-GB |
| Europe | ro | Rumano | Romanian | Română | ro-RO |
| | bg | Búlgaro | Bulgarian | Български | bg-BG |
| | uk | Ucraniano | Ukrainian | Українська | uk-UA |
| | ru | Ruso | Russian | Русский | ru-RU |
| | pl | Polaco | Polish | Polski | pl-PL |
| | pt | Portugués | Portuguese | Português | pt-PT |
| | it | Italiano | Italian | Italiano | it-IT |
| | de | Alemán | German | Deutsch | de-DE |
| | nl | Neerlandés | Dutch | Nederlands | nl-NL |
| | ca | Catalán | Catalan | Català | ca-ES |
| | sq | Albanés | Albanian | Shqip | sq-AL¹ |
| | ka | Georgiano | Georgian | ქართული | ka-GE¹ |
| | tr | Turco | Turkish | Türkçe | tr-TR |
| Asia | zh | Chino | Chinese (Simplified) | 简体中文 | zh-CN |
| | ja | Japonés | Japanese | 日本語 | ja-JP |
| | ko | Coreano | Korean | 한국어 | ko-KR |
| | vi | Vietnamita | Vietnamese | Tiếng Việt | vi-VN |
| | th | Tailandés | Thai | ไทย | th-TH |
| | id | Indonesio | Indonesian | Bahasa Indonesia | id-ID |
| | tl | Filipino (tagalo) | Filipino (Tagalog) | Filipino | fil-PH |

¹ Offline TTS voice probably **not** available on most phones (research 2026-10-28: no official evidence for Punjabi, Pashto, Sindhi, Wolof, Bambara, Hausa, Tamazight; uncertain for Persian, Darija, Swahili, Amharic, Albanian, Georgian; Urdu likely on Android, unverified on iOS 27). The app never assumes: it checks at runtime (§4.2) and falls back to "show the screen".

Spanish (`es`) is not in the coworker list. Tamazight/Berber is excluded (no evidence of model or voice support). No default coworker language: first launch asks (§5.1).

## 7. Files (override of SPEC.md §3; target ≤ 9 source files per platform)

Android (`android/app/src/main/java/dev/heikki/translator/`):
- `MainActivity.kt`, `Theme.kt`, `Icons.kt`, `ModelDownloader.kt` — unchanged except Spanish strings.
- `TranslatorViewModel.kt` — still the **only** state holder: engine (+ `audioBackend`), download, conversation turns, recorder/speaker orchestration, prefs. Turn state is a small sealed type: `Idle | Listening(side, level) | Translating(side) | Speaking(turnId)`.
- `TranslatorScreen.kt` — all Compose UI (download, choose-language, conversation, picker, writing sheet, full-screen "Mostrar").
- `Languages.kt` — the §6 table + search + recents helpers.
- `Prompt.kt` — prompts A/B/C + parsers (§3.3).
- **`AudioRecorder.kt`** (new) — `AudioRecord` → VAD → WAV bytes. No state beyond a recording session; reports level and result via callback/`Flow`.
- **`Speaker.kt`** (new) — thin `TextToSpeech` wrapper: `init`, `hasOfflineVoice(lang)`, `speak(text, lang, onDone)`, `stop()`, `shutdown()`.
- `res/values/strings.xml` — Spanish strings (only `app_name` = "Traductor Walter" plus whatever exists today).

iOS (`ios/TranslatorApp/`): `TranslatorApp.swift`, `TranslatorModel.swift` (only `@Observable` state holder), `ModelDownloader.swift`, `ContentView.swift`, `Languages.swift`, `Prompt.swift`, **`AudioRecorder.swift`** (new), **`Speaker.swift`** (new). Info.plist: `NSMicrophoneUsageDescription` (Spanish), `CFBundleDisplayName = Traductor Walter`, development region `es`.

Still: no DI, no navigation library, no extra dependencies (recording, VAD, WAV and TTS use platform APIs only).

## 8. Permissions and network scope (override of SPEC.md §4.2 and §8)

Android merged manifest requests **exactly**: `INTERNET`, `ACCESS_NETWORK_STATE`, **`RECORD_AUDIO`**. No `VIBRATE`, no foreground service, no Bluetooth permissions. `<queries>` for `TTS_SERVICE` is not a permission.

Network rules unchanged and re-checked:
- All network code still only in `ModelDownloader.kt` / `ModelDownloader.swift` (existing grep tests keep passing).
- Speech recognition is Gemma (in-process). **No** `SpeechRecognizer`, `RecognizerIntent`, `SFSpeechRecognizer`, `SpeechAnalyzer` anywhere → add them to the forbidden-symbols grep test (they can use cloud recognition).
- TTS: only offline voices with network synthesis disabled (§4.2). Add a grep check that `Speaker.kt` sets `KEY_FEATURE_NETWORK_SYNTHESIS`.
- iOS privacy: microphone usage only; nothing leaves the device. README updated: "El audio se procesa en el teléfono y se borra al instante".

## 9. Testing and acceptance (extends SPEC.md §9)

Unit tests (JVM / XCTest, no model):
- `parseSpeechTranslation`: normal, missing label, label with native/Spanish name, extra blank lines, quotes, no newline.
- `parseDetectedLanguage`: exact code, uppercase, with punctuation, English name, `unknown`, garbage → null.
- Prompt builders contain the right source/target English names and the audio instruction text.
- Languages: unique codes, every entry has all fields, search by Spanish/native/code, recents (dedupe, max 5, most recent first).
- VAD on synthetic PCM: silence only → "no speech"; tone then silence → stops after the silence window; trims leading/trailing silence. WAV header bytes correct for N samples.
- Network-scope tests updated: permissions are exactly the three above; forbidden speech-recognition symbols absent.

Instrumented (Android, no model): launches in the download state with Spanish strings; permission set check.

Manual on device with model (owner + agent on emulator/simulator where possible):
- Agents can exercise audio on the emulator/simulator through the Mac's microphone: start the emulator with `-allow-host-audio` (Simulator uses the Mac mic) and play speech with macOS `say -v Mónica "¿Dónde está el cemento?"` (Spanish) or other `say` voices. Use it for smoke tests only; real quality testing happens on Walter's phone (W5).
- Spanish → Urdu → Spanish round trip in a quiet room, then with background noise (radio/drill recording).
- Detection: Urdu, Punjabi, Pashto, Arabic, Romanian, English speakers (or recordings) → correct or sensible "unknown".
- Barge-in: tapping a mic while speaking stops the speech; TTS is never transcribed.
- Language persists across app kill/relaunch; recents order correct.
- No-voice language (e.g. Pashto) shows the large-text fallback without errors.
- **Airplane mode: full conversation (record, translate, speak, detect) works.**
- Latency per turn recorded (end of recording → first spoken word) for CPU and GPU.

Acceptance criteria:
1. Everything in SPEC.md §9 acceptance still holds, except the permission list (now three) and the UI layout.
2. On Walter's Android phone, in airplane mode: Walter and a coworker complete a 10-turn spoken conversation using only the two big buttons.
3. First launch asks for the coworker's language (detect or search); later launches open directly in the conversation with the saved language.
4. Audio and conversation text are never written to persistent storage; no network access besides the model download.
5. Builds and unit tests pass in CI without the model.

## 10. Milestones (in order; one PR per milestone into `walter`)

- **W0 — Identity & docs:** `applicationId`/bundle ID `dev.heikki.walter`, display name, Spanish strings, AGENTS.md updated (app id, three permissions, points to this spec as source of truth on this branch), README section for Walter, CI workflows also run on pushes to `walter`. No behaviour change yet.
- **W1 — Audio spike (Android first):** `audioBackend`, `AudioRecorder.kt` (WAV, no VAD yet, tap start/stop), prompt A, parser; show transcript + translation in the existing output card. Confirm the WAV format is accepted. Measure CPU vs GPU latency and peak memory on Walter's phone (or the closest device). **If Gemma audio does not work with LiteRT-LM 0.18.1 on Android, stop and report** — no fallback to platform recognizers without the owner's decision.
- **W2 — Conversation UI + TTS (Android):** conversation screen (§5.2), two buttons, bubbles, `Speaker.kt` with offline-voice selection and no-voice fallback, barge-in, haptics, VAD auto-stop, writing sheet, "Nueva".
- **W3 — Languages, detection & persistence (Android):** §6 table, picker with recents and 🔇, first-launch choose-language state, detection flow (§5.3), persistence (§5.5). Tests (§9). Network-scope tests updated.
- **W4 — iOS parity:** same as W1–W3 on iOS (`AudioRecorder.swift`, `Speaker.swift`, Liquid Glass styling of the two buttons and chip), microphone permission, tests, CI green.
- **W5 — Field evaluation with Walter (owner-driven):** real site, real coworkers. Record per language: detection right/wrong, translation understandable (yes/partly/no), voice available, latency. Tune VAD constants and speech rate; trim or mark languages that don't work. Results go into the README.

## 11. Risks and open questions

- **Speech quality for Pakistani languages is unknown.** Google publishes no per-language ASR/AST numbers for Gemma 4 E2B. Urdu is the most likely to work; Punjabi (Shahmukhi), Pashto and Sindhi are uncertain. Many Pakistani workers also understand Urdu → if a language fails in W5, the practical fallback is to use Urdu with that coworker.
- **No offline voices** for several languages (§6¹). Mitigation: the large-text "Mostrar" fallback. Coworkers who can't read (literacy) would then be unreachable → W5 must check; a possible later option is speaking Urdu as a fallback voice for Punjabi-Pakistan (owner's decision).
- **Noise on site** may hurt both VAD and recognition. Tap-to-stop always works; holding the phone closer is the documented tip. Platform noise suppression (`android.media.audiofx.NoiseSuppressor`, iOS voice processing on the input node) may help — try in W5 before adding anything else.
- **Latency on CPU** might feel slow for a conversation; GPU/MTP are the levers (W1 numbers decide).
- **Memory:** audio encoder adds to the ~1.7 GB CPU peak; Walter's phone RAM is unknown (8 GB recommended, SPEC.md §2).
- **Urdu/Hindi confusion** in detection (§5.3 step 6).
- **TTS reading the Spanish translation**: Gemma may output numbers, measurements or abbreviations ("3x4 m"); acceptable for v1, revisit in W5.
- **Wrong-direction typed translations** in writing mode (prompt C) — minor, cosmetic.
- **Open questions for the owner:** app icon; Walter's phone model (RAM, Android version ≥ 8.0); whether to keep the coworker's original transcript visible in bubbles (default: yes, small); whether "Habla él" should auto-arm after Walter's turn in a quiet place (default: no).

## 12. References (in addition to SPEC.md §12)

- Gemma 4 audio prompts, 30 s limit, modality order: https://huggingface.co/google/gemma-4-E2B-it (sections "Audio" and "Audio and Video Length") · https://ai.google.dev/gemma/docs/capabilities/audio
- LiteRT-LM multimodality (Kotlin `audioBackend`, `Content.AudioBytes/AudioFile`): https://ai.google.dev/edge/litert-lm/android#multi-modality
- LiteRT-LM multimodality (Swift `audioBackend`, `Content.audioFile`): https://ai.google.dev/edge/litert-lm/swift#multi-modality
- Android `TextToSpeech` / `Voice` (offline voices, `KEY_FEATURE_NETWORK_SYNTHESIS`, `ACTION_INSTALL_TTS_DATA`): https://developer.android.com/reference/android/speech/tts/TextToSpeech · https://developer.android.com/reference/android/speech/tts/Voice
- Android `AudioRecord`: https://developer.android.com/reference/android/media/AudioRecord
- iOS `AVSpeechSynthesizer`, `AVAudioEngine`, `AVAudioSession`: https://developer.apple.com/documentation/avfaudio
