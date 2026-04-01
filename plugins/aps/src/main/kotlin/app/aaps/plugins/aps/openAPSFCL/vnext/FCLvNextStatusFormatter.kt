package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey

import app.aaps.core.interfaces.meal.MealIntentRepository
import app.aaps.core.interfaces.meal.MealIntentType
import app.aaps.plugins.aps.openAPSFCL.vnext.meal.PreBolusController


private const val UI_EPISODES_TO_SHOW = 5

data class FclUiSnapshot(
    val bgNow: Double,
    val iob: Double,
    val delta5m: Double?,
    val slopeHr: Double?,
    val predictedPeak: Double?
)


class FCLvNextStatusFormatter(
    private val prefs: Preferences,
    private val mealIntentRepository: MealIntentRepository,
    private val preBolusController: PreBolusController
){


    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }
    private fun profileLabel(value: String): String =
        when (value) {
            "VERY_STRICT" -> "\uD83D\uDEE1\uFE0F Zeer voorzichtig"
            "STRICT"      -> "\uD83E\uDDEF Voorzichtig"
            "BALANCED"    -> "⚖\uFE0F Gebalanceerd"
            "AGGRESSIVE"   -> "\uD83D\uDE80 Actief"
            "VERY_AGGRESSIVE"  -> "\uD83D\uDD25 Zeer actief"
            else          -> value
        }
    private fun HypoProtectionLabel(value: String): String =
        when (value) {
            "MINIMAL" -> "\uD83D\uDFE2 Minimaal"
            "RELAXED"      -> "\uD83D\uDD35 Licht"
            "BALANCED"    -> "⚖\uFE0F Gebalanceerd"
            "SAFE"   -> "\uD83D\uDFE1 Verhoogd"
            "ULTRA_SAFE"  -> "\uD83D\uDD34 Maximaal"
            else          -> value
        }
    private fun NightResponsLabel(value: String): String =
        when (value) {
            "VERY_GUARDED" -> "\uD83D\uDED1 Zeer terughoudend"
            "GUARDED"      -> "\uD83E\uDDEF Terughoudend"
            "BALANCED"    -> "⚖\uFE0F Gebalanceerd"
            "RESPONSIVE"   -> "\uD83C\uDF19 Reageert eerder"
            "PROACTIVE"  -> "\uD83D\uDE80 Proactief"
            else          -> value
        }

    private fun mealDetectLabel(value: String): String =
        when (value) {
            "VERY_SLOW"  -> "\uD83D\uDC22 Zeer laat"
            "SLOW"       -> "\uD83D\uDC0C Laat"
            "MODERATE"   -> "⚖\uFE0F Normaal"
            "FAST"       -> "⚡ Snel"
            "VERY_FAST" -> "\uD83D\uDEA8 Zeer snel"
            else         -> value
        }
    private fun mealLabel(value: String): String =
        when (value) {
            "VERY_CONSERVATIVE"  -> "\uD83D\uDED1 Zeer voorzichtig"
            "CONSERVATIVE"       -> "\uD83D\uDC22 Voorzichtig"
            "BALANCED"   -> "⚖\uFE0F Gebalanceerd"
            "ANTICIPATORYT"       -> "⚡ Anticiperend"
            "AGGRESSIVE" -> "\uD83D\uDE80 Agressief"
            else         -> value
        }

    private fun correctionStyleLabel(value: String): String =
        when (value) {
            "VERY_CAUTIOUS" -> "\uD83D\uDED1 Zeer terughoudend"
            "CAUTIOUS"      -> "\uD83E\uDDEF Voorzichtig"
            "NORMAL"        -> "⚖\uFE0F Normaal"
            "PERSISTENT"    -> "\uD83D\uDD01 Vasthoudend"
            "VERY_PERSISTENT" -> "\uD83D\uDD02 Zeer vasthoudend"
            else            -> value
        }

    private fun doseDistributionLabel(value: String): String =
        when (value) {
            "VERY_SMOOTH"        -> "\uD83C\uDF0A Ultra smooth"
            "SMOOTH"      -> "\uD83E\uDEE7 Smooth"
            "BALANCED"        -> "⚖\uFE0F Balanced"
            "PULSED" -> "\uD83D\uDD28 Pulsed"
            "VERY_PULSED" -> "⚡ Ultra pulsed"
            else            -> value
        }

    private fun mealIntentLabel(type: MealIntentType): String =
        when (type) {
            MealIntentType.SMALL  -> "Kleine maaltijd"
            MealIntentType.NORMAL -> "Normale maaltijd"
            MealIntentType.LARGE  -> "Grote maaltijd"
            MealIntentType.SNACK  -> "Snack / Borrel"
            else                  -> "—"
        }




    private fun extractProfileAdviceLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 14 && t.substring(0, 14) == "PROFILE ADVICE:") {
                return t
            }
        }
        return null
    }

    private fun extractProfileReasonLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 15 && t.substring(0, 15) == "PROFILE REASON:") {
                return t
            }
        }
        return null
    }

    private fun extractPersistLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val out = ArrayList<String>(3)
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 7 && t.substring(0, 7) == "PERSIST") {
                out.add(t)
            }
        }
        return out
    }

    private fun buildMealIntentBlock(): String? {
        val now = DateTime.now()

        val snapshot = preBolusController.uiSnapshot(now)
            ?: return null

        return """
🍽️ MAALTIJD-INTENT
─────────────────────
• Type        : ${mealIntentLabel(snapshot.mealType)}
• Status      : ${if (snapshot.remainingU > 0.0) "Bolus loopt nog" else "Bolus afgegeven"}
• Pre-bolus   : ${"%.2f".format(snapshot.totalU)} U
• Gegeven     : ${"%.2f".format(snapshot.deliveredU)} U
• Resterend   : ${"%.2f".format(snapshot.remainingU)} U
• Gestart     : ${snapshot.minutesSinceArmed} min geleden
• Geldig tot  : ${DateTime(snapshot.validUntil).toString("HH:mm")}
  (nog ${snapshot.minutesRemaining} min)
• Verval      : ${"%.2f".format(snapshot.decayFactor)}

""".trimIndent()
    }


    private fun buildFclBlock(
        advice: FCLvNextAdvice?,
        ui: FclUiSnapshot,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean
    ): String {

        if (advice == null) return "Geen FCL advies"

        val sb = StringBuilder()
        sb.append("🧠 FCL vNext\n")
        sb.append("─────────────────────\n")

        sb.append("📈 Situatie\n")
        sb.append("─────────────────────\n")
        sb.append("• Glucose: ${"%.1f".format(ui.bgNow)} mmol/L\n")
        sb.append("• IOB: ${"%.2f".format(ui.iob)} U\n")

        ui.delta5m?.let {
            sb.append("• Verandering (5m): ${"%.2f".format(it)} mmol/L\n")
        }

        advice.peakState?.let { state ->
            val uitleg = when (state) {
                "IDLE"      -> "Geen actieve stijging"
                "WATCHING"  -> "Sterke stijging actief"
                "CONFIRMED" -> "Piek bevestigd – afremming verwacht"
                else        -> state
            }
            sb.append("\n")
            sb.append("• FCL piekstatus: $uitleg\n")
        }

        advice.predictedPeak?.let {
            sb.append("• Verwachte FCL-piek: ${"%.1f".format(it)} mmol/L\n")
        }

        sb.append("\n")

        sb.append("💉 Advies\n")
        sb.append("─────────────────────\n")
        if (!shouldDeliver || (bolusAmount == 0.0 && basalRate == 0.0)) {
            sb.append("• Geen extra insuline nodig\n")
        } else {
            val total = bolusAmount + (basalRate * (5.0 / 60.0))
            sb.append("• Extra insuline nu: ${"%.2f".format(total)} U\n")
        }

        sb.append("\n⏳ Timing\n")
        sb.append("─────────────────────\n")
        sb.append(if (shouldDeliver) "• Toediening nu\n" else "• Geen toediening\n")

        return sb.toString().trimEnd()
    }


    private fun minutesBetween(a: DateTime, b: DateTime): Long =
        (b.millis - a.millis) / 60000


    // ── Fijnafstelling status-sectie ─────────────────────────────────────

    /**
     * Bouwt een compacte sectie met de zes Groep A parameters zoals ze
     * werkelijk actief zijn in AAPS, plus default en afwijking.
     *
     * Wordt aangeroepen vanuit buildStatus() met de final FCLvNextConfig.
     *
     * Opmaak per regel:
     *   • Naam  : actief  (default / Δ afwijking  [bron])
     *
     * Bron-labels:
     *   "default"  → waarde gelijk aan hardcoded default
     *   "as-keten" → gewijzigd door een as-functie (applyMealHandling etc.)
     *   "override" → expliciet overschreven via param_overrides JSON
     *
     * Voor de bron gebruiken we FCLvNextActiveParamsWriter.Defaults om te
     * vergelijken — die is de enige bron van default-kennis.
     */
    private fun buildFijnafstellingBlock(config: FCLvNextConfig): String {
        val D = FCLvNextActiveParamsWriter.Defaults

        fun fmtDouble(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
        fun fmtDelta(active: Double, default: Double): String {
            val d = active - default
            return when {
                kotlin.math.abs(d) < 0.001 -> "= default"
                d > 0  -> "Δ +${String.format(java.util.Locale.US, "%.2f", d)}"
                else   -> "Δ ${String.format(java.util.Locale.US, "%.2f", d)}"
            }
        }
        fun srcLabel(active: Double, default: Double, hasOverride: Boolean): String = when {
            kotlin.math.abs(active - default) < 0.001 -> "default"
            hasOverride                                -> "override"
            else                                       -> "as-keten"
        }
        fun srcLabelInt(active: Int, default: Int, hasOverride: Boolean): String = when {
            active == default -> "default"
            hasOverride       -> "override"
            else              -> "as-keten"
        }

        // Lees het override-bestand om te weten welke params een override hebben
        val ovr = FCLvNextConfigOverride.load()?.paramOverrides

        // Hulpfunctie: schrijft elke parameter op twee regels
        //   Regel 1: "• Naam  : actief eenheid"
        //   Regel 2: "  ↳ def XX, Δ YY  [bron]"
        val sb = StringBuilder()
        sb.appendLine("⚙️ FIJNAFSTELLING - ACTIEF")
        sb.appendLine("─────────────────────")

        fun paramRegel(
            naam: String,
            actief: String,
            eenheid: String,
            defVal: String,
            delta: String,
            bron: String,
            isLast: Boolean = false
        ) {
            sb.appendLine("• $naam: $actief $eenheid".trimEnd())
            val regel2 = "  ↳ def $defVal, $delta  [$bron]"
            if (isLast) sb.append(regel2) else sb.appendLine(regel2)
        }


        // peakPredictionThreshold
        val ptActive  = config.peakPredictionThreshold
        val ptDefault = D.PEAK_PREDICTION_THRESHOLD
        val ptHasOvr  = ovr?.peakPredictionThreshold != null
        paramRegel("Piekdrempel",    "${fmtDouble(ptActive)} mmol", "",
                   "${fmtDouble(ptDefault)} mmol", fmtDelta(ptActive, ptDefault), srcLabel(ptActive, ptDefault, ptHasOvr))

        // watchingFrontloadFrac
        val ffActive  = config.watchingFrontloadFrac
        val ffDefault = D.WATCHING_FRONTLOAD_FRAC
        val ffHasOvr  = ovr?.watchingFrontloadFrac != null
        paramRegel("Frontload frac", fmtDouble(ffActive), "",
                   fmtDouble(ffDefault), fmtDelta(ffActive, ffDefault), srcLabel(ffActive, ffDefault, ffHasOvr))

        // watchingMinDeltaToTarget
        val dtActive  = config.watchingMinDeltaToTarget
        val dtDefault = D.WATCHING_MIN_DELTA_TARGET
        val dtHasOvr  = ovr?.watchingMinDeltaToTarget != null
        paramRegel("Delta-drempel",  "${fmtDouble(dtActive)} mmol", "",
                   "${fmtDouble(dtDefault)} mmol", fmtDelta(dtActive, dtDefault), srcLabel(dtActive, dtDefault, dtHasOvr))

        // commitCooldownMinutes
        val ccActive  = config.commitCooldownMinutes
        val ccDefault = D.COMMIT_COOLDOWN_MINUTES
        val ccHasOvr  = ovr?.commitCooldownMinutes != null
        paramRegel("Commit cooldown", "$ccActive min", "",
                   "$ccDefault min", fmtDelta(ccActive.toDouble(), ccDefault.toDouble()), srcLabelInt(ccActive, ccDefault, ccHasOvr))

        // peakPredictionHorizonH
        val phActive  = config.peakPredictionHorizonH
        val phDefault = D.PEAK_PREDICTION_HORIZON_H
        val phHasOvr  = ovr?.peakPredictionHorizonH != null
        paramRegel("Piek horizon",   "${fmtDouble(phActive)} uur", "",
                   "${fmtDouble(phDefault)} uur", fmtDelta(phActive, phDefault), srcLabel(phActive, phDefault, phHasOvr))

        // iobStart
        val isActive  = config.iobStart
        val isDefault = D.IOB_START
        val isHasOvr  = ovr?.iobStart != null
        paramRegel("IOB remdrempel", fmtDouble(isActive), "",
                   fmtDouble(isDefault), fmtDelta(isActive, isDefault), srcLabel(isActive, isDefault, isHasOvr), isLast = true)

        return sb.toString().trimEnd()
    }


    private fun nextFaster(current: String) = when (current) {
        "VERY_SLOW" -> "SLOW"
        "SLOW" -> "MODERATE"
        "MODERATE" -> "FAST"
        "FAST" -> "VERY_FAST"
        else -> null
    }

    private fun nextSlower(current: String) = when (current) {
        "VERY_FAST" -> "FAST"
        "FAST" -> "MODERATE"
        "MODERATE" -> "SLOW"
        "SLOW" -> "VERY_SLOW"
        else -> null
    }

    private fun nextMoreConservative(current: String) = when (current) {
        "VERY_AGGRESSIVE" -> "AGGRESSIVE"
        "AGGRESSIVE" -> "BALANCED"
        "BALANCED" -> "STRICT"
        "STRICT" -> "VERY_STRICT"
        else -> null
    }

    private fun nextMoreAggressive(current: String) = when (current) {
        "VERY_STRICT" -> "STRICT"
        "STRICT" -> "BALANCED"
        "BALANCED" -> "AGGRESSIVE"
        "AGGRESSIVE" -> "VERY_AGGRESSIVE"
        else -> null
    }

    private fun nextMorePersistent(current: String) = when (current) {
        "VERY_CAUTIOUS" -> "CAUTIOUS"
        "CAUTIOUS" -> "NORMAL"
        "NORMAL" -> "PERSISTENT"
        "PERSISTENT" -> "VERY_PERSISTENT"
        else -> null
    }

    private fun nextMoreCautious(current: String) = when (current) {
        "VERY_PERSISTENT" -> "PERSISTENT"
        "PERSISTENT" -> "NORMAL"
        "NORMAL" -> "CAUTIOUS"
        "CAUTIOUS" -> "VERY_CAUTIOUS"
        else -> null
    }


    fun buildStatus(
        isNight: Boolean,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        ui: FclUiSnapshot,
        activityLog: String?,
        resistanceLog: String?,
        metricsText: String?,
        activeConfig: FCLvNextConfig? = null       // ← nieuw, optioneel voor backward compat
    ): String {

        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${DateTime.now().toString("HH:mm:ss")}
• Advies actief: ${if (shouldDeliver) "JA" else "NEE"}
• Bolus: ${"%.2f".format(bolusAmount)} U
• Basaal: ${"%.2f".format(basalRate)} U/h

🧪 LAATSTE DOSIS
─────────────────────
${formatDeliveryHistory(advice?.let { deliveryHistory.toList() })}
""".trimIndent()

        val fclCore = buildFclBlock(
            advice = advice,
            ui = ui,
            bolusAmount = bolusAmount,
            basalRate = basalRate,
            shouldDeliver = shouldDeliver
        )

        val mealIntentBlock = buildMealIntentBlock()

        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
${activityLog ?: "Geen activiteitdata"}
""".trimIndent()

        val resistanceStatus = """
🧬 AUTO-SENS
─────────────────────
${resistanceLog ?: "Geen resistentie-log"}
""".trimIndent()

        val metricsStatus = """
            
📊 GLUCOSE STATISTIEKEN
─────────────────────
${metricsText ?: "Nog geen data"}
""".trimIndent()

        // Fijnafstelling sectie — alleen als activeConfig meegegeven is
        val fijnafstellingStatus = activeConfig?.let { cfg ->
            buildFijnafstellingBlock(cfg)
        } ?: ""

        return """
════════════════════════
 🧠 FCL meal V4 v7.0.9
 
════════════════════════
• Height (sterkte)     : ${profileLabel(prefs.get(StringKey.fcl_vnext_profile))}
• Timing (reactietijd) : ${mealDetectLabel(prefs.get(StringKey.fcl_vnext_meal_detect_speed))}
• Maaltijd behandeling : ${mealLabel(prefs.get(StringKey.fcl_vnext_meal_handling_style))}
• Persistentie         : ${correctionStyleLabel(prefs.get(StringKey.fcl_vnext_correction_style))}
• Hypoprotectie        : ${HypoProtectionLabel(prefs.get(StringKey.fcl_vnext_hypo_protection_style))}
• Nacht respons        : ${NightResponsLabel(prefs.get(StringKey.fcl_vnext_night_response_style))}
• Insulineverdeling    : ${doseDistributionLabel(prefs.get(StringKey.fcl_vnext_dose_distribution_style))}


$coreStatus

${mealIntentBlock ?: ""}

$fclCore

$activityStatus

$resistanceStatus

$metricsStatus

$fijnafstellingStatus
""".trimIndent()
    }
}
