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
        const val EARLY_PEAK_BIAS_MMOL       = 0.0
    }

    // ── Defaults S/T/V/N ─────────────────────────────────────────────────
    object StvDefaults {
        const val STERKTE        = 100
        const val TIMING         = 100
        const val VOLHOUDENDHEID = 100
        const val NF_LEVEL       = 5.0  // schaal 1-9; 5=BALANCED (was: NACHT_FACTOR=90→% omgezet naar ~5.3)
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
        val earlyPeakBiasMmol:             Double  = Defaults.EARLY_PEAK_BIAS_MMOL,
        val sterkte:                       Int     = StvDefaults.STERKTE,
        val timing:                        Int     = StvDefaults.TIMING,
        val volhoudendheid:                Int     = StvDefaults.VOLHOUDENDHEID,
        val nfLevel:                       Double  = StvDefaults.NF_LEVEL,  // 1-9
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
        val sustainedRiseMinTarget:        Int?    = null,
        val earlyPeakBiasMmol:             Double? = null
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
                sustainedRiseMinTarget        == null &&
                earlyPeakBiasMmol             == null
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
            earlyPeakBiasMmol             = c.earlyPeakBiasMmol,
            sterkte                       = snap.sterkte,
            timing                        = snap.timing,
            volhoudendheid                = snap.volhoudendheid,
            nfLevel                       = snap.nfLevel,
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
        iobBrakeLearned: Double? = null,
        context: android.content.Context? = null
    ): Boolean {
        val stvWithSmb = stvMap.toMutableMap()
        iobBrakeLearned?.let { stvWithSmb["iob_brake_learned_x1000"] = (it * 1000).toInt() }
        return postToBridge(stvWithSmb, reason, episodeCount, paramOverrides, context)
    }

    /**
     * Stuurt S/T/V + nieuwe N-factor rechtstreeks naar FCLvNext via de bridge.
     */
    // writeWithNacht behouden voor backwards-compatibility maar deprecated
    @Deprecated("Gebruik writeWithNfLevel")
    fun writeWithNacht(
        currentState: StvState,
        newNachtFactor: Int,
        reason: String,
        episodeCount: Int
    ): Boolean = writeWithNfLevel(currentState, 5.0, reason, episodeCount)  // deprecated, nfLevel via DFLearner

    fun writeWithNfLevel(
        currentState: StvState,
        newNfLevel: Double,
        reason: String,
        episodeCount: Int
    ): Boolean {
        val clamped = newNfLevel.coerceIn(1.0, 9.0)
        val stv = mapOf(
            "sterkte"        to currentState.sterkte.coerceIn(80, 125),
            "timing"         to currentState.timing.coerceIn(80, 120),
            "volhoudendheid" to currentState.volhoudendheid.coerceIn(70, 130)
        )
        // BUGFIX 19/06/2026: nfLevel moet daadwerkelijk in de Override mee,
        // anders leest loadFCLvNextConfig() 'm nooit (override?.nfLevel was
        // altijd null, dus viel altijd terug op de ongewijzigde prefs-waarde
        // — de "Toepassen in AAPS"-knop op het Nacht-tabblad had hierdoor
        // geen enkel effect op de actieve dosering, alleen op de UI-weergave
        // via DFLearner.setNfLevel()). Zie ook FCLvNextConfig.kt regel ~306:
        // de schrijf-terug-naar-prefs gebeurt al correct ZODRA nfLevel niet
        // null is — die kant van de keten was altijd al goed.
        return postToBridge(stv, reason, episodeCount, paramOverrides = null, nfLevel = clamped)
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
        paramOverrides: ParamOverrides?,
        context: android.content.Context? = null,
        nfLevel: Double? = null
    ): Boolean {
        val sterkte        = stv["sterkte"]        ?: StvDefaults.STERKTE
        val timing         = stv["timing"]         ?: StvDefaults.TIMING
        val volhoudendheid = stv["volhoudendheid"] ?: StvDefaults.VOLHOUDENDHEID
        val iobBrakeRaw    = stv["iob_brake_learned_x1000"]

        val override = app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride.Override(
            sterkte          = sterkte,
            timing           = timing,
            volhoudendheid   = volhoudendheid,
            nfLevel          = nfLevel,  // BUGFIX 19/06/2026: was altijd null, zie writeWithNfLevel()
            writtenAt        = java.time.Instant.now().toString(),
            basedOnEpisodes  = episodeCount,
            reason           = reason,
            paramOverrides   = paramOverrides?.let { p ->
                if (p.isEmpty()) null else run {
                    // Prioriteit per param: (AI aan? → FclAiParamStore) → p.X (D/F-vers) → …
                    //
                    // HERZIEN (10/07/2026, Ecko — Learner/AI-onafhankelijkheid):
                    //  1. FclAiParamStore telt alleen mee als de AI-adviseur AAN staat.
                    //     Staat AI uit, dan wordt FclAiParamStore genegeerd (niet geleegd —
                    //     zie FclAiAdvisorSettingsStore.isEnabled-kdoc) zodat de Learner,
                    //     indien aan, daadwerkelijk volledige controle heeft.
                    //  2. Voor de 3 parameters met een eigen dedicated "ondergrond"-waarde
                    //     (earlyBoostFactor/watchingFrontloadFrac/lateCommitDecayFactor)
                    //     blijft de bestaande derde fallback (DFLearner.getX()) staan.
                    //  3. Voor de overige 7 is een nieuwe derde fallback toegevoegd:
                    //     DFLearner.getTrackedParam() — de generieke, zacht-convergerende
                    //     tracked-waarde (zie DFLearner.convergeTrackedParams). Zonder AI-
                    //     override valt dit terug op wat de AI het laatst goedkeurde en
                    //     groeit dat geleidelijk terug naar de D/F-waarde als de Learner
                    //     aan staat — geen sprong bij het uitzetten van de AI-adviseur.
                    val aiEnabled = context?.let {
                        app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiAdvisorSettingsStore.isEnabled(it)
                    } ?: false

                    app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride.ParamOverrides(
                        peakPredictionThreshold       = p.peakPredictionThreshold,
                        // ── AI-PARAM-STORE PRIORITEIT (01/07/2026, Ecko) ─────────────────
                        // Als de AI-advisor een waarde heeft goedgekeurd voor deze parameters,
                        // wint die altijd boven de DFLearner-fallback. Zo is er één waarheid:
                        //   defaults → DFLearner leert → AI past aan → dit is de aktieve waarde
                        //
                        watchingFrontloadFrac = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_WATCHING_FRONTLOAD_FRAC)
                        } else null) ?: p.watchingFrontloadFrac ?: context?.let { DFLearner.getWatchingFrac(it) },

                        watchingMinDeltaToTarget = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_WATCHING_MIN_DELTA)
                        } else null) ?: p.watchingMinDeltaToTarget ?: context?.let {
                            DFLearner.getTrackedParam(it, "watchingMinDeltaToTarget", p.watchingMinDeltaToTarget ?: 0.0)
                        },

                        commitCooldownMinutes = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getInt(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_COMMIT_COOLDOWN_MINUTES)
                        } else null) ?: p.commitCooldownMinutes ?: context?.let {
                            DFLearner.getTrackedParam(it, "commitCooldownMinutes", (p.commitCooldownMinutes ?: 0).toDouble()).let { v -> kotlin.math.round(v).toInt() }
                        },
                        peakPredictionHorizonH        = p.peakPredictionHorizonH,
                        iobStart                      = p.iobStart,
                        peakIobBrakeSuppressThreshold = p.peakIobBrakeSuppressThreshold,

                        earlyBoostFactor = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_EARLY_BOOST_FACTOR)
                        } else null) ?: p.earlyBoostFactor ?: context?.let { DFLearner.getEarlyBoostFactor(it) },

                        earlyBoostMinConfidence = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_EARLY_BOOST_MIN_CONFIDENCE)
                        } else null) ?: p.earlyBoostMinConfidence ?: context?.let {
                            DFLearner.getTrackedParam(it, "earlyBoostMinConfidence", p.earlyBoostMinConfidence ?: 0.0)
                        },

                        earlyBoostMaxCommits = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getInt(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_EARLY_BOOST_MAX_COMMITS)
                        } else null) ?: p.earlyBoostMaxCommits ?: context?.let {
                            DFLearner.getTrackedParam(it, "earlyBoostMaxCommits", (p.earlyBoostMaxCommits ?: 0).toDouble()).let { v -> kotlin.math.round(v).toInt() }
                        },

                        earlyRiseFracMin = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_EARLY_RISE_FRAC_MIN)
                        } else null) ?: p.earlyRiseFracMin ?: context?.let {
                            DFLearner.getTrackedParam(it, "earlyRiseFracMin", p.earlyRiseFracMin ?: 0.0)
                        },
                        peakMaxSlopeWeight            = p.peakMaxSlopeWeight,

                        lateCommitDecayFactor = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_LATE_COMMIT_DECAY_FACTOR)
                        } else null) ?: p.lateCommitDecayFactor,

                        lateCommitDecayThreshold = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_LATE_COMMIT_DECAY_THRESHOLD)
                        } else null) ?: p.lateCommitDecayThreshold ?: context?.let {
                            DFLearner.getTrackedParam(it, "lateCommitDecayThreshold", p.lateCommitDecayThreshold ?: 0.0)
                        },

                        sustainedRiseSlopeMin = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getDouble(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_SUSTAINED_RISE_SLOPE_MIN)
                        } else null) ?: p.sustainedRiseSlopeMin ?: context?.let {
                            DFLearner.getTrackedParam(it, "sustainedRiseSlopeMin", p.sustainedRiseSlopeMin ?: 0.0)
                        },

                        sustainedRiseMinTarget = (if (aiEnabled) context?.let {
                            app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.getInt(it, app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai.FclAiParamStore.K_SUSTAINED_RISE_MIN_TARGET)
                        } else null) ?: p.sustainedRiseMinTarget,
                        earlyPeakBiasMmol             = p.earlyPeakBiasMmol
                    )
                }
            },
            iobBrakeLearned  = iobBrakeRaw?.let { it.toDouble() / 1000.0 }
        )
        app.aaps.plugins.aps.openAPSFCL.vnext.FclOverrideBridge.post(override)
        return true
    }

    /**
     * Budget-neutrale verschuiving van insuline naar voren of achteren.
     * earlyBoostFactor groter → watchingFrontloadFrac evenredig kleiner.
     * Totale insuline per episode blijft hierdoor gelijk.
     *
     * @param direction  +1 = frontload groter (insuline naar voren)
     *                   -1 = frontload kleiner (insuline naar achteren, bij hypo)
     * @param stepFrac   relatieve stapgrootte per episode, standaard 3%
     */
    fun applyEarlyBoostShift(
        currentOverrides: ParamOverrides,
        stvMap: Map<String, Int>,
        direction: Int,
        reason: String,
        episodeCount: Int,
        stepFrac: Double = 0.03
    ): Boolean {
        val oldBoost    = currentOverrides.earlyBoostFactor    ?: Defaults.EARLY_BOOST_FACTOR
        val oldWatching = currentOverrides.watchingFrontloadFrac ?: Defaults.WATCHING_FRONTLOAD_FRAC

        val newBoost = (oldBoost * (1.0 + direction * stepFrac)).coerceIn(1.30, 2.20)
        // Budget-neutraal: newWatching * newBoost = oldWatching * oldBoost
        val newWatching = if (newBoost > 0.01)
            (oldWatching * oldBoost / newBoost).coerceIn(0.45, 0.85)
        else oldWatching

        val updated = currentOverrides.copy(
            earlyBoostFactor      = newBoost,
            watchingFrontloadFrac = newWatching
        )
        return writeWithStvAndParams(stvMap, updated, reason, episodeCount)
    }
}