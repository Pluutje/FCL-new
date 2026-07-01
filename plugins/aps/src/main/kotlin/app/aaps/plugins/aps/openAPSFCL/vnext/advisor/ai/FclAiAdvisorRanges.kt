package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride

/**
 * FCL AI-Advisor — Parameter-ranges (eenmalige bron van waarheid)
 * (01/07/2026, Ecko — herschreven)
 *
 * WIJZIGINGEN t.o.v. vorige versie:
 *  - 6 parameters verwijderd die de AI NIET mag aanpassen:
 *      peakIobBrakeSuppressThreshold  → veiligheidsrem, nooit omhoog bij overshoot
 *      peakPredictionThreshold        → interne peak-detectie, geen timing
 *      peakPredictionHorizonH         → interne berekening
 *      peakMaxSlopeWeight             → interne berekening
 *      earlyPeakBiasMmol              → correctieparam, geen timing
 *      iobStart                       → IOB-berekening, geen timing
 *
 *  - softMax / hardMax (en softMin / hardMin voor min-gebonden params):
 *      De learner werkt binnen softMax. Bij confidence >= CEILING_BUST_CONFIDENCE
 *      én currentValue >= softMax - epsilon mag de AI tot hardMax gaan.
 *      Dit werkt analoog aan de agressiviteitsschuif die ook bovenop de geleerde
 *      waarde werkt: de AI is een extra laag boven de learner, niet een vervanging.
 */
object FclAiAdvisorRanges {

    enum class ValueType { DOUBLE, INT }

    /** Minimale confidence voor ceiling-busting (voorbij softMax/onder softMin). */
    const val CEILING_BUST_CONFIDENCE = 0.75

    data class ParamSpec(
        val key: String,
        val labelNl: String,
        val effectNl: String,
        val type: ValueType,
        val min: Double,       // absoluut minimum (hardMin)
        val max: Double,       // absoluut maximum (hardMax)
        /** Normale maximum die de learner ook gebruikt. AI mag hier alleen overheen
         *  bij confidence >= CEILING_BUST_CONFIDENCE EN currentValue >= softMax - ε. */
        val softMax: Double = max,
        /** Normale minimum. AI mag hier alleen onderzitten bij hoge confidence. */
        val softMin: Double = min,
        val maxRelativeStep: Double = 0.30
    )

    val ALL: List<ParamSpec> = listOf(

        // ── Timing & frontload (kern van de rode draad) ───────────────────────

        ParamSpec("watchingFrontloadFrac",
                  "Fractie van de geschatte dosis die als frontload tijdens WATCHING mag",
                  "Hoger = meer insuline vroeg in de maaltijdrespons (frontload); lager = meer gespreid.",
                  ValueType.DOUBLE, min = 0.40, max = 0.95,  // max = hardMax (0.95)
                  softMax = 0.90   // learner ceiling — AI mag 0.90–0.95 bij hoge confidence
        ),

        ParamSpec("watchingMinDeltaToTarget",
                  "Minimale afstand tot target (mmol) voor WFF-trigger",
                  "Hoger = frontload start pas bij grotere afstand boven target (voorzichtiger); lager = frontload triggert eerder.",
                  ValueType.DOUBLE, min = 0.5, max = 3.5),

        ParamSpec("commitCooldownMinutes",
                  "Minimale tijd (min) tussen twee commits",
                  "Lager = sneller opeenvolgende commits mogelijk (cyclus = 5 min); hoger = rustiger bijsturen.",
                  ValueType.INT, min = 4.0, max = 25.0,
                  softMin = 5.0),  // 4 min is hardMin — alleen AI bij hoge confidence; garandeert 2× per 2 cycli

        // ── Early Boost (hoeveel en hoe vroeg) ───────────────────────────────

        ParamSpec("earlyBoostFactor",
                  "Vermenigvuldiger op earlyTargetU bij Early Confidence Boost",
                  "Hoger = grotere vroege bolus zodra de automaat zeker genoeg is van de stijging; lager = behoudender vroege dosering.",
                  ValueType.DOUBLE, min = 1.0, max = 2.50,  // max = hardMax (2.50)
                  softMax = 2.20  // = EB_BOOST_MAX in DFLearner — AI mag 2.20–2.50 bij hoge confidence EN learner op 2.20
        ),

        ParamSpec("earlyBoostMinConfidence",
                  "Minimale confidence om Early Boost te activeren",
                  "Hoger = Early Boost activeert pas bij hoger zekerheidsniveau; lager = activeert sneller, ook bij meer twijfel.",
                  ValueType.DOUBLE, min = 0.40, max = 0.85),

        ParamSpec("earlyBoostMaxCommits",
                  "Maximaal aantal Early Boost-commits per episode",
                  "Hoger = meer vroege boost-commits toegestaan; lager = striktere beperking.",
                  ValueType.INT, min = 1.0, max = 6.0,
                  softMax = 5.0),  // learner ceiling

        ParamSpec("earlyRiseFracMin",
                  "Minimale stijgingsfractie om als 'vroege stijging' te tellen",
                  "Hoger = stijging moet relatief sterker zijn (voorzichtiger); lager = ook mildere stijgingen tellen.",
                  ValueType.DOUBLE, min = 0.35, max = 0.85),

        // ── Late commits & decay (hoe snel afbouwen richting piek) ───────────

        ParamSpec("lateCommitDecayFactor",
                  "Afbouwfactor voor late commits richting de piek",
                  "Hoger = late commits sterker verkleind; lager = late commits dichter bij oorspronkelijke grootte.",
                  ValueType.DOUBLE, min = 0.0, max = 1.0),

        ParamSpec("lateCommitDecayThreshold",
                  "Drempel waarboven late-commit-afbouw begint",
                  "Hoger = afbouw start later; lager = afbouw begint eerder in de episode.",
                  ValueType.DOUBLE, min = 0.30, max = 0.70),

        // ── Vasthoudendheid / aanhoudende stijging (vette maaltijden) ─────────

        ParamSpec("sustainedRiseSlopeMin",
                  "Minimale slope (mmol/min) voor 'aanhoudende stijging'",
                  "Lager = ook geleidelijkere (vette/eiwitrijke maaltijd) stijgingen tellen mee; hoger = alleen steile stijgingen.",
                  ValueType.DOUBLE, min = 0.10, max = 0.80,
                  softMin = 0.15)  // learner floor — AI mag hier onder bij hoge confidence
    )

    val byKey: Map<String, ParamSpec> = ALL.associateBy { it.key }

    // ── Validatiehulpers ──────────────────────────────────────────────────────

    /** Valt de waarde binnen het absolute bereik [hardMin, hardMax]? */
    fun isInRange(spec: ParamSpec, value: Double): Boolean = value in spec.min..spec.max

    /** Is de stap klein genoeg (relatieve stap ≤ maxRelativeStep)? */
    fun withinMaxStep(spec: ParamSpec, currentValue: Double, proposedValue: Double): Boolean {
        if (currentValue == 0.0) return true
        val rel = kotlin.math.abs(proposedValue - currentValue) / kotlin.math.abs(currentValue)
        return rel <= spec.maxRelativeStep
    }

    /**
     * Mag de AI de softMax/softMin overschrijden?
     * Vereist: confidence >= CEILING_BUST_CONFIDENCE én currentValue al op de soft-grens.
     */
    fun isCeilingBustAllowed(
        spec: ParamSpec,
        currentValue: Double,
        proposedValue: Double,
        confidence: Double
    ): Boolean {
        if (confidence < CEILING_BUST_CONFIDENCE) return false
        val epsilon = 0.001
        val bustingMax = proposedValue > spec.softMax + epsilon
        val bustingMin = proposedValue < spec.softMin - epsilon
        return when {
            bustingMax -> currentValue >= spec.softMax - epsilon // learner al op ceiling
            bustingMin -> currentValue <= spec.softMin + epsilon // learner al op floor
            else -> true // binnen softbereik — altijd OK
        }
    }

    /** Prompt-blok: key, label, effect, bereik (soft/hard indien van toepassing). */
    fun rangesAsPromptBlock(): String = ALL.joinToString("\n") { spec ->
        val range = if (spec.type == ValueType.INT)
            "${spec.min.toInt()}–${spec.max.toInt()}"
        else "${spec.min}–${spec.max}"
        val softNote = buildString {
            if (spec.softMax < spec.max) append("; softMax=${spec.softMax} (leergrens; overschrijdbaar bij conf≥${CEILING_BUST_CONFIDENCE} en huidige waarde=softMax)")
            if (spec.softMin > spec.min) append("; softMin=${spec.softMin} (leergrens; onderschrijdbaar bij conf≥${CEILING_BUST_CONFIDENCE} en huidige waarde=softMin)")
        }
        "- ${spec.key}: ${spec.labelNl}. ${spec.effectNl} [bereik: $range$softNote]"
    }

    /** Bouwt een ParamOverrides met precies één veld — voor post() naar FclOverrideBridge. */
    fun singleFieldOverride(key: String, value: Double): FCLvNextConfigOverride.ParamOverrides = when (key) {
        "watchingFrontloadFrac"   -> FCLvNextConfigOverride.ParamOverrides(watchingFrontloadFrac = value)
        "watchingMinDeltaToTarget"-> FCLvNextConfigOverride.ParamOverrides(watchingMinDeltaToTarget = value)
        "commitCooldownMinutes"   -> FCLvNextConfigOverride.ParamOverrides(commitCooldownMinutes = value.toInt())
        "earlyBoostFactor"        -> FCLvNextConfigOverride.ParamOverrides(earlyBoostFactor = value)
        "earlyBoostMinConfidence" -> FCLvNextConfigOverride.ParamOverrides(earlyBoostMinConfidence = value)
        "earlyBoostMaxCommits"    -> FCLvNextConfigOverride.ParamOverrides(earlyBoostMaxCommits = value.toInt())
        "earlyRiseFracMin"        -> FCLvNextConfigOverride.ParamOverrides(earlyRiseFracMin = value)
        "lateCommitDecayFactor"   -> FCLvNextConfigOverride.ParamOverrides(lateCommitDecayFactor = value)
        "lateCommitDecayThreshold"-> FCLvNextConfigOverride.ParamOverrides(lateCommitDecayThreshold = value)
        "sustainedRiseSlopeMin"   -> FCLvNextConfigOverride.ParamOverrides(sustainedRiseSlopeMin = value)
        else -> error("Onbekende AI-advisor parameter: $key")
    }
}