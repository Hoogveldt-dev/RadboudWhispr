package nl.collegewhispr.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.collegewhispr.data.EngineMode
import nl.collegewhispr.data.Settings
import nl.collegewhispr.transcribe.GroqClient
import nl.collegewhispr.whisper.ModelManager
import nl.collegewhispr.whisper.WhisperLib

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by Settings.state.collectAsState()
    val installed by ModelManager.installed.collectAsState()
    val downloads by ModelManager.downloads.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) { ModelManager.poll(ctx); delay(1000) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Terug") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Groq (online, gratis)") {
                Text(
                    "Snel en het nauwkeurigst. Maak gratis een API-sleutel aan op console.groq.com. " +
                        "Het gratis plan dekt ongeveer 8 uur audio per dag.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                var key by remember { mutableStateOf(s.groqKey) }
                var show by remember { mutableStateOf(false) }
                var check by remember { mutableStateOf<String?>(null) }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); Settings.update { st -> st.copy(groqKey = key) }; check = null },
                    label = { Text("Groq API-sleutel (begint met gsk_)") },
                    singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Tonen")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
                    }) { Text("Sleutel aanmaken") }
                    Button(enabled = key.isNotBlank(), onClick = {
                        check = "Bezig met controleren…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { GroqClient.checkKey(key) }
                            check = r ?: "Sleutel werkt"
                        }
                    }) { Text("Testen") }
                }
                check?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(4.dp))
                Text("Groq-model", fontWeight = FontWeight.Bold)
                Choice("whisper-large-v3 (nauwkeurigst, aanbevolen)", s.groqModel == "whisper-large-v3") {
                    Settings.update { it.copy(groqModel = "whisper-large-v3") }
                }
                Choice("whisper-large-v3-turbo (sneller, iets minder nauwkeurig)", s.groqModel == "whisper-large-v3-turbo") {
                    Settings.update { it.copy(groqModel = "whisper-large-v3-turbo") }
                }
            }

            Section("Welke engine?") {
                EngineMode.entries.forEach { m ->
                    Choice(m.label, s.engine == m) { Settings.update { it.copy(engine = m) } }
                }
                Text(
                    "Bij \"Groq, anders offline\" valt de app automatisch terug op het offline model als er geen internet is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Offline spraakmodel") {
                Text(
                    "Werkt zonder internet, op de tablet zelf. Een college van 90 minuten kan ongeveer net zo lang " +
                        "duren om te verwerken (afhankelijk van de tablet). Houd de tablet aan de lader.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ModelManager.models.forEach { m ->
                    val dl = downloads[m.id]
                    val isInstalled = m.id in installed
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.offlineModel == m.id, onClick = {
                                Settings.update { it.copy(offlineModel = m.id) }
                            })
                            Column(Modifier.weight(1f)) {
                                Text(m.label, fontWeight = FontWeight.Bold)
                                Text("${m.description} (${m.approxMb} MB)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        when {
                            dl != null && !dl.failed -> {
                                LinearProgressIndicator(
                                    progress = { if (dl.total > 0) dl.bytes.toFloat() / dl.total else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${dl.bytes / 1_000_000} / ${dl.total / 1_000_000} MB", Modifier.weight(1f))
                                    TextButton({ ModelManager.cancel(ctx, m) }) { Text("Annuleren") }
                                }
                            }
                            isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Gedownload", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                TextButton({ ModelManager.delete(ctx, m) }) { Text("Verwijderen") }
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dl?.failed == true) Text("Download mislukt", color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                                Button({
                                    ModelManager.download(ctx, m)
                                    if (!ModelManager.isInstalled(ctx, ModelManager.vad.id) && downloads[ModelManager.vad.id] == null) {
                                        ModelManager.download(ctx, ModelManager.vad)
                                    }
                                    Settings.update { it.copy(offlineModel = m.id) }
                                }) { Text("Downloaden") }
                            }
                        }
                    }
                }
                Text(
                    "Tip: download via wifi. Stiltedetectie (1 MB) wordt automatisch meegeladen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Taal en vakjargon") {
                Choice("Nederlands", s.language == "nl") { Settings.update { it.copy(language = "nl") } }
                Choice("Engels", s.language == "en") { Settings.update { it.copy(language = "en") } }
                Choice("Automatisch herkennen", s.language == "auto") { Settings.update { it.copy(language = "auto") } }
                var vocab by remember { mutableStateOf(s.vocabulary) }
                OutlinedTextField(
                    value = vocab,
                    onValueChange = { if (it.length <= 400) { vocab = it; Settings.update { st -> st.copy(vocabulary = it) } } },
                    label = { Text("Vaktermen en namen (optioneel)") },
                    placeholder = { Text("Bijv. namen van docenten, afkortingen en vaktermen die vaak terugkomen") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Helpt Whisper bij de spelling. Kort houden: maximaal 400 tekens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Algemeen") {
                Toggle("Automatisch transcriberen na opname", s.autoTranscribe) {
                    Settings.update { st -> st.copy(autoTranscribe = it) }
                }
                Toggle("Tijdstempels in export", s.exportTimestamps) {
                    Settings.update { st -> st.copy(exportTimestamps = it) }
                }
                BatteryRow()
            }

            val info = remember { runCatching { WhisperLib.variant }.getOrDefault("niet geladen") }
            Text(
                "CollegeWhispr 1.0 · gebaseerd op OpenWhispr en whisper.cpp · engine: $info",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryRow() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val exempt = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    Column {
        Text(
            if (exempt) "Batterijoptimalisatie staat uit voor deze app (goed voor lange opnames)."
            else "Batterijoptimalisatie staat aan. Zet die uit, zodat Android lange opnames niet afbreekt.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!exempt) {
            OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.packageName)))
                }
            }) { Text("Batterijoptimalisatie uitzetten") }
        }
        TextButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }) { Text("App-instellingen openen") }
        Spacer(Modifier.size(2.dp))
    }
}
