package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * Schrijft FCLvNext_active_params.json naar Documents/AAPS/ANALYSE/ na elke
 * loadFCLvNextConfig() aanroep.
 *
 * Dit bestand bevat de zes Groep A parameters zoals ze WERKELIJK actief zijn
 * in AAPS — dus NA de volledige as-keten én NA applyParamOverrides.
 * De FCL Analyzer leest dit bestand als bron van waarheid.
 *
 * Schrijfgedrag:
 * - Alleen herschrijven als minstens één waarde veranderd is t.o.v. de vorige cyclus.
 * - Altijd met Locale.US voor decimale punten (geen komma's).
 * - Nooit een crash veroorzaken: alle I/O is in try-catch gewikkeld.
 *
 * Aanroep in FCLvNextConfig.kt, direct na de apply-keten:
 *   val finalConfig = base
 *       .let { applyProfileDoseStrength(it) }
 *       ...
 *       .let { applyParamOverrides(it, override?.paramOverrides) }
 *   FCLvNextActiveParamsWriter.writeIfChanged(finalConfig)
 *   return finalConfig
 */
object FCLvNextActiveParamsWriter {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILENAME       = "FCLvNext_active_params.json"

    // Defaults zoals gedefinieerd in FCLvNextConfig base-blok.
    // Worden meegeschreven zodat de analyzer de afwijking kan tonen.
    object Defaults {
        const val PEAK_PREDICTION_THRESHOLD   = 12.5
        const val WATCHING_FRONTLOAD_FRAC     = 0.65
        const val WATCHING_MIN_DELTA_TARGET   = 2.0
        const val COMMIT_COOLDOWN_MINUTES     = 15
        const val PEAK_PREDICTION_HORIZON_H   = 1.2
        const val IOB_START                   = 0.40
    }

    // Cache van de vorige geschreven waarden — voorkomt onnodige I/O
    @Volatile private var lastPeakThreshold:   Double? = null
    @Volatile private var lastFrontloadFrac:   Double? = null
    @Volatile private var lastDeltaToTarget:   Double? = null
    @Volatile private var lastCooldown:        Int?    = null
    @Volatile private var lastHorizonH:        Double? = null
    @Volatile private var lastIobStart:        Double? = null
    @Volatile private var lastMealHandling:    String? = null
    @Volatile private var lastMealDetectSpeed: String? = null

    /**
     * Schrijf de actieve params als minstens één waarde veranderd is.
     * Aanroepen met de final FCLvNextConfig na de volledige keten.
     */
    fun writeIfChanged(config: FCLvNextConfig) {
        val changed =
            config.peakPredictionThreshold  != lastPeakThreshold   ||
                config.watchingFrontloadFrac     != lastFrontloadFrac   ||
                config.watchingMinDeltaToTarget  != lastDeltaToTarget   ||
                config.commitCooldownMinutes     != lastCooldown        ||
                config.peakPredictionHorizonH    != lastHorizonH        ||
                config.iobStart                  != lastIobStart        ||
                config.mealHandlingStyle         != lastMealHandling    ||
                config.mealDetectSpeed           != lastMealDetectSpeed

        if (!changed) return

        write(config)

        lastPeakThreshold   = config.peakPredictionThreshold
        lastFrontloadFrac   = config.watchingFrontloadFrac
        lastDeltaToTarget   = config.watchingMinDeltaToTarget
        lastCooldown        = config.commitCooldownMinutes
        lastHorizonH        = config.peakPredictionHorizonH
        lastIobStart        = config.iobStart
        lastMealHandling    = config.mealHandlingStyle
        lastMealDetectSpeed = config.mealDetectSpeed
    }

    private fun write(config: FCLvNextConfig) {
        try {
            val dir = File(
                Environment.getExternalStorageDirectory(),
                RELATIVE_PATH
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, FILENAME)
            file.writeText(buildJson(config), Charsets.UTF_8)
        } catch (_: Exception) {
            // Nooit een crash veroorzaken in het algoritme-pad
        }
    }

    private fun fmt(d: Double): String =
        String.format(Locale.US, "%.4f", d)

    private fun fmtDisplay(d: Double, default: Double): String {
        val diff = d - default
        return when {
            diff > 0.001  -> "+${String.format(Locale.US, "%.4f", diff)}"
            diff < -0.001 -> String.format(Locale.US, "%.4f", diff)
            else          -> "0"
        }
    }

    private fun buildJson(config: FCLvNextConfig): String {
        val now = java.time.Instant.now().toString()

        // Bepaal bron per parameter
        fun src(active: Double, default: Double): String =
            if (kotlin.math.abs(active - default) < 0.001) "default" else "modified"
        fun srcInt(active: Int, default: Int): String =
            if (active == default) "default" else "modified"

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": 1,")
            appendLine("  \"written_at\": \"$now\",")
            appendLine("  \"as_context\": {")
            appendLine("    \"mealHandlingStyle\": \"${config.mealHandlingStyle}\",")
            appendLine("    \"mealDetectSpeed\": \"${config.mealDetectSpeed}\",")
            appendLine("    \"correctionStyle\": \"${config.correctionStyle}\",")
            appendLine("    \"hypoProtectionStyle\": \"${config.hypoProtectionStyle}\"")
            appendLine("  },")
            appendLine("  \"params\": {")
            appendLine("    \"peakPredictionThreshold\":  { \"active\": ${fmt(config.peakPredictionThreshold)},  \"default\": ${fmt(Defaults.PEAK_PREDICTION_THRESHOLD)},  \"delta\": \"${fmtDisplay(config.peakPredictionThreshold,  Defaults.PEAK_PREDICTION_THRESHOLD)}\",  \"src\": \"${src(config.peakPredictionThreshold,  Defaults.PEAK_PREDICTION_THRESHOLD)}\" },")
            appendLine("    \"watchingFrontloadFrac\":    { \"active\": ${fmt(config.watchingFrontloadFrac)},    \"default\": ${fmt(Defaults.WATCHING_FRONTLOAD_FRAC)},    \"delta\": \"${fmtDisplay(config.watchingFrontloadFrac,    Defaults.WATCHING_FRONTLOAD_FRAC)}\",    \"src\": \"${src(config.watchingFrontloadFrac,    Defaults.WATCHING_FRONTLOAD_FRAC)}\" },")
            appendLine("    \"watchingMinDeltaToTarget\": { \"active\": ${fmt(config.watchingMinDeltaToTarget)}, \"default\": ${fmt(Defaults.WATCHING_MIN_DELTA_TARGET)}, \"delta\": \"${fmtDisplay(config.watchingMinDeltaToTarget, Defaults.WATCHING_MIN_DELTA_TARGET)}\", \"src\": \"${src(config.watchingMinDeltaToTarget, Defaults.WATCHING_MIN_DELTA_TARGET)}\" },")
            appendLine("    \"commitCooldownMinutes\":    { \"active\": ${config.commitCooldownMinutes},          \"default\": ${Defaults.COMMIT_COOLDOWN_MINUTES},          \"delta\": \"${fmtDisplay(config.commitCooldownMinutes.toDouble(), Defaults.COMMIT_COOLDOWN_MINUTES.toDouble())}\",  \"src\": \"${srcInt(config.commitCooldownMinutes, Defaults.COMMIT_COOLDOWN_MINUTES)}\" },")
            appendLine("    \"peakPredictionHorizonH\":   { \"active\": ${fmt(config.peakPredictionHorizonH)},   \"default\": ${fmt(Defaults.PEAK_PREDICTION_HORIZON_H)},   \"delta\": \"${fmtDisplay(config.peakPredictionHorizonH,   Defaults.PEAK_PREDICTION_HORIZON_H)}\",   \"src\": \"${src(config.peakPredictionHorizonH,   Defaults.PEAK_PREDICTION_HORIZON_H)}\" },")
            append("    \"iobStart\":                 { \"active\": ${fmt(config.iobStart)},                 \"default\": ${fmt(Defaults.IOB_START)},                 \"delta\": \"${fmtDisplay(config.iobStart,                 Defaults.IOB_START)}\",                 \"src\": \"${src(config.iobStart,                 Defaults.IOB_START)}\" }")
            appendLine()
            appendLine("  }")
            append("}")
        }
    }
}
