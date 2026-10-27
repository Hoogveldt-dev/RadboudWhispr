package nl.collegewhispr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        LectureRepository.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Opname", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TRANSCRIBE, "Transcriptie", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Transcriptie klaar", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_RECORDING = "recording"
        const val CH_TRANSCRIBE = "transcribe"
        const val CH_DONE = "done"
    }
}
