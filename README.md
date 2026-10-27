# CollegeWhispr

Android app for transcribing Dutch lectures, based on the approach of
[OpenWhispr](https://github.com/OpenWhispr/openwhispr) (Whisper models, whisper.cpp for local mode).
The interface is in Dutch.

## What it does

1. **Record** a lecture with one tap. Recording runs as a foreground service, so the screen can be
   off. The audio is saved as 16 kHz mono WAV and kept valid every 5 seconds, so a crash loses almost nothing.
   You can also import an existing recording (m4a, mp3, ogg, wav, video files) or share one to the app.
2. **Transcribe after the lecture**, automatically:
   - The recording is split into ~10 minute pieces, cut at the quietest moment so no word is chopped.
   - Each piece goes to **Groq** (`whisper-large-v3`, `language=nl`) when there is internet and a key.
   - Without internet, or if Groq fails, it falls back to **offline whisper.cpp** on the tablet
     (Large v3 Turbo or Small model, with Silero voice-activity detection).
   - Progress is saved per piece, so an interrupted job resumes where it stopped.
3. **Read, search and export**: paragraphs with timestamps (tap one to play the audio from there),
   search, copy, share as .txt/.md, or save to `Download/CollegeWhispr`.

Known Dutch Whisper "hallucinations" on silence (subtitle credits like "Ondertiteling ...") and
repeated lines are filtered out.

## Setup on the tablet

1. Install `CollegeWhispr-1.0.0.apk` (allow "install unknown apps" for your file manager/browser).
2. Open Instellingen:
   - Paste a free Groq API key from https://console.groq.com/keys and tap *Testen*.
   - Optionally download an offline model over wifi (Large v3 Turbo: 574 MB).
   - Tap *Batterijoptimalisatie uitzetten*. On Lenovo ZUI/ZUXOS also allow the app to run in the
     background / auto-start in the system's app management, or long recordings may be stopped.
3. Optional: add course-specific terms and names under *Vaktermen en namen*; it improves spelling.

Groq's free plan (checked October 2026) allows 7,200 audio seconds per hour and 28,800 per day,
25 MB per upload. A 90-minute lecture uses 5,400 seconds.

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

`release.jks` + `keystore.properties` sign the APK. Keep them: updates must be signed with the same
key to install over the existing app without losing your lectures.

## Code map

| Path | Purpose |
|---|---|
| `service/RecordingService.kt` | microphone foreground service, WAV writing, pause/resume |
| `service/TranscriptionService.kt` | queue, chunking, Groq first then offline fallback, resume |
| `transcribe/GroqClient.kt` | Groq OpenAI-compatible transcription API |
| `whisper/WhisperLib.kt`, `native/jni.c` | whisper.cpp bridge (picks fp16/dotprod build when the CPU supports it) |
| `whisper/ModelManager.kt` | offline model downloads via Android DownloadManager |
| `audio/` | WAV helpers, silence-aware chunker, resampler, audio import |
| `transcribe/TextFormat.kt` | hallucination filter, paragraphs, export formats |
| `ui/` | Jetpack Compose screens |

## Changing it with Claude Code

Open this folder in Claude Code and describe the change, for example
"add a speaker-change marker" or "also export to .docx". Rebuild with `./gradlew assembleRelease`.
