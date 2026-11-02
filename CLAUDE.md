# CollegeWhispr: notes for Claude

Android app (Kotlin, Jetpack Compose) that records Dutch lectures and transcribes them with
Deepgram Nova-3 (default), Groq or Voxtral, with offline whisper.cpp as fallback. The UI is Dutch
and styled after iOS: use `ui/Theme.kt` (`LocalIos` colors, `IosType`) and the building blocks in
`ui/Components.kt` (`Group`, `IosRow`, `CheckRow`, `SwitchRow`, `NavBar`, `IosAlert`, ...) for new
screens. See README.md for features and the code map.

## Building a release (works from a Claude cloud session)

Claude cloud sessions cannot reach Google Maven or the Android SDK (`dl.google.com`,
`maven.google.com`) nor GitHub's artifact storage (`*.blob.core.windows.net`). Git over
github.com works. So GitHub Actions builds, and the APK is signed locally:

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, and the APK name in the
   README setup steps. Current: versionCode 8, versionName 1.3.3.
2. Push to `main`. `.github/workflows/build-apk.yml` runs on every push to `main` (and on manual
   dispatch): unit tests, `assembleRelease` without signing, then force-pushes
   `app-release-unsigned.apk` and the SDK's `apksigner.jar` to the `apk-build` branch with the
   commit message `APK build of <sha>`.
3. Wait until `git fetch origin apk-build` shows that message for the pushed sha (a build takes
   about 4 minutes). If it never appears, the run failed: read the job log with the GitHub tools.
4. Sign with the user's key. `release.jks` and `keystore.properties` are NOT in the repository
   (never commit them; `.gitignore` excludes them). Ask the user to upload them, then:

   ```bash
   git clone -q --depth 1 -b apk-build https://github.com/Woofer-dev/WooferWhispr apk-build
   java -jar apk-build/apksigner.jar sign --ks release.jks --ks-key-alias collegewhispr \
     --ks-pass pass:<storePassword> --key-pass pass:<keyPassword> \
     --out CollegeWhispr-<version>.apk apk-build/app-release-unsigned.apk
   java -jar apk-build/apksigner.jar verify --print-certs CollegeWhispr-<version>.apk
   ```

   The passwords are in `keystore.properties`. The certificate SHA-256 must be
   `9049052ae55ae4d81c95e47657b5fd5e50f5b3b5458bbae2df37044f459ea631`; with another key the
   update will not install over the user's app. Delete the `.idsig` file apksigner writes next to
   the APK and send only the APK.

## Checks before pushing

- The Compose screens can only be compiled by the GitHub build. The non-UI code (audio,
  transcribe, data, whisper, service) and the unit tests in `app/src/test` can be checked locally
  with a plain Kotlin JVM Gradle project that uses those source folders, an `android.jar` taken
  from `org.robolectric:android-all` on Maven Central as `compileOnly`, small stubs for the few
  androidx classes the services use, and `-Xnullability-annotations=@android.annotation:ignore`.
- Online transcription APIs (Deepgram, Groq, Mistral) are not reachable from a cloud session
  either; parsers are covered by unit tests with sample responses.

## Native code

`app/src/main/jniLibs/arm64-v8a/*.so` are prebuilt whisper.cpp libraries. Rebuilding them needs
the Android NDK, CMake and a whisper.cpp checkout (`native/build-native.sh`); normally leave them.
