#!/usr/bin/env bash
# Builds libwhisper_jni_v82.so and libwhisper_jni_base.so for arm64-v8a
# and copies them into app/src/main/jniLibs/arm64-v8a.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
NDK="${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
CMAKE="$(ls -d "$SDK"/cmake/* | sort -V | tail -1)/bin/cmake"
WHISPER_DIR="${WHISPER_DIR:-$HOME/whisper.cpp}"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"

# Patch: whisper.cpp runs Silero VAD with a hardcoded 4 threads. For this tiny model the
# thread barriers cost far more than they save and can stall on busy CPUs; use 1 thread.
"$HERE/patch-whisper.sh" "$WHISPER_DIR"
mkdir -p "$OUT"

for VARIANT in v82 base; do
  B="$HERE/build-$VARIANT"
  "$CMAKE" -S "$HERE" -B "$B" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DWHISPER_DIR="$WHISPER_DIR" -DVARIANT=$VARIANT
  "$CMAKE" --build "$B" --target whisper_jni_$VARIANT -j"$(nproc)"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
    -o "$OUT/libwhisper_jni_$VARIANT.so" "$B/libwhisper_jni_$VARIANT.so"
done
ls -la "$OUT"
