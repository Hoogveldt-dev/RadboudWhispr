package nl.collegewhispr.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EngineMode(val label: String) {
    AUTO("Groq, anders offline"),
    GROQ_ONLY("Alleen Groq (online)"),
    OFFLINE_ONLY("Alleen offline");
}

data class SettingsState(
    val groqKey: String = "",
    val groqModel: String = "whisper-large-v3",
    val engine: EngineMode = EngineMode.AUTO,
    val language: String = "nl",
    val offlineModel: String = "large-v3-turbo-q5_0",
    val vocabulary: String = "",
    val autoTranscribe: Boolean = true,
    val exportTimestamps: Boolean = true,
)

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> get() = _state
    val current: SettingsState get() = _state.value

    const val DEFAULT_PROMPT =
        "Dit is een hoorcollege aan een Nederlandse universiteit, met correcte interpunctie en hoofdletters."

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _state.value = SettingsState(
            groqKey = prefs.getString("groqKey", "") ?: "",
            groqModel = prefs.getString("groqModel", "whisper-large-v3") ?: "whisper-large-v3",
            engine = runCatching { EngineMode.valueOf(prefs.getString("engine", "AUTO")!!) }
                .getOrDefault(EngineMode.AUTO),
            language = prefs.getString("language", "nl") ?: "nl",
            offlineModel = prefs.getString("offlineModel", "large-v3-turbo-q5_0") ?: "large-v3-turbo-q5_0",
            vocabulary = prefs.getString("vocabulary", "") ?: "",
            autoTranscribe = prefs.getBoolean("autoTranscribe", true),
            exportTimestamps = prefs.getBoolean("exportTimestamps", true),
        )
    }

    fun update(block: (SettingsState) -> SettingsState) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putString("groqKey", s.groqKey.trim())
            .putString("groqModel", s.groqModel)
            .putString("engine", s.engine.name)
            .putString("language", s.language)
            .putString("offlineModel", s.offlineModel)
            .putString("vocabulary", s.vocabulary)
            .putBoolean("autoTranscribe", s.autoTranscribe)
            .putBoolean("exportTimestamps", s.exportTimestamps)
            .apply()
    }

    /** Prompt sent to Whisper: a style hint plus the user's own terms. */
    fun basePrompt(): String {
        val s = current
        val vocab = s.vocabulary.trim()
        val style = if (s.language == "nl") DEFAULT_PROMPT else ""
        return listOf(style, vocab).filter { it.isNotBlank() }.joinToString(" ")
    }
}
