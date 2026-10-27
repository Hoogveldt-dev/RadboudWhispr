# JNI entry points and the progress callback called from native code
-keep class nl.collegewhispr.whisper.WhisperLib { native <methods>; *; }
-keep interface nl.collegewhispr.whisper.ProgressListener { *; }
-keep class * implements nl.collegewhispr.whisper.ProgressListener { void onProgress(int); }
