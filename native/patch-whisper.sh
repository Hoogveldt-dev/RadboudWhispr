#!/usr/bin/env bash
# Idempotent patch: single-threaded VAD inside whisper_full().
set -euo pipefail
F="$1/src/whisper.cpp"
if ! grep -q "collegewhispr: single-threaded VAD" "$F"; then
  sed -i 's|^\(\s*\)struct whisper_vad_context_params vad_ctx_params = whisper_vad_default_context_params();|&\n\1vad_ctx_params.n_threads = 1; // collegewhispr: single-threaded VAD|' "$F"
fi
grep -n "collegewhispr: single-threaded VAD" "$F"
