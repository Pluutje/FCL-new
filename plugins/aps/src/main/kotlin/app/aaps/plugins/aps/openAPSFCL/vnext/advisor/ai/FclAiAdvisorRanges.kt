package app.aaps.plugins.aps.openAPSFCL.vnext.advisor.ai

import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextConfigOverride

/**
 * ============================================================================
 * FCL AI-Advisor — Parameter-ranges (eenmalige bron van waarheid)
 * ============================================================================
 *
 * Dit zijn EXACT de ranges die als comment bij FCLvNextConfigOverride.ParamOverrides
 * staan. Ze worden hier herhaald i.p.v. via reflectie uitgelezen omdat Kotlin
 * geen comments runtime beschikbaar maakt — maar dit bestand is de plek waar
 * ze voortaan samen met die comments bijgewerkt moeten worden.
 *
 * Gebruikt door:
 * - FclAiAdvisorPromptBuilder: range in de prompt zodat het model nooit een
 *   waarde buiten bereik hoeft te "raden".
 * - FclAiAdvisorResponseParser: harde validatie — een voorstel buiten range
 *   wordt VERWORPEN (niet geclampt), want dat duidt op een rekenfout van het
 *   model die je wilt zien, niet stilzwijgend laten verdwijnen.
 *
 * LET OP (Ecko, 30/06/2026): als je een range in FCLvNextConfigOverride.kt
 * aanpast, moet die hier handmatig meeveranderen. Geen automatische sync —
 * bewuste keuze om reflectie/codegen te vermijden voor 17 velden.
 */
object FclAiAdvisorRanges {

    enum class ValueType { DOUBLE, INT }

    data class ParamSpec(
        val key: String,                 // moet matchen met ParamOverrides-veldnaam
        val labelNl: String,              // korte uitleg voor de prompt + UI-kaart
        val effectNl: String,             // wat verandert er concreet in het dosagegedrag als dit omhoog/omlaag gaat
        val type: ValueType,
        val min: Double,
        val max: Double,
        /** Maximale relatieve stap per voorstel t.o.v. de huidige waarde (bijv. 0.30 = max 30%). */
        val maxRelativeStep: Double = 0.30
    )

    val ALL: List<ParamSpec> = listOf(
        ParamSpec("peakPredictionThreshold", "Drempel (mmol) waarboven een piek-voorspelling meetelt",
            "Hoger = de automaat wacht op een hogere voorspelde piek voordat hij die als 'echte' piek meeneemt in dosering; lager = reageert sneller op kleinere voorspelde pieken.",
            ValueType.DOUBLE, 9.5, 14.0),
        ParamSpec("watchingFrontloadFrac", "Fractie van de geschatte dosis die als frontload tijdens WATCHING mag",
            "Hoger = meer insuline vroeg in de maaltijdrespons (frontload); lager = insuline meer gespreid over de hele episode.",
            ValueType.DOUBLE, 0.40, 0.90),
        ParamSpec("watchingMinDeltaToTarget", "Minimale afstand tot target (mmol) voor WFF-trigger",
            "Hoger = frontload start pas bij een grotere afstand boven target (voorzichtiger); lager = frontload triggert al dichter bij target.",
            ValueType.DOUBLE, 0.5, 3.5),
        ParamSpec("commitCooldownMinutes", "Minimale tijd (min) tussen twee commits",
            "Hoger = minder vaak een nieuwe dosis-commit toegestaan (rustiger, trager bijsturen); lager = sneller opeenvolgende commits mogelijk.",
            ValueType.INT, 5.0, 25.0),
        ParamSpec("peakPredictionHorizonH", "Hoe ver vooruit (uur) de piek wordt voorspeld",
            "Hoger = de voorspelling kijkt verder vooruit (kan eerder reageren maar is onzekerder); lager = kortere, preciezere horizon.",
            ValueType.DOUBLE, 0.8, 1.8),
        ParamSpec("iobStart", "IOB-ratio waarboven dosering voorzichtiger wordt opgebouwd",
            "Hoger = de automaat mag meer IOB opbouwen voordat hij voorzichtiger wordt; lager = eerder terughoudend bij oplopende IOB.",
            ValueType.DOUBLE, 0.25, 0.55),
        ParamSpec("peakIobBrakeSuppressThreshold", "IOB-ratio waarboven de piek-naderingsrem (soft) ingrijpt",
            "Hoger = de softBrake grijpt pas bij hogere IOB in (agressiever doseren toegestaan); lager = de rem grijpt eerder in — direct gekoppeld aan het 14:15-incident van vandaag.",
            ValueType.DOUBLE, 0.28, 0.60),
        ParamSpec("earlyBoostFactor", "Vermenigvuldiger op earlyTargetU bij Early Confidence Boost",
            "Hoger = grotere vroege bolus zodra de automaat zeker genoeg is van de stijging; lager = behoudender vroege dosering.",
            ValueType.DOUBLE, 1.0, 2.0),
        ParamSpec("earlyBoostMinConfidence", "Minimale confidence om Early Boost te activeren",
            "Hoger = Early Boost activeert pas bij een hoger zekerheidsniveau (minder vaak, maar betrouwbaarder); lager = activeert sneller, ook bij meer twijfel.",
            ValueType.DOUBLE, 0.40, 0.85),
        ParamSpec("earlyBoostMaxCommits", "Maximaal aantal Early Boost-commits per episode",
            "Hoger = meer opeenvolgende vroege boost-commits toegestaan per episode; lager = beperkt het aantal vroege boosts strikter.",
            ValueType.INT, 1.0, 5.0),
        ParamSpec("earlyRiseFracMin", "Minimale stijgingsfractie om als 'vroege stijging' te tellen",
            "Hoger = een stijging moet relatief sterker zijn voordat hij als 'vroege stijging' telt (voorzichtiger); lager = ook mildere stijgingen tellen al mee.",
            ValueType.DOUBLE, 0.35, 0.85),
        ParamSpec("peakMaxSlopeWeight", "Gewicht van max-slope in de piek-schatting",
            "Hoger = de piek-schatting laat zich sterker leiden door de steilste recente stijging; lager = stabielere, minder slope-gevoelige schatting.",
            ValueType.DOUBLE, 0.0, 0.60),
        ParamSpec("lateCommitDecayFactor", "Afbouwfactor voor late commits richting de piek",
            "Hoger = late commits (dicht bij de piek) worden sterker afgebouwd/verkleind; lager = late commits blijven dichter bij hun oorspronkelijke grootte.",
            ValueType.DOUBLE, 0.0, 1.0),
        ParamSpec("lateCommitDecayThreshold", "Drempel waarboven late-commit-afbouw begint",
            "Hoger = de afbouw start pas later (dichter bij de piek); lager = de afbouw begint eerder in de episode.",
            ValueType.DOUBLE, 0.30, 0.70),
        ParamSpec("sustainedRiseSlopeMin", "Minimale slope (mmol/min) voor 'aanhoudende stijging'",
            "Hoger = er is een steilere stijging nodig voordat iets als 'aanhoudend' geldt; lager = ook geleidelijkere stijgingen tellen mee.",
            ValueType.DOUBLE, 0.15, 0.80),
        ParamSpec("sustainedRiseMinTarget", "Minimale duur (min) voor 'aanhoudende stijging'",
            "Hoger = een stijging moet langer aanhouden voordat hij als 'aanhoudend' telt; lager = kortere stijgingen tellen al mee.",
            ValueType.INT, 5.0, 20.0),
        ParamSpec("earlyPeakBiasMmol", "Vroege piek-correctie (mmol)",
            "Hoger = de piek-voorspelling wordt structureel naar boven bijgesteld (compenseert systematische onderschatting); lager = minder/geen correctie.",
            ValueType.DOUBLE, 0.0, 1.5)
    )

    val byKey: Map<String, ParamSpec> = ALL.associateBy { it.key }

    fun isInRange(spec: ParamSpec, value: Double): Boolean =
        value in spec.min..spec.max

    fun withinMaxStep(spec: ParamSpec, currentValue: Double, proposedValue: Double): Boolean {
        if (currentValue == 0.0) return true // deling-door-nul guard; range-check vangt extremen al af
        val relChange = kotlin.math.abs(proposedValue - currentValue) / kotlin.math.abs(currentValue)
        return relChange <= spec.maxRelativeStep
    }

    /** Compact, voor de prompt: "key: label — effect [min–max]". */
    fun rangesAsPromptBlock(): String =
        ALL.joinToString("\n") { spec ->
            val unit = if (spec.type == ValueType.INT) "${spec.min.toInt()}–${spec.max.toInt()}" else "${spec.min}–${spec.max}"
            "- ${spec.key}: ${spec.labelNl}. ${spec.effectNl} [geldig bereik: $unit]"
        }

    /** Haalt de huidige waarde van een veld op uit ParamOverrides via expliciete when (geen reflectie). */
    fun currentValue(key: String, params: FCLvNextConfigOverride.ParamOverrides): Double? = when (key) {
        "peakPredictionThreshold" -> params.peakPredictionThreshold
        "watchingFrontloadFrac" -> params.watchingFrontloadFrac
        "watchingMinDeltaToTarget" -> params.watchingMinDeltaToTarget
        "commitCooldownMinutes" -> params.commitCooldownMinutes?.toDouble()
        "peakPredictionHorizonH" -> params.peakPredictionHorizonH
        "iobStart" -> params.iobStart
        "peakIobBrakeSuppressThreshold" -> params.peakIobBrakeSuppressThreshold
        "earlyBoostFactor" -> params.earlyBoostFactor
        "earlyBoostMinConfidence" -> params.earlyBoostMinConfidence
        "earlyBoostMaxCommits" -> params.earlyBoostMaxCommits?.toDouble()
        "earlyRiseFracMin" -> params.earlyRiseFracMin
        "peakMaxSlopeWeight" -> params.peakMaxSlopeWeight
        "lateCommitDecayFactor" -> params.lateCommitDecayFactor
        "lateCommitDecayThreshold" -> params.lateCommitDecayThreshold
        "sustainedRiseSlopeMin" -> params.sustainedRiseSlopeMin
        "sustainedRiseMinTarget" -> params.sustainedRiseMinTarget?.toDouble()
        "earlyPeakBiasMmol" -> params.earlyPeakBiasMmol
        else -> null
    }

    /** Bouwt een ParamOverrides met precies één veld gezet — voor post() naar FclOverrideBridge. */
    fun singleFieldOverride(key: String, value: Double): FCLvNextConfigOverride.ParamOverrides {
        val spec = byKey[key] ?: error("Onbekende AI-advisor parameter: $key")
        return when (key) {
            "peakPredictionThreshold" -> FCLvNextConfigOverride.ParamOverrides(peakPredictionThreshold = value)
            "watchingFrontloadFrac" -> FCLvNextConfigOverride.ParamOverrides(watchingFrontloadFrac = value)
            "watchingMinDeltaToTarget" -> FCLvNextConfigOverride.ParamOverrides(watchingMinDeltaToTarget = value)
            "commitCooldownMinutes" -> FCLvNextConfigOverride.ParamOverrides(commitCooldownMinutes = value.toInt())
            "peakPredictionHorizonH" -> FCLvNextConfigOverride.ParamOverrides(peakPredictionHorizonH = value)
            "iobStart" -> FCLvNextConfigOverride.ParamOverrides(iobStart = value)
            "peakIobBrakeSuppressThreshold" -> FCLvNextConfigOverride.ParamOverrides(peakIobBrakeSuppressThreshold = value)
            "earlyBoostFactor" -> FCLvNextConfigOverride.ParamOverrides(earlyBoostFactor = value)
            "earlyBoostMinConfidence" -> FCLvNextConfigOverride.ParamOverrides(earlyBoostMinConfidence = value)
            "earlyBoostMaxCommits" -> FCLvNextConfigOverride.ParamOverrides(earlyBoostMaxCommits = value.toInt())
            "earlyRiseFracMin" -> FCLvNextConfigOverride.ParamOverrides(earlyRiseFracMin = value)
            "peakMaxSlopeWeight" -> FCLvNextConfigOverride.ParamOverrides(peakMaxSlopeWeight = value)
            "lateCommitDecayFactor" -> FCLvNextConfigOverride.ParamOverrides(lateCommitDecayFactor = value)
            "lateCommitDecayThreshold" -> FCLvNextConfigOverride.ParamOverrides(lateCommitDecayThreshold = value)
            "sustainedRiseSlopeMin" -> FCLvNextConfigOverride.ParamOverrides(sustainedRiseSlopeMin = value)
            "sustainedRiseMinTarget" -> FCLvNextConfigOverride.ParamOverrides(sustainedRiseMinTarget = value.toInt())
            "earlyPeakBiasMmol" -> FCLvNextConfigOverride.ParamOverrides(earlyPeakBiasMmol = value)
            else -> error("Onbekende AI-advisor parameter: ${spec.key}")
        }
    }
}
