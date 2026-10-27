package nl.collegewhispr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.collegewhispr.data.Lecture
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.data.Settings
import nl.collegewhispr.service.RecordingService
import nl.collegewhispr.service.TranscriptionService
import nl.collegewhispr.transcribe.TextFormat
import nl.collegewhispr.whisper.ModelManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(activity: MainActivity, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val lectures by LectureRepository.lectures.collectAsState()
    val rec by RecordingService.state.collectAsState()
    val tx by TranscriptionService.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val installed by ModelManager.installed.collectAsState()
    val needsSetup = settings.groqKey.isBlank() && ModelManager.models.none { it.id in installed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CollegeWhispr") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Instellingen") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (needsSetup) item {
                Card(
                    Modifier.fillMaxWidth().widthIn(max = 720.dp).clickable(onClick = onSettings),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Eerst instellen", fontWeight = FontWeight.Bold)
                        Text("Voeg een gratis Groq API-sleutel toe en/of download een offline spraakmodel. Opnemen kan nu al.")
                    }
                }
            }
            item { RecorderCard(activity, rec) }
            items(lectures, key = { it.id }) { l ->
                LectureRow(l, if (tx.lectureId == l.id) tx.chunk to tx.chunks else null) { onOpen(l.id) }
            }
            if (lectures.isEmpty()) item {
                Text(
                    "Nog geen opnames. Tik op Opnemen aan het begin van het college.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }

    activity.importProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Audio importeren") },
            text = {
                Column {
                    Text("Bestand wordt omgezet…")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }
    activity.importError?.let { err ->
        AlertDialog(
            onDismissRequest = { activity.importError = null },
            confirmButton = { TextButton({ activity.importError = null }) { Text("OK") } },
            text = { Text(err) },
        )
    }
    rec.error?.let { err ->
        AlertDialog(
            onDismissRequest = { RecordingService.clearError() },
            confirmButton = { TextButton({ RecordingService.clearError() }) { Text("OK") } },
            text = { Text(err) },
        )
    }
}

@Composable
private fun RecorderCard(activity: MainActivity, rec: nl.collegewhispr.service.RecorderState) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!rec.recording) {
                Button(
                    onClick = { activity.startRecording() },
                    shape = CircleShape,
                    modifier = Modifier.size(120.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(48.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Opnemen", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { activity.pickAudioFile() }) {
                    Icon(Icons.Filled.FileUpload, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Bestaande opname importeren")
                }
            } else {
                Text(
                    if (rec.paused) "Gepauzeerd" else "Opname loopt",
                    color = if (rec.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                Text(TextFormat.time(rec.elapsedMs), fontSize = 56.sp, fontWeight = FontWeight.Light)
                LinearProgressIndicator(
                    progress = { if (rec.paused) 0f else rec.level },
                    modifier = Modifier.fillMaxWidth(0.6f).height(8.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        if (rec.paused) RecordingService.resume(activity) else RecordingService.pause(activity)
                    }) {
                        Icon(if (rec.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (rec.paused) "Hervatten" else "Pauzeren")
                    }
                    Button(
                        onClick = { RecordingService.stop(activity) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.size(6.dp))
                        Text("Stoppen")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Je kunt het scherm uitzetten; de opname loopt door.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val rowDate = SimpleDateFormat("EEE d MMM, HH:mm", Locale("nl", "NL"))

@Composable
private fun LectureRow(l: Lecture, chunkInfo: Pair<Int, Int>?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(l.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                rowDate.format(Date(l.createdAt)) + " · " + TextFormat.time(l.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            StatusLine(l, chunkInfo)
        }
    }
}

@Composable
fun StatusLine(l: Lecture, chunkInfo: Pair<Int, Int>?) {
    val color = when (l.status) {
        LectureStatus.DONE -> MaterialTheme.colorScheme.primary
        LectureStatus.ERROR, LectureStatus.WAITING -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val extra = if (l.status == LectureStatus.TRANSCRIBING && chunkInfo != null && chunkInfo.second > 0)
        " (deel ${chunkInfo.first} van ${chunkInfo.second}, ${(l.progress * 100).toInt()}%)" else ""
    Text(l.status.label + extra, color = color, style = MaterialTheme.typography.labelLarge)
    if (l.status == LectureStatus.TRANSCRIBING) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { l.progress }, modifier = Modifier.fillMaxWidth())
    }
    l.message?.let {
        if (l.status != LectureStatus.DONE) Text(it, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
