package nl.collegewhispr.whisper

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class ModelInfo(
    val id: String,
    val label: String,
    val description: String,
    val fileName: String,
    val url: String,
    val approxMb: Int,
)

data class DownloadState(val modelId: String, val bytes: Long, val total: Long, val failed: Boolean = false)

object ModelManager {
    private const val HF = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    val models = listOf(
        ModelInfo(
            "large-v3-turbo-q5_0", "Large v3 Turbo (aanbevolen)",
            "Beste Nederlandse kwaliteit offline. Langzamer; geschikt voor tablets met 6 GB+ RAM.",
            "ggml-large-v3-turbo-q5_0.bin", HF + "ggml-large-v3-turbo-q5_0.bin", 574,
        ),
        ModelInfo(
            "small-q5_1", "Small (snel)",
            "Ongeveer 3x sneller, merkbaar meer fouten in het Nederlands.",
            "ggml-small-q5_1.bin", HF + "ggml-small-q5_1.bin", 190,
        ),
    )

    val vad = ModelInfo(
        "vad", "Stiltedetectie (Silero VAD)", "Slaat stiltes over en voorkomt verzonnen tekst.",
        "ggml-silero-v5.1.2.bin",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin", 1,
    )

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> get() = _downloads

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> get() = _installed

    // Must match setDestinationInExternalFilesDir(ctx, null, "models/...") used for downloads.
    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun file(ctx: Context, m: ModelInfo) = File(dir(ctx), m.fileName)

    fun byId(id: String) = models.firstOrNull { it.id == id }

    fun refresh(ctx: Context) {
        _installed.value = (models + vad).filter { file(ctx, it).let { f -> f.exists() && f.length() > 0 } }
            .map { it.id }.toSet()
    }

    fun isInstalled(ctx: Context, id: String): Boolean {
        val m = (models + vad).firstOrNull { it.id == id } ?: return false
        return file(ctx, m).let { it.exists() && it.length() > 0 }
    }

    /** Model file for offline transcription, falling back to any installed model. */
    fun usableModel(ctx: Context, preferred: String): File? {
        val pref = byId(preferred)
        if (pref != null && isInstalled(ctx, pref.id)) return file(ctx, pref)
        return models.firstOrNull { isInstalled(ctx, it.id) }?.let { file(ctx, it) }
    }

    fun vadFile(ctx: Context): File? = file(ctx, vad).takeIf { it.exists() && it.length() > 0 }

    private val ids = mutableMapOf<String, Long>()
    private var idsLoaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    private fun loadIds(ctx: Context) {
        if (idsLoaded) return
        idsLoaded = true
        prefs(ctx).all.forEach { (k, v) -> if (v is Long) ids[k] = v }
    }

    private fun saveIds(ctx: Context) {
        val e = prefs(ctx).edit().clear()
        ids.forEach { (k, v) -> e.putLong(k, v) }
        e.apply()
    }

    @Synchronized
    fun download(ctx: Context, m: ModelInfo) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val target = file(ctx, m)
        val tmpName = m.fileName + ".part"
        File(dir(ctx), tmpName).delete()
        val req = DownloadManager.Request(Uri.parse(m.url))
            .setTitle("CollegeWhispr: ${m.label}")
            .setDescription("Spraakmodel downloaden (${m.approxMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "models/$tmpName")
            .setAllowedOverMetered(true)
        loadIds(ctx)
        ids[m.id] = dm.enqueue(req)
        saveIds(ctx)
        _downloads.value = _downloads.value + (m.id to DownloadState(m.id, 0, m.approxMb * 1_000_000L))
        if (target.exists()) target.delete()
    }

    /**
     * Polls DownloadManager and installs finished downloads. Called every second while the settings
     * screen is open, and on app resume and before transcribing, so a download that finished after
     * leaving the settings screen is still picked up.
     */
    @Synchronized
    fun poll(ctx: Context) {
        loadIds(ctx)
        if (ids.isEmpty()) return
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val iter = ids.entries.iterator()
        val next = _downloads.value.toMutableMap()
        while (iter.hasNext()) {
            val (modelId, id) = iter.next()
            val m = (models + vad).first { it.id == modelId }
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) { iter.remove(); next.remove(modelId); return@use }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        File(dir(ctx), m.fileName + ".part").renameTo(file(ctx, m))
                        iter.remove(); next.remove(modelId)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        iter.remove()
                        next[modelId] = DownloadState(modelId, got, total, failed = true)
                    }
                    else -> next[modelId] = DownloadState(modelId, got, if (total > 0) total else m.approxMb * 1_000_000L)
                }
            }
        }
        _downloads.value = next
        saveIds(ctx)
        refresh(ctx)
    }

    @Synchronized
    fun cancel(ctx: Context, m: ModelInfo) {
        loadIds(ctx)
        ids.remove(m.id)?.let { ctx.getSystemService(DownloadManager::class.java).remove(it) }
        saveIds(ctx)
        _downloads.value = _downloads.value - m.id
    }

    fun delete(ctx: Context, m: ModelInfo) {
        file(ctx, m).delete()
        refresh(ctx)
    }
}
