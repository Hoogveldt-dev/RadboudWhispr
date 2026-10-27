package nl.collegewhispr.whisper

import android.util.Log
import java.io.File

fun interface ProgressListener {
    fun onProgress(percent: Int)
}

object WhisperLib {
    private const val TAG = "WhisperLib"

    val variant: String by lazy {
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val fast = features.contains("asimddp") && features.contains("fphp")
        val name = if (fast) "whisper_jni_v82" else "whisper_jni_base"
        try {
            System.loadLibrary(name)
            name
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Kon $name niet laden, val terug op basisversie", e)
            System.loadLibrary("whisper_jni_base")
            "whisper_jni_base"
        }
    }

    fun ensureLoaded() { variant }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun requestAbort()
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun transcribe(
        ptr: Long,
        audio: FloatArray,
        language: String?,
        prompt: String?,
        vadModel: String?,
        threads: Int,
        listener: ProgressListener?,
    ): ByteArray?
}
