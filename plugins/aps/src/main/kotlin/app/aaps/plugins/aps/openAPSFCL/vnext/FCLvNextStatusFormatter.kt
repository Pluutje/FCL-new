package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.IntKey

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
) {

    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"
        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }

    // ── Label-helpers (ongewijzigd — worden nog gebruikt voor nightResponse en doseDistribution) ──

    private fun nightResponsLabel(value: String): String =
        when (value) {
            "VERY_GUARDED" -> "🛑 Zeer terughoudend"
            "GUARDED"      -> "🧤 Terughoudend"
            "BALANCED"     -> "⚖️ Gebalanceerd"
            "RESPONSIVE"   -> "🌙 Reageert eerder"
            "PROACTIVE"    -> "🚀 Proactief"
            else           -> value
        }

    private fun doseDistributionLabel(value: String): String =
        when (value) {
            "VERY_SMOOTH" -> "🌊 Ultra smooth"
            "SMOOTH"      -> "🫧 Smooth"
            "BALANCED"    -> "⚖️ Balanced"
            "PULSED"      -> "🔨 Pulsed"
            "VERY_PULSED" -> "⚡ Ultra pulsed"
            else          -> value
        }

    // ── Algoritmestatus — leesbare samenvatting van actieve config ────────
    //
    // Toont de vier ingestelde waarden (S/T/V/N) plus twee vaste gebruikers-
    // keuzes (nacht-respons, insulineverdeling). Geen losse Groep-3 params —
    // die worden uitsluitend bijgehouden in FCLvNext_active_params.json.
    //
    // Interpretatiehulp per waarde:
    //   S <  95: minder insuline dan standaard
    //   S = 100: standaard basislijn
    //   S > 105: meer insuline dan standaard
    //   T <  95: later doseren (meer gespreid)
    //   T = 100: neutraal timing
    //   T > 105: eerder en geconcentreerder doseren
    //   V <  95: minder vasthoudend, hypo-bescherming hoger
    //   V = 100: standaard
    //   V > 105: meer vasthoudend, agressiever bijsturen
    //   N <  85: 's nachts beduidend rustiger dan overdag
    //   N =  85: standaard nacht-instelling
    //   N = 100: nacht identiek aan dag
    //
    private fun buildAlgoritmeSectie(): String {
        val s = prefs.get(IntKey.fcl_vnext_sterkte)
        val t = prefs.get(IntKey.fcl_vnext_timing)
        val v = prefs.get(IntKey.fcl_vnext_volhoudendheid)
        val n = prefs.get(IntKey.fcl_vnext_nacht_factor)

        fun indicator(waarde: Int, neutraal: Int = 100) = when {
            waarde > neutraal + 4 -> "↑"
            waarde < neutraal - 4 -> "↓"
            else                  -> "="
        }

        fun frontloadLabel(t: Int): String = when {
            t >= 115 -> "Sterk vroeg"
            t >= 107 -> "Vroeg"
            t >= 93  -> "Neutraal"
            t >= 85  -> "Laat"
            else     -> "Sterk laat"
        }

        fun sterkteLabel(s: Int): String = when {
            s >= 110 -> "Hoog"
            s >= 102 -> "Iets boven standaard"
            s >= 98  -> "Standaard"
            s >= 90  -> "Iets onder standaard"
            else     -> "Laag"
        }

        fun volhLabel(v: Int): String = when {
            v >= 110 -> "Vasthoudend"
            v >= 102 -> "Iets vasthoudender"
            v >= 98  -> "Standaard"
            v >= 90  -> "Iets voorzichtiger"
            else     -> "Voorzichtig"
        }

        val nachtTov = when {
            n >= 98  -> "Gelijk aan dag"
            n >= 90  -> "${100 - n}% rustiger dan dag"
            n >= 78  -> "${100 - n}% rustiger dan dag"
            else     -> "${100 - n}% rustiger dan dag"
        }

        return buildString {
            appendLine("════════════════════════")
            appendLine(" 🧠 FCL V5 v2.0.2")
            appendLine("════════════════════════")
            appendLine()
            appendLine("📊 ALGORITMEPARAMETERS")
            appendLine("─────────────────────")
            appendLine("• Sterkte   (S): ${s}%  ${indicator(s)}  ${sterkteLabel(s)}")
            appendLine("• Timing    (T): ${t}%  ${indicator(t)}  ${frontloadLabel(t)}")
            appendLine("• Volhoud.  (V): ${v}%  ${indicator(v)}  ${volhLabel(v)}")
            appendLine("• Nacht-N      : ${n}%  ($nachtTov)")
            appendLine()
            appendLine("⚙️  LEVERINGSINSTELLINGEN")
            appendLine("─────────────────────")
            appendLine("• Nacht respons     : ${nightResponsLabel(prefs.get(StringKey.fcl_vnext_night_response_style))}")
            append(    "• Insulineverdeling : ${doseDistributionLabel(prefs.get(StringKey.fcl_vnext_dose_distribution_style))}")
        }
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
        activeConfig: FCLvNextConfig? = null
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

        return """
${buildAlgoritmeSectie()}


$coreStatus


$fclCore

$activityStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}
