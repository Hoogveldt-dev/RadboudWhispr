# CollegeWhispr

Android app for transcribing Dutch lectures, based on the approach of
[OpenWhispr](https://github.com/OpenWhispr/openwhispr) (Whisper models, whisper.cpp for local mode).
The interface is in Dutch, styled after iOS (grouped lists, large titles, system colors, light and dark).

## What it does

1. **Record** a lecture with one tap. Recording runs as a foreground service, so the screen can be
   off. The audio is saved as 16 kHz mono WAV and kept valid every 5 seconds, so a crash loses almost nothing.
   You can also import an existing recording (m4a, mp3, ogg, wav, video files) or share one to the app.
2. **Transcribe after the lecture**, automatically:
   - The recording is split into ~10 minute pieces, cut at the quietest moment so no word is chopped.
   - Each piece goes to the chosen online service when there is internet and a key. The default
     is **Deepgram** (Nova-3, with Dutch and Flemish models); **Groq** (`whisper-large-v3`) and
     **Voxtral** (Mistral) can be used instead or to compare.
   - Without internet, or if the online service fails, it falls back to **offline whisper.cpp** on the
     tablet (Large v3 Turbo or Small model, with Silero voice-activity detection).
   - Progress is saved per piece, so an interrupted job resumes where it stopped.
3. **Read, search and export**: paragraphs with timestamps (tap one to play the audio from there),
   search, copy, share as .txt/.md, or save to `Download/CollegeWhispr`.
4. **Compare engines**: *Opnieuw transcriberen met ...* in a lecture's menu re-runs it with another
   service. The previous transcript is kept as a version, and chips on the lecture screen switch
   between versions.

Known Dutch Whisper "hallucinations" on silence (subtitle credits like "Ondertiteling ...") and
repeated lines are filtered out.

## Setup on the tablet

1. Install `CollegeWhispr-1.3.1.apk` (allow "install unknown apps" for your file manager/browser).
2. Open Instellingen:
   - Under *Online transcriptie*, paste a Deepgram API key (https://console.deepgram.com/, then
     API Keys) and tap *Testen*; it also shows the remaining credit when the key may read billing.
     Groq and Voxtral sit under *Andere diensten*, only needed to compare.
   - Optionally download an offline model over wifi (Large v3 Turbo: 574 MB).
   - Tap *Batterijoptimalisatie uitzetten*. On Lenovo ZUI/ZUXOS also allow the app to run in the
     background / auto-start in the system's app management, or long recordings may be stopped.
3. Optional: add course-specific terms and names under *Vaktermen en namen*, separated by commas;
   Deepgram receives them as keyterms. Choose *Nederlands (Vlaams)* for Flemish lecturers.

Groq's free plan (checked October 2026) allows 7,200 audio seconds per hour and 28,800 per day,
25 MB per upload. A 90-minute lecture uses 5,400 seconds. Deepgram and Mistral charge per minute
(roughly $0.26 and $0.20 per audio hour in October 2026); Deepgram gives starting credit on signup.

Service notes: Deepgram gets the vocabulary as separate keyterms, and its own sentences (with
times) become the segments. The filter for Whisper's invented subtitle lines only runs on Whisper
output, so a lecturer saying a web address on Deepgram is kept. Voxtral gets them as context bias
(experimental for Dutch per Mistral) and is sent no language, because Mistral does not accept a
language together with timestamps; it detects the language itself.

## Building

Requirements: JDK 17+, Android SDK 35, NDK r28, CMake 3.22+, and a clone of whisper.cpp.

```bash
# 1. native library (whisper.cpp + JNI bridge), two CPU variants for arm64
WHISPER_DIR=~/whisper.cpp ANDROID_HOME=~/android-sdk native/build-native.sh
# 2. APK
./gradlew assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
# 3. tests
./gradlew testDebugUnitTest
```

`release.jks` + `keystore.properties` sign the APK. They are **not in this repository** (see
`.gitignore`): put your copies in the project root before `assembleRelease`, and keep a backup
somewhere safe. Updates must be signed with the same key to install over the existing app without
losing your lectures. Without the two files, `assembleRelease` produces an unsigned APK.

`keystore.properties` looks like this:

```properties
storeFile=release.jks
storePassword=...
keyAlias=collegewhispr
keyPassword=...
```

## Code map

| Path | Purpose |
|---|---|
| `service/RecordingService.kt` | microphone foreground service, WAV writing, pause/resume |
| `service/TranscriptionService.kt` | queue, chunking, online service first then offline fallback, resume |
| `transcribe/GroqClient.kt`, `DeepgramClient.kt`, `VoxtralClient.kt` | online transcription APIs |
| `transcribe/CloudStt.kt` | shared HTTP, multipart and verbose_json parsing for those clients |
| `whisper/WhisperLib.kt`, `native/jni.c` | whisper.cpp bridge (picks fp16/dotprod build when the CPU supports it) |
| `whisper/ModelManager.kt` | offline model downloads via Android DownloadManager |
| `audio/` | WAV helpers, silence-aware chunker, resampler, audio import |
| `transcribe/TextFormat.kt` | hallucination filter, paragraphs, export formats |
| `ui/` | Jetpack Compose screens; `Theme.kt` (iOS palette, type scale) and `Components.kt` (grouped lists, rows, switches, alerts) give the iOS look |

## Changing it with Claude Code

Open this folder in Claude Code and describe the change, for example
"add a speaker-change marker" or "also export to .docx". Rebuild with `./gradlew assembleRelease`.
