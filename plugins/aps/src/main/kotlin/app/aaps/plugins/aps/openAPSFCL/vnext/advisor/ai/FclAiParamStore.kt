package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import android.content.Context

/**
 * ============================================================================
 * FCL AI-Advisor — Persistente parameteropslag
 * ============================================================================
 *
 * Dit is DE enige bron van waarheid voor AI-goedgekeurde parameterwaarden.
 *
 * Architectuur (01/07/2026, de gebruiker — definitieve versie na meerdere iteraties):
 *
 *   Basiswaarden (hardcoded defaults)
 *     ↓ leert aan
 *   DFLearner (df_learner_prefs) — voor eb_factor, eb_watching_frac
 *     ↓ AI past aan indien nodig
 *   FclAiParamStore (fcl_ai_param_store) ← JE BENT HIER
 *     ↓ wordt gelezen door
 *   ConfigOverrideWriter (vóór DFLearner als fallback)
 *     ↓ post naar
 *   FclOverrideBridge → loadFCLvNextConfig() → AAPS Preferences → FCLvNext
 *
 * WAAROM dit werkt:
 *   ConfigOverrideWriter gebruikt DFLearner.getEarlyBoostFactor() als
 *   fallback. Door FclAiParamStore in die chain in te voegen als hogere
 *   prioriteit dan DFLearner, wint de AI-waarde altijd zolang de gebruiker
 *   hem niet handmatig reset. De learner kan zijn eigen prefs blijven
 *   bijwerken (dat is normaal leergedrag), maar ConfigOverrideWriter pakt
 *   altijd de AI-waarde als die bestaat.
 *
 * VOOR NIET-DFLEARNER-PARAMS (commitCooldownMinutes, lateDecayFactor, etc.):
 *   Die worden ook hier opgeslagen. loadFCLvNextConfig() schrijft ze al naar
 *   AAPS Preferences bij consumptie van de override (rule 324 v.a.); dankzij
 *   deze store worden ze ook bij de volgende ConfigOverrideWriter-run
 *   opnieuw in de override meegenomen.
 *
 * AGRESSIVITEITSSCHUIF: die past S/T/V/nfLevel aan via de normale
 * ConfigOverrideWriter-route. FclAiParamStore raakt die niet aan.
 */
object FclAiParamStore {

    private const val PREFS_NAME = "fcl_ai_param_store"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Schrijven (door AI-advisor na goedkeuring) ────────────────────────

    fun put(context: Context, key: String, value: Double) {
        prefs(context).edit().putFloat(key, value.toFloat()).apply()
    }

    fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }

    // ── Lezen (door ConfigOverrideWriter) ─────────────────────────────────

    fun getDouble(context: Context, key: String): Double? {
        val p = prefs(context)
        return if (p.contains(key)) p.getFloat(key, 0f).toDouble() else null
    }

    fun getInt(context: Context, key: String): Int? {
        val p = prefs(context)
        return if (p.contains(key)) p.getInt(key, 0) else null
    }

    // ── Wissen (voor reset-functies) ──────────────────────────────────────

    /** Wis één parameter — na reset mag DFLearner/default weer de baas zijn. */
    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    /** Wis alle AI-overrides — reset alles naar DFLearner/defaults. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ── Parametersleutels (spiegelen de FCLvNext-benaming) ────────────────

    const val K_EARLY_BOOST_FACTOR         = "earlyBoostFactor"
    const val K_WATCHING_FRONTLOAD_FRAC    = "watchingFrontloadFrac"
    const val K_WATCHING_MIN_DELTA         = "watchingMinDeltaToTarget"
    const val K_COMMIT_COOLDOWN_MINUTES    = "commitCooldownMinutes"
    const val K_EARLY_BOOST_MIN_CONFIDENCE = "earlyBoostMinConfidence"
    const val K_EARLY_BOOST_MAX_COMMITS    = "earlyBoostMaxCommits"
    const val K_EARLY_RISE_FRAC_MIN        = "earlyRiseFracMin"
    const val K_LATE_COMMIT_DECAY_FACTOR   = "lateCommitDecayFactor"
    const val K_LATE_COMMIT_DECAY_THRESHOLD= "lateCommitDecayThreshold"
    const val K_SUSTAINED_RISE_SLOPE_MIN   = "sustainedRiseSlopeMin"
    const val K_SUSTAINED_RISE_MIN_TARGET  = "sustainedRiseMinTarget"
}
