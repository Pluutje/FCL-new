package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.advisor

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeClassifier
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt


object FclPatternAdvisor {

    data class Config(
        val minEpisodes: Int = 6,
        val targetPeakUpper: Double = 8.5,
        val hardPeakUpper: Double = 9.5,
        val earlySpikeRiseThreshold: Double = 3.0,
        val latePeakMinutesThreshold: Double = 90.0,
        val longTailMinutesThreshold: Double = 180.0,
        val hypoRateThreshold: Double = 0.10,
        val strongHypoRateThreshold: Double = 0.18,
        val oscillationPeakThreshold: Double = 9.0
    )

    fun analyzeAggregate(
        aggregate: EpisodeAggregate,
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        current: StvState,
        config: Config = Config()
    ): FclAdvisorRecommendation {

        if (aggregate.episodeCount < config.minEpisodes) {
            return FclAdvisorRecommendation(
                dominantPattern = FclPattern.MIXED_UNCLEAR,
                confidence = 0.2,
                patternScores = emptyList(),
                adjustment = StvAdjustment(),
                vector = StvAdjustment(),
                transitions = emptyList(),
                stats = buildStats(metrics, classes),
                summary = "Te weinig episodes voor betrouwbaar advies.",
                axisEvidence = emptyList()
            )
        }

        return analyze(metrics = metrics, classes = classes, current = current, config = config)
    }

    fun analyze(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        current: StvState,
        config: Config = Config()
    ): FclAdvisorRecommendation {

        if (metrics.isEmpty() || classes.isEmpty()) {
            return FclAdvisorRecommendation(
                dominantPattern = FclPattern.MIXED_UNCLEAR,
                confidence = 0.0,
                patternScores = listOf(
                    FclPatternScore(
                        pattern = FclPattern.MIXED_UNCLEAR,
                        score = 1.0,
                        reason = "Geen episodes beschikbaar."
                    )
                ),
                adjustment = StvAdjustment(),
                vector = StvAdjustment(),
                transitions = emptyList(),
                stats = buildStats(metrics, classes),
                summary = "Geen advies mogelijk: geen episode-data.",
                axisEvidence = emptyList()
            )
        }

        val scores = listOf(
            scoreEarlySpike(metrics, classes, config),
            scoreLatePeak(metrics, classes, config),
            scoreEarlyHypo(metrics, classes, config),
            scoreLateHypo(metrics, classes, config),
            scoreLongHighTail(metrics, classes, config),
            scoreOscillating(metrics, classes, config),
            scoreFlatGood(metrics, classes, config)
        ).sortedByDescending { it.score }

        val dominant = scores.first()
        val vector = evidenceVectorForPattern(dominant.pattern)
        val transitions = applyVector(current, vector, dominant.reason)

        val confidence = computeConfidence(
            episodeCount = metrics.size,
            topScore = dominant.score,
            secondScore = scores.getOrNull(1)?.score ?: 0.0,
            minEpisodes = config.minEpisodes
        )

        val axisEvidence = buildAxisEvidence(scores)

        return FclAdvisorRecommendation(
            dominantPattern = dominant.pattern,
            confidence = confidence,
            patternScores = scores,
            adjustment = vector,
            vector = vector,
            transitions = transitions,
            stats = buildStats(metrics, classes),
            summary = buildSummary(
                pattern = dominant.pattern,
                transitions = transitions,
                confidence = confidence,
                axisEvidence = axisEvidence
            ),
            axisEvidence = axisEvidence,
            paramSignals = buildParamSignals(scores, dominant.pattern, confidence)
        )
    }

    private fun scoreEarlySpike(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val medianRise = weightedAverage(metrics) { it.riseMagnitude }
        val medianPeak = weightedAverage(metrics) { it.peakBg }
        val hyperRate = weightedRate(metrics, classes) { it.hyper }

        // FIX 2: null-safe timeToPeak — als er geen episodes zijn met tijddata, score 0.0 voor
        // die component in plaats van te werken met een onbetrouwbare 0.0-default die altijd
        // weight(90.0, 0.0, 40.0)=1.0 oplevert.
        val validTimes = metrics.mapNotNull { m ->
            m.timeToPeakMinutes?.toDouble()?.let { t -> t to m.advisorWeight }
        }
        val timeToPeakComponent = if (validTimes.isNotEmpty()) {
            val totalW = validTimes.sumOf { it.second }.coerceAtLeast(1e-9)
            val medianTimeToPeak = validTimes.sumOf { it.first * it.second } / totalW
            // vroege piek = kleine tijdwaarde = hoge score → klopt met patroon-naam
            weight(config.latePeakMinutesThreshold - medianTimeToPeak, 0.0, 40.0)
        } else {
            0.0   // onvoldoende data → component draagt niet bij
        }

        val score =
            weight(medianRise, config.earlySpikeRiseThreshold, 5.0) * 0.35 +
                timeToPeakComponent * 0.30 +
                weight(medianPeak, config.targetPeakUpper, config.hardPeakUpper + 1.0) * 0.20 +
                hyperRate * 0.15

        return FclPatternScore(
            pattern = FclPattern.EARLY_SPIKE,
            score = score.coerceIn(0.0, 1.0),
            reason = "Snelle vroege stijging met relatief vroege piek."
        )
    }

    private fun scoreLatePeak(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val medianPeak = weightedAverage(metrics) { it.peakBg }
        val medianTimeToPeak = weightedAverageNullable(metrics) { it.timeToPeakMinutes?.toDouble() }
        val hyperRate = weightedRate(metrics, classes) { it.hyper }

        val score =
            weight(medianPeak, config.targetPeakUpper, config.hardPeakUpper + 1.0) * 0.40 +
                weight(medianTimeToPeak, config.latePeakMinutesThreshold, 140.0) * 0.40 +
                hyperRate * 0.20

        return FclPatternScore(
            pattern = FclPattern.LATE_PEAK,
            score = score.coerceIn(0.0, 1.0),
            reason = "Piek komt laat en blijft te hoog."
        )
    }

    private fun scoreEarlyHypo(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val rate = weightedRate(metrics, classes) { it.hypoEarly }
        val score = weight(rate, config.hypoRateThreshold, config.strongHypoRateThreshold)

        return FclPatternScore(
            pattern = FclPattern.EARLY_HYPO,
            score = score,
            reason = "Te agressieve vroege respons of te vroege insulinetoediening."
        )
    }

    private fun scoreLateHypo(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val rate = weightedRate(metrics, classes) { it.hypoLate }
        val score = weight(rate, config.hypoRateThreshold, config.strongHypoRateThreshold)

        return FclPatternScore(
            pattern = FclPattern.LATE_HYPO,
            score = score,
            reason = "Te veel totale blootstelling of te lang doorcorrigeren."
        )
    }

    private fun scoreLongHighTail(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val medianDuration = weightedAverage(metrics) { it.durationMinutes.toDouble() }
        val hyperRate = weightedRate(metrics, classes) { it.hyper }

        val score =
            weight(medianDuration, config.longTailMinutesThreshold, 300.0) * 0.60 +
                hyperRate * 0.40

        return FclPatternScore(
            pattern = FclPattern.LONG_HIGH_TAIL,
            score = score.coerceIn(0.0, 1.0),
            reason = "Glucose blijft lang verhoogd na de maaltijd."
        )
    }

    private fun scoreOscillating(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val hyperRate = weightedRate(metrics, classes) { it.hyper }
        val earlyHypoRate = weightedRate(metrics, classes) { it.hypoEarly }
        val lateHypoRate = weightedRate(metrics, classes) { it.hypoLate }
        val medianPeak = weightedAverage(metrics) { it.peakBg }

        // FIX 3: episodes met hoge IOB op de piek wegen zwaarder mee bij oscillerende respons
        // (hoge iobRatioAtPeak + hyper = klassiek te-laat-burst patroon)
        val highIobPeakBonus = weightedRate(metrics, classes) { cls ->
            val m = metrics.getOrNull(classes.indexOf(cls))
            (m?.iobRatioAtPeak ?: 0.0) >= 0.65 && cls.hyper
        } * 0.10

        val score =
            hyperRate * 0.30 +
                earlyHypoRate * 0.25 +
                lateHypoRate * 0.20 +
                weight(medianPeak, config.oscillationPeakThreshold, 11.0) * 0.15 +
                highIobPeakBonus

        return FclPatternScore(
            pattern = FclPattern.OSCILLATING_RESPONSE,
            score = score.coerceIn(0.0, 1.0),
            reason = "Combinatie van overshoot en hypo-signalen wijst op instabiele respons."
        )
    }

    private fun scoreFlatGood(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        config: Config
    ): FclPatternScore {
        val goodRate = weightedRate(metrics, classes) { it.meetsGoal }
        val medianPeak = weightedAverage(metrics) { it.peakBg }

        val score =
            goodRate * 0.75 +
                (1.0 - weight(medianPeak, config.targetPeakUpper, 10.5)) * 0.25

        return FclPatternScore(
            pattern = FclPattern.FLAT_GOOD,
            score = score.coerceIn(0.0, 1.0),
            reason = "Respons is overwegend stabiel en binnen doel."
        )
    }

    private data class AxisContribution(
        val pattern: FclPattern,
        val signedScore: Double,
        val reason: String
    )

    // S/T/V stappen in procentpunten (±5%)
    // S (Sterkte)       = was height + mealHandling + persistence
    // T (Timing)        = was timing
    // V (Volhoudendheid)= was persistence + hypoProtection (invers)
    private fun evidenceVectorForPattern(pattern: FclPattern): StvAdjustment =
        when (pattern) {
            // Vroege spike: sneller reageren, meer kracht
            FclPattern.EARLY_SPIKE -> StvAdjustment(dTiming = +5, dSterkte = +5)
            // Late piek: eerder en sterker
            FclPattern.LATE_PEAK -> StvAdjustment(dSterkte = +5, dTiming = +5)
            // Vroege hypo: minder hard, minder snel
            FclPattern.EARLY_HYPO -> StvAdjustment(dSterkte = -5, dTiming = -5)
            // Late hypo: minder volhoudend (V omlaag = vroeger stoppen + meer hypo-rem)
            FclPattern.LATE_HYPO -> StvAdjustment(dSterkte = -5, dVolhoudendheid = -5)
            // Lang hoog: meer volhoudend + iets sterker
            FclPattern.LONG_HIGH_TAIL -> StvAdjustment(dVolhoudendheid = +5, dSterkte = +5)
            // Oscillerend: minder kracht, minder volhoudend
            FclPattern.OSCILLATING_RESPONSE -> StvAdjustment(dSterkte = -5, dVolhoudendheid = -5)
            FclPattern.FLAT_GOOD -> StvAdjustment()
            FclPattern.MIXED_UNCLEAR -> StvAdjustment()
        }

    private fun axisDelta(vector: StvAdjustment, axis: StvAxis): Int =
        when (axis) {
            StvAxis.STERKTE        -> vector.dSterkte
            StvAxis.TIMING         -> vector.dTiming
            StvAxis.VOLHOUDENDHEID -> vector.dVolhoudendheid
        }

    private fun buildAxisEvidence(scores: List<FclPatternScore>): List<FclAxisEvidence> {
        val axes = listOf(
            StvAxis.STERKTE, StvAxis.TIMING, StvAxis.VOLHOUDENDHEID
        )

        val flatGoodScore = scores.firstOrNull { it.pattern == FclPattern.FLAT_GOOD }?.score ?: 0.0
        val stabilityDampening = (1.0 - 0.70 * flatGoodScore).coerceIn(0.30, 1.0)

        return axes.map { axis ->
            val contributions = scores.mapNotNull { score ->
                val delta = axisDelta(evidenceVectorForPattern(score.pattern), axis)
                if (delta == 0) null
                else AxisContribution(pattern = score.pattern, signedScore = score.score * delta, reason = score.reason)
            }

            if (contributions.isEmpty()) {
                FclAxisEvidence(axis = axis, direction = 0, strength = 0.0, reason = "Geen sterk patroonsignaal voor ${axisDisplayName(axis)}.")
            } else {
                val positiveContributions = contributions.filter { it.signedScore > 0.0 }
                val negativeContributions = contributions.filter { it.signedScore < 0.0 }
                val positiveScore = positiveContributions.sumOf { it.signedScore }
                val negativeScore = negativeContributions.sumOf { abs(it.signedScore) }
                val rawNetScore = (positiveScore - negativeScore).coerceIn(-1.0, 1.0)
                val dampedNetScore = (rawNetScore * stabilityDampening).coerceIn(-1.0, 1.0)
                val strength = abs(dampedNetScore).coerceIn(0.0, 1.0)
                val direction = when {
                    strength < 0.18 -> 0
                    dampedNetScore > 0.0 -> 1
                    else -> -1
                }
                val dominantPositive = positiveContributions.maxByOrNull { abs(it.signedScore) }
                val dominantNegative = negativeContributions.maxByOrNull { abs(it.signedScore) }

                FclAxisEvidence(
                    axis = axis,
                    direction = direction,
                    strength = strength,
                    reason = buildAxisReason(
                        axis = axis, direction = direction, strength = strength,
                        flatGoodScore = flatGoodScore, positiveScore = positiveScore,
                        negativeScore = negativeScore,
                        dominantPositive = dominantPositive, dominantNegative = dominantNegative
                    )
                )
            }
        }
    }

    private fun buildAxisReason(
        axis: FclAxis, direction: Int, strength: Double, flatGoodScore: Double,
        positiveScore: Double, negativeScore: Double,
        dominantPositive: AxisContribution?, dominantNegative: AxisContribution?
    ): String {
        if (direction == 0) {
            return when {
                positiveScore > 0.0 && negativeScore > 0.0 -> "Tegengestelde signalen heffen elkaar grotendeels op."
                flatGoodScore >= 0.55 && max(positiveScore, negativeScore) > 0.0 -> "Signalen zijn aanwezig, maar blijven zwak."
                else -> "Geen sterk patroonsignaal."
            }
        }
        return if (direction > 0) upwardAxisReason(axis, dominantPositive?.pattern)
        else downwardAxisReason(axis, dominantNegative?.pattern)
    }

    private fun upwardAxisReason(axis: StvAxis, pattern: FclPattern?): String =
        when (axis) {
            StvAxis.STERKTE -> when (pattern) {
                FclPattern.LATE_PEAK       -> "Late pieken blijven te hoog — meer kracht nodig."
                FclPattern.LONG_HIGH_TAIL  -> "Glucose blijft langdurig verhoogd."
                else                       -> "Totale respons lijkt wat te zwak."
            }
            StvAxis.TIMING -> when (pattern) {
                FclPattern.LATE_PEAK   -> "De piek komt relatief laat — eerder reageren."
                FclPattern.EARLY_SPIKE -> "De glucose stijgt vroeg en snel."
                else                   -> "Timing lijkt wat te traag."
            }
            StvAxis.VOLHOUDENDHEID -> when (pattern) {
                FclPattern.LONG_HIGH_TAIL -> "Glucose blijft lang verhoogd na de piek."
                else                      -> "Aanhoudende correctie lijkt wat te zwak."
            }
        }

    private fun downwardAxisReason(axis: StvAxis, pattern: FclPattern?): String =
        when (axis) {
            StvAxis.STERKTE -> when (pattern) {
                FclPattern.EARLY_HYPO          -> "Vroege hypo-signalen wijzen op te sterke respons."
                FclPattern.LATE_HYPO           -> "Late hypo-signalen — te veel totale blootstelling."
                FclPattern.OSCILLATING_RESPONSE -> "Overshoot wijst op te agressieve respons."
                else                            -> "Totale respons lijkt te agressief."
            }
            StvAxis.TIMING         -> "Timing lijkt te vroeg of te snel."
            StvAxis.VOLHOUDENDHEID -> when (pattern) {
                FclPattern.LATE_HYPO -> "Correctie loopt te lang door — volhoudendheid verlagen."
                else                 -> "Volhoudendheid lijkt te sterk."
            }
        }

    private fun axisDisplayName(axis: StvAxis): String =
        when (axis) {
            StvAxis.STERKTE        -> "Sterkte"
            StvAxis.TIMING         -> "Timing"
            StvAxis.VOLHOUDENDHEID -> "Volhoudendheid"
        }

    private fun applyVector(current: StvState, vector: StvAdjustment, reason: String): List<FclAxisTransition> {
        return listOfNotNull(
            transitionPct(StvAxis.STERKTE,       current.sterkte,       vector.dSterkte,        80, 125, reason),
            transitionPct(StvAxis.TIMING,         current.timing,        vector.dTiming,         80, 120, reason),
            transitionPct(StvAxis.VOLHOUDENDHEID, current.volhoudendheid, vector.dVolhoudendheid, 70, 130, reason)
        )
    }

    private fun transitionPct(
        axis: StvAxis, fromPct: Int, delta: Int, min: Int, max: Int, reason: String
    ): FclAxisTransition? {
        if (delta == 0) return null
        val toPct = (fromPct + delta).coerceIn(min, max)
        if (toPct == fromPct) return null
        return FclAxisTransition(axis = axis, fromIndex = fromPct, toIndex = toPct, step = toPct - fromPct, reason = reason)
    }

    private fun buildSummary(
        pattern: FclPattern, transitions: List<FclAxisTransition>,
        confidence: Double, axisEvidence: List<FclAxisEvidence>
    ): String {
        val positiveStrong = axisEvidence.filter { it.direction > 0 && it.strength >= 0.45 }
        val positiveLight = axisEvidence.filter { it.direction > 0 && it.strength in 0.18..<0.45 }
        val negativeStrong = axisEvidence.filter { it.direction < 0 && it.strength >= 0.45 }
        val negativeLight = axisEvidence.filter { it.direction < 0 && it.strength in 0.18..<0.45 }
        val neutral = axisEvidence.filter { it.direction == 0 || it.strength < 0.18 }

        val signalParts = mutableListOf<String>()
        if (positiveStrong.isNotEmpty()) signalParts += "Duidelijke aanwijzing omhoog voor ${joinAxisNames(positiveStrong)}"
        if (positiveLight.isNotEmpty()) signalParts += "Lichte aanwijzing omhoog voor ${joinAxisNames(positiveLight)}"
        if (negativeStrong.isNotEmpty()) signalParts += "Duidelijke aanwijzing omlaag voor ${joinAxisNames(negativeStrong)}"
        if (negativeLight.isNotEmpty()) signalParts += "Lichte aanwijzing omlaag voor ${joinAxisNames(negativeLight)}"
        if (neutral.isNotEmpty()) signalParts += "Geen sterk signaal voor ${joinAxisNames(neutral)}"

        val hasOnlyLightSignals = positiveStrong.isEmpty() && negativeStrong.isEmpty() &&
            (positiveLight.isNotEmpty() || negativeLight.isNotEmpty())

        val dampeningText = if (pattern == FclPattern.FLAT_GOOD && hasOnlyLightSignals)
            "Het overwegend stabiele totaalbeeld zwakt deze signalen af." else null

        val confidenceText = "Confidence ${(confidence * 100).toInt()}%."

        return if (transitions.isEmpty()) {
            val intro = when (pattern) {
                FclPattern.FLAT_GOOD -> "Overwegend stabiel patroon."
                FclPattern.MIXED_UNCLEAR -> "Gemengd patroon."
                else -> "Patroon met onderliggende signalen."
            }
            buildList {
                add(intro)
                if (signalParts.isNotEmpty()) add(signalParts.joinToString(". ") + ".")
                dampeningText?.let { add(it) }
                add(confidenceText)
            }.joinToString(" ")
        } else {
            val transitionText = transitions.joinToString(", ") {
                "${axisSummaryName(it.axis)} ${if (it.step > 0) "+" else ""}${it.step}"
            }
            buildList {
                add("Dominant pattern: ${patternSummaryName(pattern)}.")
                add("Advies: $transitionText.")
                if (signalParts.isNotEmpty()) add(signalParts.joinToString(". ") + ".")
                dampeningText?.let { add(it) }
                add(confidenceText)
            }.joinToString(" ")
        }
    }

    private fun computeConfidence(episodeCount: Int, topScore: Double, secondScore: Double, minEpisodes: Int): Double {
        val volume = (episodeCount.toDouble() / minEpisodes).coerceIn(0.3, 1.0)
        val separation = (topScore - secondScore).coerceIn(0.0, 1.0)
        return (0.65 * topScore + 0.20 * separation + 0.15 * volume).coerceIn(0.0, 1.0)
    }

    private fun weight(value: Double, low: Double, high: Double): Double {
        if (high <= low) return 0.0
        return ((value - low) / (high - low)).coerceIn(0.0, 1.0)
    }

    private fun joinAxisNames(evidence: List<FclAxisEvidence>): String =
        evidence.joinToString(", ") { axisSummaryName(it.axis) }

    private fun axisSummaryName(axis: StvAxis): String =
        when (axis) {
            StvAxis.STERKTE        -> "Sterkte"
            StvAxis.TIMING         -> "Timing"
            StvAxis.VOLHOUDENDHEID -> "Volhoudendheid"
        }

    private fun patternSummaryName(pattern: FclPattern): String =
        when (pattern) {
            FclPattern.FLAT_GOOD -> "stabiel patroon"
            FclPattern.EARLY_SPIKE -> "vroege piek"
            FclPattern.LATE_PEAK -> "late piek"
            FclPattern.EARLY_HYPO -> "vroege hypo"
            FclPattern.LATE_HYPO -> "late hypo"
            FclPattern.LONG_HIGH_TAIL -> "lang hoge staart"
            FclPattern.OSCILLATING_RESPONSE -> "oscillerende respons"
            FclPattern.MIXED_UNCLEAR -> "gemengd / onduidelijk patroon"
        }

    /**
     * Leidt param-signalen af uit dominante patronen.
     *
     * Logica per parameter:
     * - peakIobBrakeSuppressThreshold:
     *   LATE_HYPO of OSCILLATING met hoge confidence → te hoge IOB → VERLAGEN
     *   FLAT_GOOD met hoge confidence → rem werkt goed, kan iets hoger → VERHOGEN
     *   EARLY_SPIKE → geen invloed op brake-drempel
     *
     * Dit is een patroon-gebaseerd signaal. De metrische berekening
     * (IOB@piek, post-piek daling) vindt plaats in ParamFijnafstellingScreen.
     * Beide signalen worden daar gecombineerd en samen getoond.
     */
    private fun buildParamSignals(
        scores: List<FclPatternScore>,
        dominantPattern: FclPattern,
        confidence: Double
    ): List<FclParamSignal> {

        val signals = mutableListOf<FclParamSignal>()

        // ── peakIobBrakeSuppressThreshold ──────────────────────────────────────
        val lateHypoScore    = scores.firstOrNull { it.pattern == FclPattern.LATE_HYPO }?.score  ?: 0.0
        val oscillatingScore = scores.firstOrNull { it.pattern == FclPattern.OSCILLATING_RESPONSE }?.score ?: 0.0
        val flatGoodScore    = scores.firstOrNull { it.pattern == FclPattern.FLAT_GOOD }?.score  ?: 0.0
        val earlyHypoScore   = scores.firstOrNull { it.pattern == FclPattern.EARLY_HYPO }?.score ?: 0.0

        val brakeDir = when {
            // Late hypo of oscillatie + sterke confidence → te veel IOB ophoping → eerder remmen
            (lateHypoScore + oscillatingScore * 0.7) > 0.35 && confidence > 0.45 ->
                ParamSignalDirection.VERLAGEN

            // Vroege hypo → te vroeg remmend + te weinig insuline → later remmen
            earlyHypoScore > 0.40 && confidence > 0.45 ->
                ParamSignalDirection.VERHOGEN

            // Stabiel patroon met hoge confidence → rem werkt goed, klein beetje ruimte
            flatGoodScore > 0.55 && confidence > 0.55 ->
                ParamSignalDirection.GOED

            else -> ParamSignalDirection.ONBEKEND
        }

        val brakeStrength = when (brakeDir) {
            ParamSignalDirection.VERLAGEN -> (lateHypoScore + oscillatingScore * 0.7).coerceIn(0.0, 1.0)
            ParamSignalDirection.VERHOGEN -> earlyHypoScore.coerceIn(0.0, 1.0)
            ParamSignalDirection.GOED     -> flatGoodScore.coerceIn(0.0, 1.0)
            ParamSignalDirection.ONBEKEND -> 0.0
        }

        val brakeReason = when (brakeDir) {
            ParamSignalDirection.VERLAGEN ->
                "Late hypo (${String.format("%.2f", lateHypoScore)}) / oscillatie (${String.format("%.2f", oscillatingScore)}) → " +
                    "te veel IOB ophoping rond de piek → rem eerder activeren."
            ParamSignalDirection.VERHOGEN ->
                "Vroege hypo (${String.format("%.2f", earlyHypoScore)}) → rem valt te vroeg in → iets verhogen geeft meer insulineruimte."
            ParamSignalDirection.GOED ->
                "Stabiel patroon (${String.format("%.2f", flatGoodScore)}) → piek-IOB rem werkt goed."
            ParamSignalDirection.ONBEKEND ->
                "Onvoldoende patroonbewijs voor een richting."
        }

        signals += FclParamSignal(
            technisch = "peakIobBrakeSuppressThreshold",
            direction = brakeDir,
            strength  = brakeStrength,
            reason    = brakeReason
        )

        return signals
    }

    private fun buildStats(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>
    ): FclAdvisorStats {
        val count = minOf(metrics.size, classes.size)
        if (count == 0) {
            return FclAdvisorStats(
                usedEpisodeCount = 0, avgTirPercent = 0, avgPeakBg = 0.0,
                avgRiseMagnitude = 0.0, avgDurationMinutes = 0, avgInsulinDelivered = 0.0,
                hyperPercent = 0, hypoPercent = 0, meetsGoalPercent = 0
            )
        }
        val usedMetrics = metrics.take(count)
        val usedClasses = classes.take(count)
        return FclAdvisorStats(
            usedEpisodeCount = count,
            avgTirPercent = usedClasses.map { it.tirPercent }.average().roundToInt(),
            avgPeakBg = usedMetrics.map { it.peakBg }.average(),
            avgRiseMagnitude = usedMetrics.map { it.riseMagnitude }.average(),
            avgDurationMinutes = usedMetrics.map { it.durationMinutes.toDouble() }.average().roundToInt(),
            avgInsulinDelivered = usedMetrics.map { it.totalInsulinDelivered }.average(),
            hyperPercent = ((usedClasses.count { it.hyper } * 100.0) / count).roundToInt(),
            hypoPercent = ((usedClasses.count { it.hypoEarly || it.hypoLate } * 100.0) / count).roundToInt(),
            meetsGoalPercent = ((usedClasses.count { it.meetsGoal } * 100.0) / count).roundToInt()
        )
    }

    private fun weightedAverage(metrics: List<EpisodeMetrics>, selector: (EpisodeMetrics) -> Double): Double {
        val totalWeight = metrics.sumOf { it.advisorWeight }.coerceAtLeast(1e-9)
        return metrics.sumOf { selector(it) * it.advisorWeight } / totalWeight
    }

    private fun weightedAverageNullable(metrics: List<EpisodeMetrics>, selector: (EpisodeMetrics) -> Double?): Double {
        val valid = metrics.mapNotNull { m -> selector(m)?.let { v -> v to m.advisorWeight } }
        if (valid.isEmpty()) return 0.0
        val totalWeight = valid.sumOf { it.second }.coerceAtLeast(1e-9)
        return valid.sumOf { it.first * it.second } / totalWeight
    }

    private fun weightedRate(
        metrics: List<EpisodeMetrics>,
        classes: List<EpisodeClassifier.EpisodeClassification>,
        predicate: (EpisodeClassifier.EpisodeClassification) -> Boolean
    ): Double {
        val totalWeight = metrics.sumOf { it.advisorWeight }.coerceAtLeast(1e-9)
        val matchedWeight = metrics.indices.sumOf { index ->
            if (predicate(classes[index])) metrics[index].advisorWeight else 0.0
        }
        return matchedWeight / totalWeight
    }
}