package nl.collegewhispr.ui

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.collegewhispr.data.CloudProvider
import nl.collegewhispr.data.EngineMode
import nl.collegewhispr.data.LectureRepository
import nl.collegewhispr.data.LectureStatus
import nl.collegewhispr.service.TranscriptionService
import nl.collegewhispr.transcribe.Paragraph
import nl.collegewhispr.transcribe.TextFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lectures by LectureRepository.lectures.collectAsState()
    val tx by TranscriptionService.state.collectAsState()
    val lecture = lectures.firstOrNull { it.id == id }
    if (lecture == null) { LaunchedEffect(Unit) { onBack() }; return }

    // Earlier transcripts (kept when re-transcribing) to compare engines; null shows the current one.
    var versionsTick by remember { mutableIntStateOf(0) }
    val versions = remember(lecture.status, versionsTick) { LectureRepository.versions(id) }
    var picked by remember { mutableStateOf<String?>(null) }
    val version = picked?.takeIf { it in versions }
    // Re-read segments whenever progress or status changes.
    val paras = remember(lecture.status, lecture.progress, version) {
        TextFormat.paragraphs(version?.let { LectureRepository.versionSegments(id, it) } ?: LectureRepository.segments(id))
    }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    // --- audio player ---
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(0f) }
    val recording = lecture.status == LectureStatus.RECORDING
    DisposableEffect(id, lecture.hasAudio, recording) {
        val f = LectureRepository.audio(id)
        player = if (lecture.hasAudio && f.exists() && !recording) {
            runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull()
        } else null
        onDispose { player?.release(); player = null; playing = false }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { position = it.currentPosition.toFloat() }
            if (player?.isPlaying != true) playing = false
            delay(250)
        }
    }
    fun seekPlay(ms: Long) {
        player?.let { it.seekTo(ms.toInt()); it.start(); playing = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lecture.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Filled.Search, "Zoeken")
                    }
                    IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Naam wijzigen") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Meer") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        CloudProvider.entries.forEach { p ->
                            DropdownMenuItem(text = { Text("Opnieuw transcriberen met ${p.label}") }, enabled = lecture.hasAudio, onClick = {
                                menu = false; picked = null
                                TranscriptionService.enqueue(ctx, id, reset = true, engine = EngineMode.ONLINE_ONLY, provider = p)
                            })
                        }
                        DropdownMenuItem(text = { Text("Opnieuw transcriberen (offline)") }, enabled = lecture.hasAudio, onClick = {
                            menu = false; picked = null
                            TranscriptionService.enqueue(ctx, id, reset = true, engine = EngineMode.OFFLINE_ONLY)
                        })
                        DropdownMenuItem(text = { Text("Opname delen (.wav)") }, enabled = lecture.hasAudio, onClick = {
                            menu = false; Export.shareAudio(ctx, lecture)
                        })
                        DropdownMenuItem(text = { Text("Delen als Markdown") }, enabled = paras.isNotEmpty(), onClick = {
                            menu = false; Export.share(ctx, lecture, paras, md = true)
                        })
                        DropdownMenuItem(
                            text = { Text("Audio verwijderen (tekst blijft)") },
                            enabled = lecture.hasAudio && lecture.status == LectureStatus.DONE,
                            onClick = { menu = false; player?.release(); player = null; LectureRepository.deleteAudio(id) },
                        )
                        DropdownMenuItem(text = { Text("College verwijderen") }, onClick = { menu = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = {
            player?.let { p ->
                BottomAppBar {
                    IconButton(onClick = {
                        if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
                    }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Afspelen") }
                    Text(TextFormat.time(position.toLong()), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = position,
                        onValueChange = { position = it; p.seekTo(it.toInt()) },
                        valueRange = 0f..maxOf(1f, p.duration.toFloat()),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text(TextFormat.time(p.duration.toLong()), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.size(12.dp))
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 820.dp)) {
                    Text(Export.subtitle(lecture), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lecture.engines?.let {
                        Text("Getranscribeerd met: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    StatusLine(lecture, if (tx.lectureId == id) tx.chunk to tx.chunks else null)
                    if (versions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Versies om te vergelijken", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = version == null, onClick = { picked = null },
                                label = { Text("Nu: " + (lecture.engines ?: lecture.status.label)) },
                            )
                            versions.asReversed().forEach { v ->
                                FilterChip(selected = version == v, onClick = { picked = v }, label = { Text(v) })
                            }
                        }
                        version?.let { v ->
                            TextButton(onClick = { LectureRepository.deleteVersion(id, v); picked = null; versionsTick++ }) {
                                Text("Deze versie verwijderen")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (lecture.status) {
                            LectureStatus.TRANSCRIBING, LectureStatus.QUEUED ->
                                OutlinedButton(onClick = { TranscriptionService.cancel(ctx, id) }) { Text("Stoppen") }
                            LectureStatus.RECORDED, LectureStatus.WAITING, LectureStatus.ERROR ->
                                if (lecture.hasAudio) Button(onClick = { TranscriptionService.enqueue(ctx, id) }) {
                                    Text(if (lecture.status == LectureStatus.RECORDED) "Transcriberen" else "Opnieuw proberen")
                                }
                            else -> {}
                        }
                    }
                    if (paras.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {
                                Export.copy(ctx, lecture, paras)
                                Toast.makeText(ctx, "Gekopieerd", Toast.LENGTH_SHORT).show()
                            }, label = { Text("Kopiëren") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                            AssistChip(onClick = { Export.share(ctx, lecture, paras, md = false) },
                                label = { Text("Delen") }, leadingIcon = { Icon(Icons.Filled.Share, null) })
                            AssistChip(onClick = { saveMenu = true },
                                label = { Text("Opslaan") }, leadingIcon = { Icon(Icons.Filled.Download, null) })
                            DropdownMenu(saveMenu, onDismissRequest = { saveMenu = false }) {
                                listOf(false to "Als tekst (.txt)", true to "Als Markdown (.md)").forEach { (md, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        saveMenu = false
                                        val r = Export.saveToDownloads(ctx, lecture, paras, md)
                                        Toast.makeText(ctx, r?.let { "Opgeslagen in $it" } ?: "Gebruik Delen op deze Android-versie",
                                            Toast.LENGTH_LONG).show()
                                    })
                                }
                            }
                        }
                    }
                    if (searching) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            label = { Text("Zoeken in transcriptie") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }
            val shown = if (query.isBlank()) paras else paras.filter { it.text.contains(query, ignoreCase = true) }
            if (searching && query.isNotBlank()) item {
                Text("${shown.size} alinea's gevonden", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 820.dp).padding(vertical = 8.dp))
            }
            items(shown) { p -> ParagraphView(p, query, canPlay = player != null) { seekPlay(p.startMs) } }
        }
    }

    if (rename) {
        var title by remember { mutableStateOf(lecture.title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Naam wijzigen") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    if (title.isNotBlank()) LectureRepository.update(id) { it.copy(title = title.trim()) }
                    rename = false
                }) { Text("Opslaan") }
            },
            dismissButton = { TextButton({ rename = false }) { Text("Annuleren") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("College verwijderen?") },
            text = { Text("De opname en de transcriptie worden definitief verwijderd.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (lecture.status == LectureStatus.QUEUED || lecture.status == LectureStatus.TRANSCRIBING) {
                        TranscriptionService.cancel(ctx, id)
                    }
                    player?.release(); player = null
                    LectureRepository.delete(id)
                    onBack()
                }) { Text("Verwijderen") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuleren") } },
        )
    }
}

@Composable
private fun ParagraphView(p: Paragraph, query: String, canPlay: Boolean, onPlay: () -> Unit) {
    Column(Modifier.fillMaxWidth().widthIn(max = 820.dp).padding(vertical = 8.dp)) {
        TextButton(onClick = onPlay, enabled = canPlay, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(TextFormat.time(p.startMs), style = MaterialTheme.typography.labelMedium)
            if (canPlay) Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
        }
        SelectionContainer {
            Text(
                highlight(p.text, query),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f),
            )
        }
    }
}

@Composable
private fun highlight(text: String, query: String) = buildAnnotatedString {
    if (query.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    val lower = text.lowercase()
    val q = query.lowercase()
    while (i < text.length) {
        val j = lower.indexOf(q, i)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        withStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, fontWeight = FontWeight.Bold)) {
            append(text.substring(j, j + q.length))
        }
        i = j + q.length
    }
}
