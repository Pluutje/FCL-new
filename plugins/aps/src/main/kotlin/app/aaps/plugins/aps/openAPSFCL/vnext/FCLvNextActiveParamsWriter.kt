package app.aaps.plugins.aps.openAPSFCL.vnext

import android.os.Environment
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import java.io.File
import java.time.Instant

/**
 * Schrijft FCLvNext_active_params.json naar Documents/AAPS/ANALYSE/ na elke cyclus.
 * Alleen als een waarde veranderd is (cache-vergelijking).
 *
 * Schema 2 uitvoer:
 * {
 *   "schema": 2,
 *   "written_at": "...",
 *   "stv": { "sterkte": 95, "timing": 108, "volhoudendheid": 88, "nacht_factor": 82 },
 *   "params": {
 *     "peakPredictionThreshold":  { "active": 11.50, "default": 12.50, "delta": "-1.00", "src": "override" },
 *     ...
 *   }
 * }
 */
object FCLvNextActiveParamsWriter {

    private const val RELATIVE_PATH = "Documents/AAPS/ANALYSE"
    private const val FILENAME       = "FCLvNext_active_params.json"

    object Defaults {
        // S/T/V/N
        const val STERKTE        = 100
        const val TIMING         = 100
        const val VOLHOUDENDHEID = 100
        const val NACHT_FACTOR   = 85
        // Groep-A params — gelijk aan DFMapping-referentie bij D=1.0, F=0.50.
        // Dit is de enige bron van default-kennis binnen AAPS voor active_params.json.
        // ConfigOverrideWriter.Defaults (Analyzer) en DoubleKey/IntKey defaultValues
        // moeten hieraan identiek zijn.
        const val PEAK_PREDICTION_THRESHOLD   = 12.5
        const val WATCHING_FRONTLOAD_FRAC     = 0.64  // was 0.65 (= REF_WFF in DFMapping)
        const val WATCHING_MIN_DELTA_TARGET   = 1.50  // was 2.0  (= REF_WMD in DFMapping)
        const val COMMIT_COOLDOWN_MINUTES     = 13    // was 15   (= REF_CC  in DFMapping)
        const val PEAK_PREDICTION_HORIZON_H   = 1.2
        const val IOB_START                   = 0.40
        const val PEAK_IOB_BRAKE_SUPPRESS     = 0.42
        const val EARLY_BOOST_FACTOR          = 1.0
        const val EARLY_BOOST_MIN_CONFIDENCE  = 0.50  // was 0.60 (formule: 0.50-(0.5-0.5)*0.20)
        const val EARLY_BOOST_MAX_COMMITS     = 2
        const val EARLY_RISE_FRAC_MIN         = 0.35
        const val PEAK_MAX_SLOPE_WEIGHT       = 0.0
        const val LATE_COMMIT_DECAY_FACTOR    = 0.0
        const val LATE_COMMIT_DECAY_THRESHOLD = 0.55
        const val SUSTAINED_RISE_SLOPE_MIN    = 0.40  // was ontbrekend; 0.35 = F=0.60-waarde
        const val SUSTAINED_RISE_MIN_TARGET   = 12    // was ontbrekend; 10  = F=0.60-waarde
    }

    // Cache — voorkomt I/O elke 5-minuten cyclus als niets veranderd is
    @Volatile private var lastWrittenConfig: FCLvNextConfig? = null
    @Volatile private var lastWrittenSTV: String = ""

    fun writeIfChanged(
        config: FCLvNextConfig,
        prefs: Preferences? = null,
        sterkte: Int = Defaults.STERKTE,
        timing: Int = Defaults.TIMING,
        volhoudendheid: Int = Defaults.VOLHOUDENDHEID,
        nachtFactor: Int = Defaults.NACHT_FACTOR
    ) {
        val stvKey = "$sterkte/$timing/$volhoudendheid/$nachtFactor"

        // Snapshot altijd bijwerken — ook als config ongewijzigd is.
        // max_bolus_day kan veranderd zijn terwijl de rest van de config gelijk blijft.
        val manualMaxSmbDay = prefs?.get(DoubleKey.max_bolus_day) ?: 1.25
        FclActiveConfigBridge.snapshot(
            config          = config,
            sterkte         = sterkte,
            timing          = timing,
            volhoudendheid  = volhoudendheid,
            nachtFactor     = nachtFactor,
            manualMaxSmbDay = manualMaxSmbDay
        )

        // Cache-check: sla bestandsschrijving over als niets veranderd is
        if (config == lastWrittenConfig && stvKey == lastWrittenSTV) return

        try {
            val file = File(
                Environment.getExternalStorageDirectory(),
                "$RELATIVE_PATH/$FILENAME"
            )
            file.parentFile?.mkdirs()
            file.writeText(buildJson(config, sterkte, timing, volhoudendheid, nachtFactor))
            lastWrittenConfig = config
            lastWrittenSTV    = stvKey
        } catch (_: Exception) {
            // schrijven mag NOOIT FCL blokkeren
        }
    }

    private fun buildJson(
        config: FCLvNextConfig,
        sterkte: Int,
        timing: Int,
        volhoudendheid: Int,
        nachtFactor: Int
    ): String {
        val now = Instant.now().toString()

        fun fmt(v: Double)    = String.format(java.util.Locale.US, "%.4f", v)
        fun fmtDisplay(active: Double, default: Double): String {
            val d = active - default
            return when {
                kotlin.math.abs(d) < 0.001 -> "0"
                d > 0 -> "+%.2f".format(d)
                else  -> "%.2f".format(d)
            }
        }
        fun src(active: Double, default: Double) =
            if (kotlin.math.abs(active - default) < 0.001) "default" else "modified"
        fun srcInt(active: Int, default: Int) =
            if (active == default) "default" else "modified"

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": 2,")
            appendLine("  \"written_at\": \"$now\",")
            // S/T/V/N blok
            appendLine("  \"stv\": {")
            appendLine("    \"sterkte\": $sterkte,")
            appendLine("    \"timing\": $timing,")
            appendLine("    \"volhoudendheid\": $volhoudendheid,")
            appendLine("    \"nacht_factor\": $nachtFactor")
            appendLine("  },")
            appendLine("  \"params\": {")
            appendLine("    \"peakPredictionThreshold\":  { \"active\": ${fmt(config.peakPredictionThreshold)},  \"default\": ${fmt(Defaults.PEAK_PREDICTION_THRESHOLD)},  \"delta\": \"${fmtDisplay(config.peakPredictionThreshold,  Defaults.PEAK_PREDICTION_THRESHOLD)}\",  \"src\": \"${src(config.peakPredictionThreshold,  Defaults.PEAK_PREDICTION_THRESHOLD)}\" },")
            appendLine("    \"watchingFrontloadFrac\":    { \"active\": ${fmt(config.watchingFrontloadFrac)},    \"default\": ${fmt(Defaults.WATCHING_FRONTLOAD_FRAC)},    \"delta\": \"${fmtDisplay(config.watchingFrontloadFrac,    Defaults.WATCHING_FRONTLOAD_FRAC)}\",    \"src\": \"${src(config.watchingFrontloadFrac,    Defaults.WATCHING_FRONTLOAD_FRAC)}\" },")
            appendLine("    \"watchingMinDeltaToTarget\": { \"active\": ${fmt(config.watchingMinDeltaToTarget)}, \"default\": ${fmt(Defaults.WATCHING_MIN_DELTA_TARGET)}, \"delta\": \"${fmtDisplay(config.watchingMinDeltaToTarget, Defaults.WATCHING_MIN_DELTA_TARGET)}\", \"src\": \"${src(config.watchingMinDeltaToTarget, Defaults.WATCHING_MIN_DELTA_TARGET)}\" },")
            appendLine("    \"commitCooldownMinutes\":    { \"active\": ${config.commitCooldownMinutes},          \"default\": ${Defaults.COMMIT_COOLDOWN_MINUTES},          \"delta\": \"${fmtDisplay(config.commitCooldownMinutes.toDouble(), Defaults.COMMIT_COOLDOWN_MINUTES.toDouble())}\",  \"src\": \"${srcInt(config.commitCooldownMinutes, Defaults.COMMIT_COOLDOWN_MINUTES)}\" },")
            appendLine("    \"peakPredictionHorizonH\":   { \"active\": ${fmt(config.peakPredictionHorizonH)},   \"default\": ${fmt(Defaults.PEAK_PREDICTION_HORIZON_H)},   \"delta\": \"${fmtDisplay(config.peakPredictionHorizonH,   Defaults.PEAK_PREDICTION_HORIZON_H)}\",   \"src\": \"${src(config.peakPredictionHorizonH,   Defaults.PEAK_PREDICTION_HORIZON_H)}\" },")
            appendLine("    \"iobStart\":                 { \"active\": ${fmt(config.iobStart)},                 \"default\": ${fmt(Defaults.IOB_START)},                 \"delta\": \"${fmtDisplay(config.iobStart,                 Defaults.IOB_START)}\",                 \"src\": \"${src(config.iobStart,                 Defaults.IOB_START)}\" },")
            appendLine("    \"peakIobBrakeSuppressThreshold\": { \"active\": ${fmt(config.peakIobBrakeSuppressThreshold)}, \"default\": ${fmt(Defaults.PEAK_IOB_BRAKE_SUPPRESS)}, \"delta\": \"${fmtDisplay(config.peakIobBrakeSuppressThreshold, Defaults.PEAK_IOB_BRAKE_SUPPRESS)}\", \"src\": \"${src(config.peakIobBrakeSuppressThreshold, Defaults.PEAK_IOB_BRAKE_SUPPRESS)}\" },")
            appendLine("    \"earlyBoostFactor\":         { \"active\": ${fmt(config.earlyBoostFactor)},         \"default\": ${fmt(Defaults.EARLY_BOOST_FACTOR)},         \"delta\": \"${fmtDisplay(config.earlyBoostFactor,         Defaults.EARLY_BOOST_FACTOR)}\",         \"src\": \"${src(config.earlyBoostFactor,         Defaults.EARLY_BOOST_FACTOR)}\" },")
            appendLine("    \"earlyBoostMinConfidence\":  { \"active\": ${fmt(config.earlyBoostMinConfidence)},  \"default\": ${fmt(Defaults.EARLY_BOOST_MIN_CONFIDENCE)},  \"delta\": \"${fmtDisplay(config.earlyBoostMinConfidence,  Defaults.EARLY_BOOST_MIN_CONFIDENCE)}\",  \"src\": \"${src(config.earlyBoostMinConfidence,  Defaults.EARLY_BOOST_MIN_CONFIDENCE)}\" },")
            appendLine("    \"earlyBoostMaxCommits\":     { \"active\": ${config.earlyBoostMaxCommits},          \"default\": ${Defaults.EARLY_BOOST_MAX_COMMITS},          \"delta\": \"${fmtDisplay(config.earlyBoostMaxCommits.toDouble(), Defaults.EARLY_BOOST_MAX_COMMITS.toDouble())}\", \"src\": \"${srcInt(config.earlyBoostMaxCommits, Defaults.EARLY_BOOST_MAX_COMMITS)}\" },")
            appendLine("    \"earlyRiseFracMin\":         { \"active\": ${fmt(config.earlyRiseFracMin)},         \"default\": ${fmt(Defaults.EARLY_RISE_FRAC_MIN)},         \"delta\": \"${fmtDisplay(config.earlyRiseFracMin,         Defaults.EARLY_RISE_FRAC_MIN)}\",         \"src\": \"${src(config.earlyRiseFracMin,         Defaults.EARLY_RISE_FRAC_MIN)}\" },")
            append(  "    \"peakMaxSlopeWeight\":       { \"active\": ${fmt(config.peakMaxSlopeWeight)},       \"default\": ${fmt(Defaults.PEAK_MAX_SLOPE_WEIGHT)},       \"delta\": \"${fmtDisplay(config.peakMaxSlopeWeight,       Defaults.PEAK_MAX_SLOPE_WEIGHT)}\",       \"src\": \"${src(config.peakMaxSlopeWeight,       Defaults.PEAK_MAX_SLOPE_WEIGHT)}\" }")
            appendLine(",")
            appendLine("    \"lateCommitDecayFactor\":   { \"active\": ${fmt(config.lateCommitDecayFactor)},   \"default\": ${fmt(Defaults.LATE_COMMIT_DECAY_FACTOR)},   \"delta\": \"${fmtDisplay(config.lateCommitDecayFactor,   Defaults.LATE_COMMIT_DECAY_FACTOR)}\",   \"src\": \"${src(config.lateCommitDecayFactor,   Defaults.LATE_COMMIT_DECAY_FACTOR)}\" },")
            appendLine("    \"lateCommitDecayThreshold\": { \"active\": ${fmt(config.lateCommitDecayThreshold)}, \"default\": ${fmt(Defaults.LATE_COMMIT_DECAY_THRESHOLD)}, \"delta\": \"${fmtDisplay(config.lateCommitDecayThreshold, Defaults.LATE_COMMIT_DECAY_THRESHOLD)}\", \"src\": \"${src(config.lateCommitDecayThreshold, Defaults.LATE_COMMIT_DECAY_THRESHOLD)}\" },")
            appendLine("    \"sustainedRiseSlopeMin\":   { \"active\": ${fmt(config.sustainedRiseSlopeMin)},   \"default\": ${fmt(Defaults.SUSTAINED_RISE_SLOPE_MIN)},   \"delta\": \"${fmtDisplay(config.sustainedRiseSlopeMin,   Defaults.SUSTAINED_RISE_SLOPE_MIN)}\",   \"src\": \"${src(config.sustainedRiseSlopeMin,   Defaults.SUSTAINED_RISE_SLOPE_MIN)}\" },")
            append(  "    \"sustainedRiseMinTarget\":   { \"active\": ${config.sustainedRiseMinTarget},          \"default\": ${Defaults.SUSTAINED_RISE_MIN_TARGET},          \"delta\": \"${fmtDisplay(config.sustainedRiseMinTarget.toDouble(), Defaults.SUSTAINED_RISE_MIN_TARGET.toDouble())}\", \"src\": \"${srcInt(config.sustainedRiseMinTarget, Defaults.SUSTAINED_RISE_MIN_TARGET)}\" }")
            appendLine()
            appendLine("  }")
            append("}")
        }
    }
}