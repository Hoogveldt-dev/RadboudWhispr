package nl.collegewhispr.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.collegewhispr.App
import nl.collegewhispr.R
import nl.collegewhispr.audio.AudioImporter
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.data.Settings
import nl.collegewhispr.ui.MainActivity

data class ImportState(
    /** Lecture being imported now, or null when idle. */
    val lectureId: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    /** Set once when an import finished, so the app can open it. */
    val finishedId: String? = null,
)

/**
 * Converts an imported audio file in a foreground service, so it keeps running when the
 * screen locks or the app goes to the background (Android would otherwise stop the app
 * halfway through a long lecture).
 */
class ImportService : Service() {

    companion object {
        private const val TAG = "Import"
        private const val NOTIF_ID = 3

        private val _state = MutableStateFlow(ImportState())
        val state: StateFlow<ImportState> get() = _state

        fun start(ctx: Context, uri: Uri) {
            if (_state.value.lectureId != null) return
            val lecture = LectureRepository.newLecture(LectureStatus.IMPORTING).let { l ->
                val name = displayName(ctx, uri)?.substringBeforeLast('.')
                if (name != null) l.copy(title = name).also { LectureRepository.save(it) } else l
            }
            _state.value = ImportState(lectureId = lecture.id)
            val i = Intent(ctx, ImportService::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("id", lecture.id)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "Kon dienst niet starten", e)
                LectureRepository.delete(lecture.id)
                _state.value = ImportState(error = "Importeren kon niet starten: ${e.message}")
            }
        }

        fun clearError() { _state.value = _state.value.copy(error = null) }
        fun consumeFinished() { _state.value = _state.value.copy(finishedId = null) }

        private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(0f)
        val uri = intent?.data
        val id = intent?.getStringExtra("id")
        if (uri == null || id == null || worker != null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CollegeWhispr:import").apply { acquire(2 * 3600_000L) }
        worker = Thread({ run(uri, id) }, "import").apply { start() }
        return START_NOT_STICKY
    }

    private fun run(uri: Uri, id: String) {
        var lastNotify = 0L
        try {
            val ms = AudioImporter.import(this, uri, LectureRepository.audio(id), shouldStop = { stopped }) { p ->
                _state.value = _state.value.copy(progress = p)
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotify > 1000) { lastNotify = now; notify(p) }
            }
            val auto = Settings.current.autoTranscribe
            LectureRepository.update(id) {
                it.copy(durationMs = ms, status = if (auto) LectureStatus.QUEUED else LectureStatus.RECORDED, message = null)
            }
            if (auto) TranscriptionService.enqueue(this, id)
            _state.value = ImportState(finishedId = id)
        } catch (e: Throwable) {
            Log.w(TAG, "Importeren mislukt", e)
            LectureRepository.delete(id)
            _state.value = ImportState(
                error = if (stopped) "Importeren werd door Android gestopt. Probeer het opnieuw."
                else "Importeren mislukt: ${e.message ?: e.javaClass.simpleName}",
            )
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground(progress: Float) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification(progress), type)
    }

    private fun notification(progress: Float): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, App.CH_TRANSCRIBE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Audio importeren")
            .setContentText("${(progress * 100).toInt()}%")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(1000, (progress * 1000).toInt(), false)
            .build()
    }

    private fun notify(progress: Float) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(progress))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits dataSync services; stop cleanly instead of being killed.
        stopped = true
    }

    override fun onDestroy() {
        stopped = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
