package nl.collegewhispr.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.collegewhispr.App
import nl.collegewhispr.R
import nl.collegewhispr.audio.SAMPLE_RATE
import nl.collegewhispr.audio.Wav
import nl.collegewhispr.audio.Decimator
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.data.Settings
import nl.collegewhispr.ui.MainActivity
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class RecorderState(
    val lectureId: String? = null,
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val error: String? = null,
)

class RecordingService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> get() = _state

        fun start(ctx: Context) = send(ctx, ACTION_START, foreground = true)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)
        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun clearError() { if (_state.value.error != null) _state.value = RecorderState() }

        private fun send(ctx: Context, action: String, foreground: Boolean = false) {
            val i = Intent(ctx, RecordingService::class.java).setAction(action)
            if (foreground) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
        }
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> { paused = true; _state.value = _state.value.copy(paused = true); updateNotification() }
            ACTION_RESUME -> { paused = false; _state.value = _state.value.copy(paused = false); updateNotification() }
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CH_RECORDING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "Opname gepauzeerd" else "Hoorcollege wordt opgenomen")
            .setContentText("Tik om de app te openen")
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - _state.value.elapsedMs)
            .addAction(0, if (paused) "Hervatten" else "Pauzeren", toggle)
            .addAction(0, "Stoppen", stop)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (running) {
            // every startForegroundService() must be answered with startForeground()
            runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notification(), type) }
            return
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            _state.value = RecorderState(error = "Kon opname niet starten: ${e.message}")
            stopSelf()
            return
        }

        // Try 16 kHz directly; fall back to 48 kHz with software downsampling.
        var rate = SAMPLE_RATE
        var record: AudioRecord? = null
        for (r in intArrayOf(SAMPLE_RATE, 48000, 44100)) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, r, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r))
            } catch (e: Exception) { null }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) { record = rec; rate = r; break }
            rec?.release()
        }
        if (record == null) {
            _state.value = RecorderState(error = "Microfoon niet beschikbaar")
            stopForegroundCompat(); stopSelf(); return
        }

        val lecture = LectureRepository.newLecture()
        LectureRepository.liveRecordingId = lecture.id
        val file = LectureRepository.audio(lecture.id)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CollegeWhispr:rec").apply { acquire(6 * 3600_000L) }

        running = true
        paused = false
        _state.value = RecorderState(lectureId = lecture.id, recording = true)

        val rec = record
        val srcRate = rate
        thread = Thread({ loop(rec, srcRate, file) }, "recorder").apply { start() }
    }

    private fun loop(rec: AudioRecord, srcRate: Int, file: java.io.File) {
        val decimator = if (srcRate == SAMPLE_RATE) null else Decimator(srcRate, SAMPLE_RATE)
        val buf = ShortArray(srcRate / 10) // 100 ms
        var written = 0L
        var lastHeader = SystemClock.elapsedRealtime()
        var lastUi = 0L
        var elapsed = 0L
        var isRecording = false
        var error: String? = null
        // An exception escaping this thread would crash the app; stop cleanly and keep what was recorded.
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(Wav.header(0))
                while (running) {
                    if (paused) {
                        if (isRecording) { rec.stop(); isRecording = false }
                        Thread.sleep(100)
                        continue
                    }
                    if (!isRecording) { rec.startRecording(); isRecording = true }
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { Thread.sleep(20); continue }
                    val samples = if (decimator != null) decimator.process(buf, n) else buf.copyOf(n)
                    val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(samples)
                    raf.write(bb.array())
                    written += samples.size * 2
                    elapsed = written * 1000 / (SAMPLE_RATE * 2)

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUi > 150) {
                        var sum = 0.0
                        for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                        val rms = sqrt(sum / n)
                        val level = (20 * kotlin.math.log10(rms + 1e-6) + 60).coerceIn(0.0, 60.0) / 60.0
                        _state.value = _state.value.copy(elapsedMs = elapsed, level = level.toFloat())
                        lastUi = now
                    }
                    if (now - lastHeader > 5000) {
                        // keep the file valid at all times, so a crash loses at most a few seconds
                        val pos = raf.filePointer
                        raf.seek(0); raf.write(Wav.header(written)); raf.seek(pos)
                        lastHeader = now
                    }
                }
                raf.seek(0)
                raf.write(Wav.header(written))
            }
        } catch (e: Exception) {
            Log.e("RecordingService", "Opname afgebroken", e)
            error = "Opname gestopt door een fout: ${e.message ?: e.javaClass.simpleName}"
            runCatching { Wav.fixHeader(file) }
        } finally {
            if (isRecording) runCatching { rec.stop() }
            rec.release()
        }
        finish(elapsed, error)
    }

    private fun finish(elapsedMs: Long, error: String? = null) {
        running = false
        val id = _state.value.lectureId ?: return
        LectureRepository.liveRecordingId = null
        if (elapsedMs < 2000) {
            LectureRepository.delete(id)
        } else {
            val auto = Settings.current.autoTranscribe
            LectureRepository.update(id) {
                it.copy(durationMs = elapsedMs, status = if (auto) LectureStatus.QUEUED else LectureStatus.RECORDED,
                    message = error?.let { "Opname werd door een fout onderbroken; tot dat moment opgeslagen." })
            }
            if (auto) TranscriptionService.enqueue(this, id)
        }
        _state.value = RecorderState(error = error)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopRecording() {
        if (!running) { stopForegroundCompat(); stopSelf(); return }
        running = false
        paused = false
    }

    private fun stopForegroundCompat() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    override fun onDestroy() {
        running = false
        thread?.join(3000)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
