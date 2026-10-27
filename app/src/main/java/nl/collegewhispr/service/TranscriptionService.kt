package nl.collegewhispr.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.collegewhispr.App
import nl.collegewhispr.R
import nl.collegewhispr.audio.Chunker
import nl.collegewhispr.audio.Dsp
import nl.collegewhispr.audio.SAMPLE_RATE
import nl.collegewhispr.audio.Wav
import nl.collegewhispr.data.Chunk
import nl.collegewhispr.data.EngineMode
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.data.Segment
import nl.collegewhispr.data.Settings
import nl.collegewhispr.transcribe.GroqClient
import nl.collegewhispr.transcribe.GroqException
import nl.collegewhispr.ui.MainActivity
import nl.collegewhispr.whisper.ModelManager
import nl.collegewhispr.whisper.WhisperLib
import java.io.IOException

data class TranscriptionState(
    val lectureId: String? = null,
    val chunk: Int = 0,
    val chunks: Int = 0,
    val chunkProgress: Float = 0f,
    val engine: String = "",
)

class TranscriptionService : Service() {

    companion object {
        private const val TAG = "Transcription"
        private const val ACTION_ENQUEUE = "enqueue"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_ID = "id"
        private const val EXTRA_RESET = "reset"
        private const val EXTRA_ENGINE = "engine"
        private const val NOTIF_ID = 2

        private val _state = MutableStateFlow(TranscriptionState())
        val state: StateFlow<TranscriptionState> get() = _state

        fun enqueue(ctx: Context, id: String, reset: Boolean = false, engine: EngineMode? = null) {
            val i = Intent(ctx, TranscriptionService::class.java)
                .setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, id).putExtra(EXTRA_RESET, reset)
            engine?.let { i.putExtra(EXTRA_ENGINE, it.name) }
            if (reset) LectureRepository.clearChunks(id)
            LectureRepository.update(id) { it.copy(status = LectureStatus.QUEUED, message = null) }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Android may refuse when the app is in the background; it resumes on next app start.
                Log.w(TAG, "Kon dienst niet starten", e)
            }
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<Pair<String, EngineMode?>>()
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                goForeground("Transcriptie voorbereiden…", 0f)
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val eng = intent.getStringExtra(EXTRA_ENGINE)?.let { runCatching { EngineMode.valueOf(it) }.getOrNull() }
                synchronized(queue) {
                    if (queue.none { it.first == id } && _state.value.lectureId != id) queue.addLast(id to eng)
                }
                startWorker()
            }
            ACTION_CANCEL -> {
                cancelled = true
                synchronized(queue) { queue.clear() }
                runCatching { WhisperLib.requestAbort() }
                worker?.cancel()
                if (worker?.isActive != true) stopSelf()
            }
            else -> if (worker?.isActive != true) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(text: String, progress: Float) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(text, progress), type)
    }

    private fun notification(text: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CH_TRANSCRIBE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transcriberen")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, (progress * 1000).toInt(), false)
            .addAction(0, "Stoppen", cancel)
            .build()
    }

    private fun notify(text: String, progress: Float) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(text, progress))
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        cancelled = false
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CollegeWhispr:tx").apply { acquire(4 * 3600_000L) }
        worker = scope.launch {
            var local: LocalEngine? = null
            try {
                while (true) {
                    val next = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    if (local == null) local = LocalEngine(this@TranscriptionService)
                    try {
                        process(next.first, next.second, local)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        LectureRepository.update(next.first) {
                            it.copy(status = LectureStatus.RECORDED, message = "Gestopt. Tik op Transcriberen om verder te gaan.")
                        }
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Fout bij ${next.first}", e)
                        LectureRepository.update(next.first) {
                            it.copy(status = LectureStatus.ERROR, message = e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                local?.close()
                _state.value = TranscriptionState()
                wakeLock?.let { if (it.isHeld) it.release() }
                ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun process(id: String, override: EngineMode?, local: LocalEngine) {
        val lecture = LectureRepository.get(id) ?: return
        val file = LectureRepository.audio(id)
        if (!file.exists()) error("Het audiobestand ontbreekt")
        val info = Wav.info(file)
        require(info.sampleRate == SAMPLE_RATE && info.channels == 1 && info.bits == 16) { "Onverwacht audioformaat" }

        var chunks = LectureRepository.loadChunks(id) ?: Chunker.plan(info.totalSamples) { s, n ->
            Wav.readSamples(file, info, s, n)
        }.map { Chunk(it.first, it.last + 1) }.also { LectureRepository.saveChunks(id, it) }

        val settings = Settings.current
        val mode = override ?: settings.engine
        val enginesUsed = chunks.mapNotNull { it.engine }.toMutableSet()
        var groqBlocked: String? = null
        var prevTail = chunks.lastOrNull { it.done }?.segments?.joinToString(" ") { it.text }?.takeLast(200) ?: ""

        LectureRepository.update(id) {
            it.copy(status = LectureStatus.TRANSCRIBING, message = null,
                progress = chunks.count { c -> c.done }.toFloat() / chunks.size)
        }

        for ((i, c) in chunks.withIndex()) {
            if (c.done) continue
            coroutineContextCheck()
            val raw = Wav.readSamples(file, info, c.startSample, (c.endSample - c.startSample).toInt())
            val samples = Dsp.normalize(raw)
            val prompt = (Settings.basePrompt().take(350) + " " + prevTail.takeLast(150)).trim()
            val offsetMs = c.startSample * 1000 / SAMPLE_RATE

            fun progress(p: Float, engine: String) {
                val total = (i + p) / chunks.size
                _state.value = TranscriptionState(id, i + 1, chunks.size, p, engine)
                LectureRepository.update(id) { it.copy(progress = total) }
                notify("${lecture.title}: deel ${i + 1} van ${chunks.size} ($engine)", total)
            }

            var result: List<Segment>? = null
            var usedEngine = ""
            var reason: String? = null

            val canGroq = mode != EngineMode.OFFLINE_ONLY && settings.groqKey.isNotBlank() && groqBlocked == null
            if (canGroq && online()) {
                progress(0.05f, "Groq")
                val r = tryGroq(settings.groqKey, settings.groqModel, samples, settings.language, prompt,
                    allowLongWait = mode == EngineMode.GROQ_ONLY || !local.available())
                when {
                    r.segments != null -> { result = r.segments; usedEngine = "Groq" }
                    r.fatal -> { groqBlocked = r.message; reason = r.message }
                    else -> reason = r.message
                }
            } else if (mode != EngineMode.OFFLINE_ONLY) {
                reason = when {
                    settings.groqKey.isBlank() -> "Geen Groq API-sleutel ingesteld"
                    groqBlocked != null -> groqBlocked
                    else -> "Geen internetverbinding"
                }
            }

            if (result == null && mode != EngineMode.GROQ_ONLY) {
                if (local.available()) {
                    usedEngine = "Offline (${local.modelLabel()})"
                    progress(0f, usedEngine)
                    result = local.transcribe(samples, settings.language, prompt) { p -> progress(p / 100f, usedEngine) }
                    if (result == null && cancelled) throw kotlinx.coroutines.CancellationException()
                    if (result == null) reason = "Offline transcriptie mislukt"
                } else if (reason == null || mode == EngineMode.OFFLINE_ONLY) {
                    reason = "Geen offline model gedownload"
                } else {
                    reason += "; geen offline model gedownload"
                }
            }

            if (result == null) {
                LectureRepository.update(id) {
                    it.copy(status = LectureStatus.WAITING, message = reason ?: "Onbekende fout")
                }
                return
            }

            val shifted = result.map { s -> s.copy(startMs = s.startMs + offsetMs, endMs = s.endMs + offsetMs) }
            chunks = chunks.toMutableList().also { it[i] = c.copy(done = true, engine = usedEngine, segments = shifted) }
            LectureRepository.saveChunks(id, chunks)
            enginesUsed += usedEngine
            prevTail = shifted.joinToString(" ") { it.text }.takeLast(200)
        }

        LectureRepository.update(id) {
            it.copy(status = LectureStatus.DONE, progress = 1f, message = null,
                engines = enginesUsed.joinToString(" + "))
        }
        notifyDone(id, lecture.title)
    }

    private suspend fun coroutineContextCheck() = kotlin.coroutines.coroutineContext.ensureActive()

    private class GroqResult(val segments: List<Segment>?, val fatal: Boolean = false, val message: String? = null)

    private suspend fun tryGroq(key: String, model: String, samples: ShortArray, lang: String, prompt: String,
                                allowLongWait: Boolean): GroqResult {
        val wav = Wav.wavBytes(samples)
        var lastMsg = ""
        var p = prompt
        for (attempt in 1..4) {
            try {
                return GroqResult(GroqClient.transcribe(key, model, wav, lang, p))
            } catch (e: GroqException) {
                lastMsg = "Groq: ${e.message}"
                when (e.code) {
                    401, 403 -> return GroqResult(null, fatal = true, message = "Groq API-sleutel ongeldig of geweigerd")
                    429 -> {
                        val wait = e.retryAfterS ?: (30L * attempt)
                        if (wait > 120 && !allowLongWait) return GroqResult(null, message = "Groq-limiet bereikt")
                        delay(minOf(wait, 20 * 60L) * 1000)
                    }
                    400 -> {
                        // e.g. prompt too long: retry once without it
                        if (p.isEmpty()) return GroqResult(null, fatal = true, message = lastMsg)
                        p = ""
                    }
                    404, 413 -> return GroqResult(null, fatal = true, message = lastMsg)
                    else -> delay(5_000L * attempt)
                }
            } catch (e: IOException) {
                lastMsg = "Netwerkfout: ${e.message}"
                if (!online()) return GroqResult(null, message = "Geen internetverbinding")
                delay(5_000L * attempt)
            }
        }
        return GroqResult(null, message = lastMsg)
    }

    private fun notifyDone(id: String, title: String) {
        val open = PendingIntent.getActivity(
            this, id.hashCode(), Intent(this, MainActivity::class.java).putExtra("lectureId", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, App.CH_DONE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Transcriptie klaar")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(id.hashCode(), n)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits dataSync services; progress is saved per chunk, so just stop.
        cancelled = true
        runCatching { WhisperLib.requestAbort() }
        worker?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

/** Wraps a whisper.cpp context, loaded lazily and reused for all chunks. */
class LocalEngine(private val ctx: Context) {
    private var ptr = 0L
    private var loadedPath: String? = null

    private fun modelFile() = ModelManager.usableModel(ctx, Settings.current.offlineModel)

    fun available() = modelFile() != null

    fun modelLabel(): String {
        val f = modelFile() ?: return "?"
        return ModelManager.models.firstOrNull { it.fileName == f.name }?.label?.substringBefore(" (") ?: f.name
    }

    fun transcribe(samples: ShortArray, language: String, prompt: String, onProgress: (Int) -> Unit): List<Segment>? {
        val f = modelFile() ?: return null
        WhisperLib.ensureLoaded()
        if (ptr == 0L || loadedPath != f.absolutePath) {
            close()
            ptr = WhisperLib.initContext(f.absolutePath)
            loadedPath = f.absolutePath
            if (ptr == 0L) return null
        }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val vad = ModelManager.vadFile(ctx)?.absolutePath
        val lang = if (language == "auto") null else language
        val bytes = WhisperLib.transcribe(ptr, Dsp.toFloat(samples), lang, prompt, vad, threads) { onProgress(it) }
            ?: return null
        return String(bytes, Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) null else Segment(parts[0].toLong(), parts[1].toLong(), parts[2].trim())
        }.toList()
    }

    fun close() {
        if (ptr != 0L) WhisperLib.freeContext(ptr)
        ptr = 0L
        loadedPath = null
    }
}
