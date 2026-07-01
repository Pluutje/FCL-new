package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context

/**
 * Eigen, AAPS-onafhankelijke opslag voor de AI-advisor instellingen.
 * Zelfde mechanisme als "fcl_expert_prefs" (eigen SharedPreferences-bestand).
 *
 * Ondersteunt twee providers: CLAUDE en GEMINI.
 * Sleutel/model per provider opgeslagen zodat wisselen tussen providers
 * geen hertypen vereist.
 */
object FclAiAdvisorSettingsStore {

    private const val PREFS_NAME = "fcl_ai_advisor_prefs"

    private const val KEY_PROVIDER      = "provider"
    private const val KEY_CLAUDE_KEY    = "claude_api_key"
    private const val KEY_CLAUDE_MODEL  = "claude_model"
    private const val KEY_GEMINI_KEY    = "gemini_api_key"
    private const val KEY_GEMINI_MODEL  = "gemini_model"

    const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

    enum class Provider { CLAUDE, GEMINI }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(context: Context): Provider =
        when (prefs(context).getString(KEY_PROVIDER, Provider.GEMINI.name)) {
            Provider.CLAUDE.name -> Provider.CLAUDE
            else                 -> Provider.GEMINI
        }

    fun setProvider(context: Context, provider: Provider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    // ── Claude ───────────────────────────────────────────────────────────

    fun getClaudeKey(context: Context): String =
        prefs(context).getString(KEY_CLAUDE_KEY, "") ?: ""

    fun setClaudeKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CLAUDE_KEY, value.trim()).apply()
    }

    fun getClaudeModel(context: Context): String =
        prefs(context).getString(KEY_CLAUDE_MODEL, DEFAULT_CLAUDE_MODEL) ?: DEFAULT_CLAUDE_MODEL

    fun setClaudeModel(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_CLAUDE_MODEL, value.trim().ifBlank { DEFAULT_CLAUDE_MODEL }).apply()
    }

    // ── Gemini ───────────────────────────────────────────────────────────

    fun getGeminiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_KEY, "") ?: ""

    fun setGeminiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_GEMINI_KEY, value.trim()).apply()
    }

    fun getGeminiModel(context: Context): String =
        prefs(context).getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL

    fun setGeminiModel(context: Context, value: String) {
        prefs(context).edit()
            .putString(KEY_GEMINI_MODEL, value.trim().ifBlank { DEFAULT_GEMINI_MODEL }).apply()
    }

    // ── Actieve sleutel/model (voor de scheduler) ─────────────────────

    fun getActiveApiKey(context: Context): String = when (getProvider(context)) {
        Provider.CLAUDE -> getClaudeKey(context)
        Provider.GEMINI -> getGeminiKey(context)
    }

    fun getActiveModel(context: Context): String = when (getProvider(context)) {
        Provider.CLAUDE -> getClaudeModel(context)
        Provider.GEMINI -> getGeminiModel(context)
    }

    fun isConfigured(context: Context): Boolean = getActiveApiKey(context).isNotBlank()
}
