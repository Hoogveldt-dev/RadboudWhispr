package nl.collegewhispr.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.collegewhispr.audio.AudioImporter
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.data.Settings
import nl.collegewhispr.service.RecordingService
import nl.collegewhispr.service.TranscriptionService
import nl.collegewhispr.whisper.ModelManager

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity() {

    private var screen by mutableStateOf<Screen>(Screen.Home)
    var importProgress by mutableStateOf<Float?>(null)
        private set
    var importError by mutableStateOf<String?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) RecordingService.start(this)
        else importError = "Zonder microfoontoestemming kan de app niet opnemen."
    }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            AppTheme {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        activity = this,
                        onOpen = { screen = Screen.Detail(it) },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                    is Screen.Detail -> DetailScreen(s.id, onBack = { screen = Screen.Home })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("lectureId")?.let { screen = Screen.Detail(it) }
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) importAudio(uri)
            setIntent(Intent())
        }
    }

    override fun onResume() {
        super.onResume()
        ModelManager.refresh(this)
        // Pick up lectures that were waiting for a connection or got interrupted.
        if (TranscriptionService.state.value.lectureId == null && RecordingService.state.value.lectureId == null) {
            LectureRepository.lectures.value
                .filter { it.status == LectureStatus.QUEUED || it.status == LectureStatus.WAITING }
                .sortedBy { it.createdAt }
                .forEach { TranscriptionService.enqueue(this, it.id) }
        }
    }

    fun startRecording() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) RecordingService.start(this) else micPermission.launch(missing.toTypedArray())
    }

    fun pickAudioFile() = pickAudio.launch(arrayOf("audio/*", "video/*"))

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun importAudio(uri: Uri) {
        if (importProgress != null) return
        importProgress = 0f
        lifecycleScope.launch {
            val lecture = LectureRepository.newLecture().let { l ->
                val name = displayName(uri)?.substringBeforeLast('.')
                if (name != null) l.copy(title = name).also { LectureRepository.save(it) } else l
            }
            try {
                val ms = withContext(Dispatchers.IO) {
                    AudioImporter.import(this@MainActivity, uri, LectureRepository.audio(lecture.id)) { p ->
                        runOnUiThread { importProgress = p }
                    }
                }
                val auto = Settings.current.autoTranscribe
                LectureRepository.update(lecture.id) {
                    it.copy(durationMs = ms, status = if (auto) LectureStatus.QUEUED else LectureStatus.RECORDED)
                }
                if (auto) TranscriptionService.enqueue(this@MainActivity, lecture.id)
                screen = Screen.Detail(lecture.id)
            } catch (e: Exception) {
                LectureRepository.delete(lecture.id)
                importError = "Importeren mislukt: ${e.message}"
            } finally {
                importProgress = null
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9CC3EA))
        else -> lightColorScheme(primary = Color(0xFF1F4E79))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
