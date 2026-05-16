package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.IntKey

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

    // ── Label-helpers ─────────────────────────────────────────────────────────

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

    // ── Sectie 1: Situatie ───────────────────────────────────────────────────

    private fun buildSituatieSectie(
        isNight: Boolean,
        ui: FclUiSnapshot,
        advice: FCLvNextAdvice?
    ): String = buildString {
        val dagNacht = if (isNight) "'S NACHTS" else "OVERDAG"
        val tijd = DateTime.now().toString("HH:mm")
        appendLine("🔄 SITUATIE — $dagNacht  $tijd")
        appendLine("─────────────────────")
        val deltaStr = ui.delta5m?.let { " (%+.2f/5m)".format(it) } ?: ""
        appendLine("• Glucose:  ${"%.1f".format(ui.bgNow)} mmol/L$deltaStr")
        appendLine("• IOB:      ${"%.2f".format(ui.iob)} U")

        advice?.peakState?.let { state ->
            val uitleg = when (state) {
                "IDLE"      -> "Geen actieve stijging"
                "WATCHING"  -> "Sterke stijging actief"
                "CONFIRMED" -> "Piek bevestigd – afremming verwacht"
                else        -> state
            }
            val peakStr = advice.predictedPeak
                ?.let { "  →  verwacht ${"%.1f".format(it)} mmol/L" } ?: ""
            append("• FCL piek: $uitleg$peakStr")
        }
    }

    // ── Sectie 2: Beslissing ─────────────────────────────────────────────────

    private fun buildBeslissingSectie(
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean
    ): String = buildString {
        appendLine("💉 BESLISSING")
        appendLine("─────────────────────")
        if (shouldDeliver) {
            when {
                bolusAmount > 0.0 && basalRate > 0.0 -> {
                    appendLine("• SMB:    ${"%.2f".format(bolusAmount)} U")
                    append(    "• Basaal: ${"%.2f".format(basalRate)} U/h")
                }
                bolusAmount > 0.0 -> append("• SMB: ${"%.2f".format(bolusAmount)} U")
                basalRate > 0.0   -> append("• Basaal: ${"%.2f".format(basalRate)} U/h")
                else              -> append("• Geen insuline deze cyclus")
            }
        } else {
            append("• Geen ingreep")
        }
    }

    // ── Sectie 3: Laatste doses ───────────────────────────────────────────────

    private fun buildDosesSectie(
        history: ArrayDeque<Triple<DateTime, Double, Boolean>>
    ): String = buildString {
        appendLine("📋 LAATSTE DOSES")
        appendLine("─────────────────────")
        if (history.isEmpty()) {
            append("Geen recente afleveringen")
        } else {
            history.forEachIndexed { i, (ts, dose, isFcl) ->
                val bron = if (isFcl) "🧠 FCLvNext" else "⚙️ AAPS"
                val line = "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U  $bron"
                if (i < history.lastIndex) appendLine(line) else append(line)
            }
        }
    }

    // ── Sectie 4: Algoritmeparameters ────────────────────────────────────────

    private fun buildAlgoritmeSectie(activeConfig: FCLvNextConfig? = null): String {
        val s = prefs.get(IntKey.fcl_vnext_sterkte)
        val t = prefs.get(IntKey.fcl_vnext_timing)
        val v = prefs.get(IntKey.fcl_vnext_volhoudendheid)
        val n = prefs.get(IntKey.fcl_vnext_nacht_factor)

        fun indicator(waarde: Int, neutraal: Int = 100) = when {
            waarde > neutraal + 4 -> "↑"
            waarde < neutraal - 4 -> "↓"
            else                  -> "="
        }

        fun frontloadLabel(t: Int) = when {
            t >= 115 -> "Sterk vroeg"
            t >= 107 -> "Vroeg"
            t >= 93  -> "Neutraal"
            t >= 85  -> "Laat"
            else     -> "Sterk laat"
        }

        fun sterkteLabel(s: Int) = when {
            s >= 110 -> "Hoog"
            s >= 102 -> "Iets boven standaard"
            s >= 98  -> "Standaard"
            s >= 90  -> "Iets onder standaard"
            else     -> "Laag"
        }

        fun volhLabel(v: Int) = when {
            v >= 110 -> "Vasthoudend"
            v >= 102 -> "Iets vasthoudender"
            v >= 98  -> "Standaard"
            v >= 90  -> "Iets voorzichtiger"
            else     -> "Voorzichtig"
        }

        val nachtTov = "${100 - n}% rustiger dan dag"

        return buildString {
            appendLine("📊 ALGORITMEPARAMETERS")
            appendLine("─────────────────────")
            appendLine("• Sterkte  (S): ${s}%  ${indicator(s)}  ${sterkteLabel(s)}")
            appendLine("• Timing   (T): ${t}%  ${indicator(t)}  ${frontloadLabel(t)}")
            appendLine("• Volhoud. (V): ${v}%  ${indicator(v)}  ${volhLabel(v)}")
            appendLine("• Nacht-N    : ${n}%  ($nachtTov)")
            appendLine("• Nacht-respons:     ${nightResponsLabel(prefs.get(StringKey.fcl_vnext_night_response_style))}")
            append(    "• Insulineverdeling: ${doseDistributionLabel(prefs.get(StringKey.fcl_vnext_dose_distribution_style))}")
        }
    }

    // ── Sectie 5: Analyzer-gestuurde waarden ─────────────────────────────────

    private fun buildAnalyzerConfigSectie(activeConfig: FCLvNextConfig?): String {
        if (activeConfig == null) return "🔧 ANALYZER-WAARDEN\n─────────────────────\nConfig nog niet beschikbaar (wacht op eerste FCLvNext cyclus)"

        val snapManual = FclActiveConfigBridge.get()?.manualMaxSmbDay ?: 0.0

        return buildString {
            appendLine("🔧 ANALYZER-GESTUURDE WAARDEN (config)")
            appendLine("─────────────────────")
            appendLine("• MaxSMB dag        : ${"%.2f".format(activeConfig.maxSMB)} U")
            if (snapManual > 0.001 && kotlin.math.abs(activeConfig.maxSMB - snapManual) > 0.01) {
                val richting = if (activeConfig.maxSMB < snapManual) "verlaagd" else "verhoogd"
                appendLine("  ↳ handmatig: ${"%.2f".format(snapManual)} U ($richting door analyzer)")
            }
            appendLine("• IOB-remdrempel    : ${"%.3f".format(activeConfig.peakIobBrakeSuppressThreshold)}")
            appendLine()
            appendLine("⚙️  FIJNAFSTEMMING")
            appendLine("─────────────────────")
            appendLine("• iobStart          : ${"%.2f".format(activeConfig.iobStart)}")
            appendLine("• commitCooldown    : ${activeConfig.commitCooldownMinutes} min")
            appendLine("• peakPredThreshold : ${"%.1f".format(activeConfig.peakPredictionThreshold)} mmol")
            appendLine("• peakHorizon       : ${"%.1f".format(activeConfig.peakPredictionHorizonH)} h")
            appendLine("• watchingFrac      : ${"%.2f".format(activeConfig.watchingFrontloadFrac)}")
            appendLine("• watchingDeltaMin  : ${"%.2f".format(activeConfig.watchingMinDeltaToTarget)}")
            appendLine("• earlyBoostFactor  : ${"%.2f".format(activeConfig.earlyBoostFactor)}")
            appendLine("• earlyBoostMinConf : ${"%.2f".format(activeConfig.earlyBoostMinConfidence)}")
            appendLine("• earlyBoostMaxCommits: ${activeConfig.earlyBoostMaxCommits}")
            appendLine("• earlyRiseFracMin  : ${"%.2f".format(activeConfig.earlyRiseFracMin)}")
            appendLine("• peakMaxSlopeWeight: ${"%.2f".format(activeConfig.peakMaxSlopeWeight)}")
            appendLine("• lateDecayFactor   : ${"%.2f".format(activeConfig.lateCommitDecayFactor)}")
            appendLine("• lateDecayThreshold: ${"%.2f".format(activeConfig.lateCommitDecayThreshold)}")
            appendLine("• sustainedSlopeMin : ${"%.2f".format(activeConfig.sustainedRiseSlopeMin)}")
            append(    "• sustainedMinTarget: ${activeConfig.sustainedRiseMinTarget} mmol")
        }
    }

    // ── Hoofd buildStatus ─────────────────────────────────────────────────────

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
        activeConfig: FCLvNextConfig? = null,
        history: ArrayDeque<Triple<DateTime, Double, Boolean>>
    ): String = buildString {
        appendLine("════════════════════════")
        appendLine(" 🧠 FCL V6 v1.5.7")
        appendLine("════════════════════════")
        appendLine()

        appendLine(buildSituatieSectie(isNight, ui, advice))
        appendLine()

        appendLine(buildBeslissingSectie(bolusAmount, basalRate, shouldDeliver))
        appendLine()

        appendLine(buildDosesSectie(history))
        appendLine()

        appendLine(buildAlgoritmeSectie(activeConfig))
        appendLine()

        appendLine("🏃 ACTIVITEIT")
        appendLine("─────────────────────")
        appendLine(activityLog ?: "Geen activiteitdata")
        appendLine()

        appendLine("🧬 AUTO-SENS")
        appendLine("─────────────────────")
        appendLine(resistanceLog ?: "Geen resistentie-log")
        appendLine()

        appendLine("📈 GLUCOSE STATISTIEKEN")
        appendLine("─────────────────────")
        appendLine(metricsText ?: "Nog geen data")
        appendLine()

        append(buildAnalyzerConfigSectie(activeConfig))
    }
}