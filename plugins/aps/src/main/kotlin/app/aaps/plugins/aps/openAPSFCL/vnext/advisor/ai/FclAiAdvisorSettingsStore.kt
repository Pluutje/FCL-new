package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context

/**
 * FCL AI-Advisor Settings — model-selectie + dual API-keys per model.
 *
 * (01/07/2026, Ecko): uitgebreid met:
 *  - Hardcoded model-lijst per provider (uitbreidbaar zonder UI-aanpassing)
 *  - Twee API-keys per model — eerste geprobeerd, bij fout automatisch tweede
 *  - Lege key-velden worden overgeslagen
 */
object FclAiAdvisorSettingsStore {

    private const val PREFS_NAME = "fcl_ai_advisor_prefs"

    private const val KEY_SELECTED_MODEL = "selected_model"

    // ── Modellen (hardcoded lijst, te zijner tijd uitbreidbaar) ──────────────

    data class ModelOption(
        val id: String,         // API-model-string
        val displayName: String,
        val provider: Provider
    )

    enum class Provider { CLAUDE, GEMINI }

    val MODELS: List<ModelOption> = listOf(
        // Claude
        ModelOption("claude-haiku-4-5-20251001", "→ Claude Haiku 4.5", Provider.CLAUDE),
        ModelOption("claude-sonnet-4-6",  "2 Claude Sonnet 4.6",  Provider.CLAUDE),
        ModelOption("claude-opus-4-6",    "3 Claude Opus 4.6",    Provider.CLAUDE),

        // Gemini
        ModelOption("gemini-3.5-flash",   "→ Gemini 3.5", Provider.GEMINI),
        ModelOption("gemini-3.0-flash",   "2 Gemini 3.0",   Provider.GEMINI),
        ModelOption("gemini-2.5-flash",     "3 Gemini 2.5",     Provider.GEMINI),
    )

    val DEFAULT_MODEL_ID = "gemini-3.5-flash"

    // ── Aan/uit-schakelaar (10/07/2026, Ecko) ────────────────────────────────
    // Mirroring DFLearner.isAutoEnabled/setAutoEnabled — zelfde patroon, zodat
    // Learner en AI-adviseur onafhankelijk aan/uit kunnen, zoals besproken.
    // Default TRUE: bestaand gedrag (AI staat al aan) blijft ongewijzigd voor
    // wie deze instelling nooit aanraakt.
    //
    // BELANGRIJK — wat "uit" NIET doet: FclAiParamStore wordt NIET geleegd.
    // Eerder goedgekeurde AI-waarden blijven gewoon staan; alleen worden ze
    // niet langer met prioriteit toegepast (zie ConfigOverrideWriter), en
    // stopt de scheduler met nieuwe voorstellen genereren. Zo geen abrupte
    // sprong in de dosering bij het uitzetten — de Learner's tracked-waarden
    // (zie DFLearner.getTrackedParam) staan door de sync-koppeling al op de
    // laatst actieve stand en kunnen van daaruit geleidelijk verder evalueren.
    private const val KEY_ENABLED = "ai_advisor_enabled"

    fun isEnabled(context: Context): Boolean =
        getMode(context) != app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF

    /** Legacy setter — nieuwe code: setMode(). Zet OFF/MANUAL, nooit automatisch
     *  AUTO: bestaand gedrag was altijd "wacht op goedkeuring", dat blijft zo
     *  totdat de gebruiker zelf expliciet AUTO kiest. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        setMode(context, if (enabled) app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
                          else app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Mode-schakeling (10/07/2026, Ecko) — zie FclSystemMode.kt.
    // ═══════════════════════════════════════════════════════════════════════
    private const val KEY_MODE = "fcl_ai_advisor_mode"

    /**
     * Backward-compat: bestaande installaties hebben nog geen KEY_MODE, alleen
     * het oude KEY_ENABLED-boolean (default true). Het bestaande gedrag bij
     * enabled=true was ALTIJD "wacht op expliciete goedkeuring" — er bestond
     * nog geen automatisch-toepassen-modus — dus de eerlijke default-mapping
     * is MANUAL, niet AUTO. AUTO is puur een nieuwe, expliciete keuze.
     */
    fun getMode(context: Context): app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode {
        val stored = prefs(context).getString(KEY_MODE, null)
        if (stored != null) return app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.fromStored(stored)
        return if (prefs(context).getBoolean(KEY_ENABLED, true))
            app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.MANUAL
        else
            app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode.OFF
    }

    fun setMode(context: Context, mode: app.aaps.plugins.aps.openAPSFCL.vnext.FclSystemMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Model-selectie ────────────────────────────────────────────────────────

    fun getSelectedModel(context: Context): ModelOption =
        MODELS.find { it.id == prefs(context).getString(KEY_SELECTED_MODEL, DEFAULT_MODEL_ID) }
            ?: MODELS.first { it.id == DEFAULT_MODEL_ID }

    fun setSelectedModel(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun getProvider(context: Context): Provider = getSelectedModel(context).provider

    // ── API-keys (2 per model, opgeslagen als "key1_<modelId>" / "key2_<modelId>") ──

    private fun keyPref1(modelId: String) = "key1_${modelId.replace("-", "_")}"
    private fun keyPref2(modelId: String) = "key2_${modelId.replace("-", "_")}"

    fun getKey1(context: Context, modelId: String): String =
        prefs(context).getString(keyPref1(modelId), "") ?: ""

    fun getKey2(context: Context, modelId: String): String =
        prefs(context).getString(keyPref2(modelId), "") ?: ""

    fun setKey1(context: Context, modelId: String, value: String) {
        prefs(context).edit().putString(keyPref1(modelId), value.trim()).apply()
    }

    fun setKey2(context: Context, modelId: String, value: String) {
        prefs(context).edit().putString(keyPref2(modelId), value.trim()).apply()
    }

    /** Geeft de actieve keys terug (niet-lege, in volgorde). */
    fun getActiveKeys(context: Context): List<String> {
        val model = getSelectedModel(context)
        return listOf(getKey1(context, model.id), getKey2(context, model.id))
            .filter { it.isNotBlank() }
    }

    fun isConfigured(context: Context): Boolean = getActiveKeys(context).isNotEmpty()

    // ── Legacy-getters voor backward compat (Scheduler gebruikt getActiveApiKey) ──

    fun getActiveApiKey(context: Context): String = getActiveKeys(context).firstOrNull() ?: ""
    fun getActiveModel(context: Context): String = getSelectedModel(context).id
}
