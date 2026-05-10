package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import android.content.Context
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import kotlin.math.abs

/**
 * Leert de rescue-detector-drempels bij op basis van gebruikersbevestigingen.
 *
 * Drie leersignalen:
 * 1. TRUE POSITIVE:  gebruiker=YES + auto=CONFIRMED → drempels goed, kleine verstrakking
 * 2. FALSE NEGATIVE: gebruiker=YES + auto!=CONFIRMED → detector miste het, drempels versoepelen
 * 3. FALSE POSITIVE: gebruiker=NO  + auto=CONFIRMED → fout alarm, drempels aanscherpen
 *
 * Geleerde drempels worden opgeslagen in SharedPreferences en gebruikt door FCLvNext
 * via RescueLearnerBridge (analoog aan DFLearner).
 */
object RescueLearner {

    private const val PREFS_NAME = "rescue_learner_prefs"

    // Sleutels voor geleerde drempels
    private const val KEY_ARM_IOB_RATIO  = "rescue_arm_iob_ratio"   // min IOB-ratio om te armen
    private const val KEY_ARM_SLOPE      = "rescue_arm_slope"        // max slope (negatief) om te armen
    private const val KEY_ARM_BG         = "rescue_arm_bg"           // max BG om te armen
    private const val KEY_CONFIDENCE_MIN = "rescue_conf_min"         // min confidence voor CONFIRM
    private const val KEY_LEARN_COUNT    = "rescue_learn_count"      // aantal leerstappen gedaan

    // Startwaarden (conservatief — zelfde als huidige hardcoded waarden in FCLvNext)
    const val DEFAULT_ARM_IOB_RATIO  = 0.25
    const val DEFAULT_ARM_SLOPE      = -0.9   // mmol/5min
    const val DEFAULT_ARM_BG         = 5.2    // mmol/L
    const val DEFAULT_CONFIDENCE_MIN = 0.35

    // Leersnelheid — kleine stapjes
    private const val STEP_IOB_RATIO  = 0.03
    private const val STEP_SLOPE      = 0.10
    private const val STEP_BG         = 0.20
    private const val MIN_EPISODES_TO_LEARN = 3  // minimaal N bevestigingen voor eerste aanpassing

    data class LearnedThresholds(
        val armIobRatio:  Double,
        val armSlope:     Double,
        val armBg:        Double,
        val confidenceMin: Double,
        val learnCount:   Int
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThresholds(context: Context): LearnedThresholds = LearnedThresholds(
        armIobRatio   = prefs(context).getFloat(KEY_ARM_IOB_RATIO,  DEFAULT_ARM_IOB_RATIO.toFloat()).toDouble(),
        armSlope      = prefs(context).getFloat(KEY_ARM_SLOPE,      DEFAULT_ARM_SLOPE.toFloat()).toDouble(),
        armBg         = prefs(context).getFloat(KEY_ARM_BG,         DEFAULT_ARM_BG.toFloat()).toDouble(),
        confidenceMin = prefs(context).getFloat(KEY_CONFIDENCE_MIN, DEFAULT_CONFIDENCE_MIN.toFloat()).toDouble(),
        learnCount    = prefs(context).getInt(KEY_LEARN_COUNT, 0)
    )

    /**
     * Verwerk bevestigde episodes en pas drempels aan.
     * Wordt aangeroepen vanuit FclAnalyzerScreen na elke refreshData().
     */
    fun learn(
        context: Context,
        confirmed: List<EpisodeEntity>,   // gebruiker=YES
        falsePositives: List<EpisodeEntity>, // gebruiker=NO + auto=CONFIRMED
        missed: List<EpisodeEntity>       // gebruiker=YES + auto!=CONFIRMED
    ) {
        if (confirmed.size + falsePositives.size + missed.size < MIN_EPISODES_TO_LEARN) return

        val current = getThresholds(context)
        var iobRatio  = current.armIobRatio
        var slope     = current.armSlope
        var bg        = current.armBg
        var confMin   = current.confidenceMin

        // ── FALSE NEGATIVES: detector miste echte rescue ──────────────────
        // Versoepel drempels op basis van de ARM-context van gemiste episodes
        if (missed.isNotEmpty()) {
            val avgMissedIob  = missed.mapNotNull { it.rescueArmedIobRatio.takeIf { v -> v > 0.01 } }.average()
            val avgMissedSlope = missed.mapNotNull { it.rescueArmedSlope.takeIf { v -> v < -0.05 } }.average()
            val avgMissedBg   = missed.mapNotNull { it.rescueArmedBg.takeIf { v -> v > 2.0 } }.average()

            // Als gemiste episodes lagere IOB hadden dan drempel → drempel verlagen
            if (!avgMissedIob.isNaN() && avgMissedIob < iobRatio - 0.01)
                iobRatio = (iobRatio - STEP_IOB_RATIO).coerceAtLeast(0.10)

            // Als gemiste episodes minder steile daling hadden → minder strenge slope
            if (!avgMissedSlope.isNaN() && avgMissedSlope > slope + 0.05)
                slope = (slope + STEP_SLOPE).coerceAtMost(-0.2)

            // Als gemiste episodes hogere BG hadden → hogere BG-drempel toestaan
            if (!avgMissedBg.isNaN() && avgMissedBg > bg + 0.1)
                bg = (bg + STEP_BG).coerceAtMost(8.0)

            // Confidence minimum verlagen zodat meer situaties CONFIRMED worden
            confMin = (confMin - 0.03).coerceAtLeast(0.20)
        }

        // ── FALSE POSITIVES: fout alarm ───────────────────────────────────
        // Verscherp drempels
        if (falsePositives.isNotEmpty()) {
            iobRatio  = (iobRatio  + STEP_IOB_RATIO).coerceAtMost(0.60)
            slope     = (slope     - STEP_SLOPE).coerceAtLeast(-2.0)
            bg        = (bg        - STEP_BG).coerceAtLeast(3.5)
            confMin   = (confMin   + 0.03).coerceAtMost(0.70)
        }

        // ── TRUE POSITIVES: drempels licht verstrakken richting optimum ──
        if (confirmed.size >= 5) {
            val avgConf = confirmed.mapNotNull { it.rescueAutoConfidence.takeIf { v -> v > 0.01 } }.average()
            if (!avgConf.isNaN() && avgConf > 0.6) {
                // Hoge gemiddelde confidence → detectie werkt goed, kleine verstrakking
                confMin = (confMin + 0.01).coerceAtMost(0.70)
            }
        }

        // Sla op
        prefs(context).edit()
            .putFloat(KEY_ARM_IOB_RATIO,  iobRatio.toFloat())
            .putFloat(KEY_ARM_SLOPE,      slope.toFloat())
            .putFloat(KEY_ARM_BG,         bg.toFloat())
            .putFloat(KEY_CONFIDENCE_MIN, confMin.toFloat())
            .putInt(KEY_LEARN_COUNT,      current.learnCount + 1)
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}