package nl.collegewhispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.Settings
import nl.collegewhispr.service.ImportService
import nl.collegewhispr.service.RecorderState
import nl.collegewhispr.service.RecordingService
import nl.collegewhispr.service.TranscriptionService
import nl.collegewhispr.transcribe.TextFormat
import nl.collegewhispr.whisper.ModelManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(activity: MainActivity, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ios = LocalIos.current
    val lectures by LectureRepository.lectures.collectAsState()
    val rec by RecordingService.state.collectAsState()
    val tx by TranscriptionService.state.collectAsState()
    val imp by ImportService.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val installed by ModelManager.installed.collectAsState()
    val needsSetup = settings.keyFor(settings.cloud).isBlank() && ModelManager.models.none { it.id in installed }
    val list = rememberLazyListState()
    // Like iOS: the large title scrolls away and the bar then shows a small one.
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }

    Scaffold(
        containerColor = ios.background,
        topBar = {
            NavBar(title = if (collapsed) "Colleges" else null) {
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Instellingen", tint = ios.accent) }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { LargeTitle("Colleges") }
            if (needsSetup) item {
                Group {
                    NavRow(
                        title = "Eerst instellen",
                        subtitle = "Voeg een API-sleutel toe voor ${settings.cloud.label}. Opnemen kan nu al.",
                        onClick = onSettings,
                        leading = { IconTile(Icons.Filled.Key, ios.orange) },
                    )
                }
            }
            item { RecorderCard(activity, rec) }
            if (lectures.isEmpty()) {
                item {
                    Group {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Filled.Mic, null, tint = ios.tertiaryLabel, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Nog geen opnames", style = IosType.headline, color = ios.label)
                            Text(
                                "Tik op de opnameknop aan het begin van het college.",
                                style = IosType.footnote, color = ios.secondaryLabel, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "OPNAMES", style = IosType.footnote, color = ios.secondaryLabel,
                        modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth()
                            .padding(start = 32.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(lectures, key = { _, l -> l.id }) { i, l ->
                    Column(
                        Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(groupShape(i, lectures.size)).background(ios.card),
                    ) {
                        NavRow(
                            title = l.title,
                            subtitle = rowDate.format(Date(l.createdAt)) + " · " + TextFormat.time(l.durationMs),
                            onClick = { onOpen(l.id) },
                            below = {
                                Spacer(Modifier.height(4.dp))
                                StatusLine(l, if (tx.lectureId == l.id) tx.chunk to tx.chunks else null)
                            },
                        )
                        if (i < lectures.size - 1) Separator()
                    }
                }
            }
        }
    }

    if (imp.lectureId != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Audio importeren", style = IosType.headline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            text = {
                Column {
                    Text(
                        "Bestand wordt omgezet… ${(imp.progress * 100).toInt()}%\nDit gaat door als je het scherm vergrendelt of een andere app opent.",
                        style = IosType.footnote, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    ThinProgress(imp.progress)
                }
            },
            shape = RoundedCornerShape(14.dp),
            containerColor = ios.card,
            tonalElevation = 0.dp,
        )
    }
    imp.error?.let { err ->
        IosAlert(title = "Importeren", message = err, confirm = "OK", dismiss = null,
            onConfirm = { ImportService.clearError() }, onDismiss = { ImportService.clearError() })
    }
    activity.importError?.let { err ->
        IosAlert(title = "Let op", message = err, confirm = "OK", dismiss = null,
            onConfirm = { activity.importError = null }, onDismiss = { activity.importError = null })
    }
    rec.error?.let { err ->
        IosAlert(title = "Opname", message = err, confirm = "OK", dismiss = null,
            onConfirm = { RecordingService.clearError() }, onDismiss = { RecordingService.clearError() })
    }
}

/** Corner shape for row [i] of [n] rows that together look like one grouped card. */
fun groupShape(i: Int, n: Int): Shape {
    val r = 12.dp
    return when {
        n == 1 -> RoundedCornerShape(r)
        i == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        i == n - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/** Small rounded square with a white symbol, like the icons in iOS Settings. */
@Composable
fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun RecorderCard(activity: MainActivity, rec: RecorderState) {
    val ios = LocalIos.current
    Group {
        if (!rec.recording) {
            Column(
                Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RecordButton(recording = false) { activity.startRecording() }
                Spacer(Modifier.height(14.dp))
                Text("Opnemen", style = IosType.headline, color = ios.label)
                Text("Tik aan het begin van het college", style = IosType.footnote, color = ios.secondaryLabel)
            }
            Separator()
            ActionRow("Bestaande opname importeren", onClick = { activity.pickAudioFile() }, icon = Icons.Filled.FileUpload)
        } else {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (rec.paused) ios.secondaryLabel else ios.red))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (rec.paused) "Gepauzeerd" else "Opname loopt",
                        style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = if (rec.paused) ios.secondaryLabel else ios.red,
                    )
                }
                Text(
                    TextFormat.time(rec.elapsedMs),
                    style = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
                    color = ios.label,
                )
                ThinProgress(if (rec.paused) 0f else rec.level, color = ios.red, modifier = Modifier.widthIn(max = 280.dp))
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(ios.fill).clickable {
                            if (rec.paused) RecordingService.resume(activity) else RecordingService.pause(activity)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (rec.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            if (rec.paused) "Hervatten" else "Pauzeren", tint = ios.label,
                        )
                    }
                    RecordButton(recording = true) { RecordingService.stop(activity) }
                    Spacer(Modifier.size(56.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Je kunt het scherm uitzetten; de opname loopt door.",
                    style = IosType.footnote, color = ios.secondaryLabel, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Voice Memos-style button: red circle in a ring; a red rounded square (stop) while recording. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val ios = LocalIos.current
    Box(
        Modifier.size(78.dp).border(3.dp, ios.tertiaryLabel, CircleShape).padding(6.dp)
            .clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(ios.red))
        else Box(Modifier.fillMaxSize().clip(CircleShape).background(ios.red))
    }
}

private val rowDate = SimpleDateFormat("EEE d MMM, HH:mm", Locale("nl", "NL"))
