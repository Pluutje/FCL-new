package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvAdjustment
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor.StvState

/**
 * Stuurt parameter-aanpassingen van de Analyzer rechtstreeks naar FCLvNext
 * via FclOverrideBridge (in-memory, geen bestandsschrijven).
 * Leest actieve parameters rechtstreeks uit FclActiveConfigBridge.
 */
object ConfigOverrideWriter {

    // ── Defaults Groep-3 params ───────────────────────────────────────────
    object Defaults {
        const val PEAK_PREDICTION_THRESHOLD  = 12.5
        const val WATCHING_FRONTLOAD_FRAC    = 0.64
        const val WATCHING_MIN_DELTA_TARGET  = 1.50
        const val COMMIT_COOLDOWN_MINUTES    = 13
        const val PEAK_PREDICTION_HORIZON_H  = 1.2
        const val IOB_START                  = 0.40
        const val PEAK_IOB_BRAKE_SUPPRESS    = 0.42
        const val EARLY_BOOST_FACTOR         = 1.0
        const val EARLY_BOOST_MIN_CONFIDENCE = 0.50
        const val EARLY_BOOST_MAX_COMMITS    = 2
        const val EARLY_RISE_FRAC_MIN        = 0.35
        const val PEAK_MAX_SLOPE_WEIGHT      = 0.0
        const val LATE_COMMIT_DECAY_FACTOR   = 0.0
        const val LATE_COMMIT_DECAY_THRESHOLD = 0.55
        const val SUSTAINED_RISE_SLOPE_MIN   = 0.40
        const val SUSTAINED_RISE_MIN_TARGET  = 12
    }

    // ── Defaults S/T/V/N ─────────────────────────────────────────────────
    object StvDefaults {
        const val STERKTE        = 100
        const val TIMING         = 100
        const val VOLHOUDENDHEID = 100
        const val NACHT_FACTOR   = 85
        val MAX_SMB_DAY_LEARNED: Double? = null
        val IOB_BRAKE_LEARNED:   Double? = null
    }

    // ── ActiveParams: actuele FCLvNext parameterwaarden ──────────────────
    data class ActiveParams(
        val peakPredictionThreshold:       Double  = Defaults.PEAK_PREDICTION_THRESHOLD,
        val watchingFrontloadFrac:         Double  = Defaults.WATCHING_FRONTLOAD_FRAC,
        val watchingMinDeltaToTarget:      Double  = Defaults.WATCHING_MIN_DELTA_TARGET,
        val commitCooldownMinutes:         Int     = Defaults.COMMIT_COOLDOWN_MINUTES,
        val peakPredictionHorizonH:        Double  = Defaults.PEAK_PREDICTION_HORIZON_H,
        val iobStart:                      Double  = Defaults.IOB_START,
        val peakIobBrakeSuppressThreshold: Double  = Defaults.PEAK_IOB_BRAKE_SUPPRESS,
        val earlyBoostFactor:              Double  = Defaults.EARLY_BOOST_FACTOR,
        val earlyBoostMinConfidence:       Double  = Defaults.EARLY_BOOST_MIN_CONFIDENCE,
        val earlyBoostMaxCommits:          Int     = Defaults.EARLY_BOOST_MAX_COMMITS,
        val earlyRiseFracMin:              Double  = Defaults.EARLY_RISE_FRAC_MIN,
        val peakMaxSlopeWeight:            Double  = Defaults.PEAK_MAX_SLOPE_WEIGHT,
        val lateCommitDecayFactor:         Double  = Defaults.LATE_COMMIT_DECAY_FACTOR,
        val lateCommitDecayThreshold:      Double  = Defaults.LATE_COMMIT_DECAY_THRESHOLD,
        val sustainedRiseSlopeMin:         Double  = Defaults.SUSTAINED_RISE_SLOPE_MIN,
        val sustainedRiseMinTarget:        Int     = Defaults.SUSTAINED_RISE_MIN_TARGET,
        val sterkte:                       Int     = StvDefaults.STERKTE,
        val timing:                        Int     = StvDefaults.TIMING,
        val volhoudendheid:                Int     = StvDefaults.VOLHOUDENDHEID,
        val nachtFactor:                   Int     = StvDefaults.NACHT_FACTOR,
        val writtenAt:                     String  = "",
        val dataAvailable:                 Boolean = false
    )

    // ── ParamOverrides: Groep-3 fijnafstelling ────────────────────────────
    data class ParamOverrides(
        val peakPredictionThreshold:       Double? = null,
        val watchingFrontloadFrac:         Double? = null,
        val watchingMinDeltaToTarget:      Double? = null,
        val commitCooldownMinutes:         Int?    = null,
        val peakPredictionHorizonH:        Double? = null,
        val iobStart:                      Double? = null,
        val peakIobBrakeSuppressThreshold: Double? = null,
        val earlyBoostFactor:              Double? = null,
        val earlyBoostMinConfidence:       Double? = null,
        val earlyBoostMaxCommits:          Int?    = null,
        val earlyRiseFracMin:              Double? = null,
        val peakMaxSlopeWeight:            Double? = null,
        val lateCommitDecayFactor:         Double? = null,
        val lateCommitDecayThreshold:      Double? = null,
        val sustainedRiseSlopeMin:         Double? = null,
        val sustainedRiseMinTarget:        Int?    = null
    ) {
        fun isEmpty() =
            peakPredictionThreshold       == null &&
                watchingFrontloadFrac         == null &&
                watchingMinDeltaToTarget      == null &&
                commitCooldownMinutes         == null &&
                peakPredictionHorizonH        == null &&
                iobStart                      == null &&
                peakIobBrakeSuppressThreshold == null &&
                earlyBoostFactor              == null &&
                earlyBoostMinConfidence       == null &&
                earlyBoostMaxCommits          == null &&
                earlyRiseFracMin              == null &&
                peakMaxSlopeWeight            == null &&
                lateCommitDecayFactor         == null &&
                lateCommitDecayThreshold      == null &&
                sustainedRiseSlopeMin         == null &&
                sustainedRiseMinTarget        == null
    }

    // ── Publieke API ──────────────────────────────────────────────────────

    /**
     * Leest actieve FCLvNext parameters rechtstreeks uit het in-memory snapshot.
     * Beschikbaar na de eerste FCLvNext cyclus.
     */
    fun readActiveParams(): ActiveParams {
        val snap = app.aaps.plugins.aps.openAPSFCL.vnext.FclActiveConfigBridge.get()
            ?: return ActiveParams()
        val c = snap.config
        return ActiveParams(
            peakPredictionThreshold       = c.peakPredictionThreshold,
            watchingFrontloadFrac         = c.watchingFrontloadFrac,
            watchingMinDeltaToTarget      = c.watchingMinDeltaToTarget,
            commitCooldownMinutes         = c.commitCooldownMinutes,
            peakPredictionHorizonH        = c.peakPredictionHorizonH,
            iobStart                      = c.iobStart,
            peakIobBrakeSuppressThreshold = c.peakIobBrakeSuppressThreshold,
            earlyBoostFactor              = c.earlyBoostFactor,
            earlyBoostMinConfidence       = c.earlyBoostMinConfidence,
            earlyBoostMaxCommits          = c.earlyBoostMaxCommits,
            earlyRiseFracMin              = c.earlyRiseFracMin,
            peakMaxSlopeWeight            = c.peakMaxSlopeWeight,
            lateCommitDecayFactor         = c.lateCommitDecayFactor,
            lateCommitDecayThreshold      = c.lateCommitDecayThreshold,
            sustainedRiseSlopeMin         = c.sustainedRiseSlopeMin,
            sustainedRiseMinTarget        = c.sustainedRiseMinTarget,
            sterkte                       = snap.sterkte,
            timing                        = snap.timing,
            volhoudendheid                = snap.volhoudendheid,
            nachtFactor                   = snap.nachtFactor,
            writtenAt                     = java.time.Instant.now().toString(),
            dataAvailable                 = true
        )
    }

    /**
     * Stuurt S/T/V + param_overrides rechtstreeks naar FCLvNext via de bridge.
     */
    fun writeWithStvAndParams(
        stvMap: Map<String, Int>,
        paramOverrides: ParamOverrides,
        reason: String,
        episodeCount: Int,
        maxSmbDayLearned: Double? = null,
        iobBrakeLearned: Double? = null
    ): Boolean {
        val stvWithSmb = stvMap.toMutableMap()
        maxSmbDayLearned?.let { stvWithSmb["max_smb_day_learned_x100"] = (it * 100).toInt() }
        iobBrakeLearned?.let  { stvWithSmb["iob_brake_learned_x1000"]  = (it * 1000).toInt() }
        return postToBridge(stvWithSmb, reason, episodeCount, paramOverrides)
    }

    /**
     * Stuurt S/T/V + nieuwe N-factor rechtstreeks naar FCLvNext via de bridge.
     */
    fun writeWithNacht(
        currentState: StvState,
        newNachtFactor: Int,
        reason: String,
        episodeCount: Int
    ): Boolean {
        val stv = mapOf(
            "sterkte"        to currentState.sterkte.coerceIn(80, 125),
            "timing"         to currentState.timing.coerceIn(80, 120),
            "volhoudendheid" to currentState.volhoudendheid.coerceIn(70, 130),
            "nacht_factor"   to newNachtFactor.coerceIn(60, 110)
        )
        return postToBridge(stv, reason, episodeCount, paramOverrides = null)
    }

    /**
     * Conversie: StvState + StvAdjustment → Int map voor writeWithStvAndParams.
     */
    fun buildStvMap(current: StvState, adjustment: StvAdjustment): Map<String, Int> =
        mapOf(
            "sterkte"        to (current.sterkte        + adjustment.dSterkte).coerceIn(80, 125),
            "timing"         to (current.timing         + adjustment.dTiming).coerceIn(80, 120),
            "volhoudendheid" to (current.volhoudendheid + adjustment.dVolhoudendheid).coerceIn(70, 130)
        )

    // ── Interne bridge-aanroep ────────────────────────────────────────────

    private fun postToBridge(
        stv: Map<String, Int>,
        reason: String,
        episodeCount: Int,
        paramOverrides: ParamOverrides?
    ): Boolean {
        val sterkte        = stv["sterkte"]        ?: StvDefaults.STERKTE
        val timing         = stv["timing"]         ?: StvDefaults.TIMING
        val volhoudendheid = stv["volhoudendheid"] ?: StvDefaults.VOLHOUDENDHEID
        val nachtFactor    = stv["nacht_factor"]
        val maxSmbRaw      = stv["max_smb_day_learned_x100"]
        val iobBrakeRaw    = stv["iob_brake_learned_x1000"]

        val override = app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride.Override(
            sterkte          = sterkte,
            timing           = timing,
            volhoudendheid   = volhoudendheid,
            nachtFactor      = nachtFactor,
            writtenAt        = java.time.Instant.now().toString(),
            basedOnEpisodes  = episodeCount,
            reason           = reason,
            paramOverrides   = paramOverrides?.let { p ->
                if (p.isEmpty()) null else
                    app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride.ParamOverrides(
                        peakPredictionThreshold       = p.peakPredictionThreshold,
                        watchingFrontloadFrac         = p.watchingFrontloadFrac,
                        watchingMinDeltaToTarget      = p.watchingMinDeltaToTarget,
                        commitCooldownMinutes         = p.commitCooldownMinutes,
                        peakPredictionHorizonH        = p.peakPredictionHorizonH,
                        iobStart                      = p.iobStart,
                        peakIobBrakeSuppressThreshold = p.peakIobBrakeSuppressThreshold,
                        earlyBoostFactor              = p.earlyBoostFactor,
                        earlyBoostMinConfidence       = p.earlyBoostMinConfidence,
                        earlyBoostMaxCommits          = p.earlyBoostMaxCommits,
                        earlyRiseFracMin              = p.earlyRiseFracMin,
                        peakMaxSlopeWeight            = p.peakMaxSlopeWeight,
                        lateCommitDecayFactor         = p.lateCommitDecayFactor,
                        lateCommitDecayThreshold      = p.lateCommitDecayThreshold,
                        sustainedRiseSlopeMin         = p.sustainedRiseSlopeMin,
                        sustainedRiseMinTarget        = p.sustainedRiseMinTarget
                    )
            },
            maxSmbDayLearned = maxSmbRaw?.let  { it.toDouble() / 100.0 },
            iobBrakeLearned  = iobBrakeRaw?.let { it.toDouble() / 1000.0 }
        )
        app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge.post(override)
        return true
    }
}