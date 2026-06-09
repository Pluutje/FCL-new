package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.plugins.aps.openAPSFCL.vnext.lang.FclStrings

import android.content.Context

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey

data class FclUiSnapshot(
    val bgNow: Double,
    val iob: Double,
    val delta5m: Double?,
    val slopeHr: Double?,
    val predictedPeak: Double?
)

class FCLvNextStatusFormatter(
    private val prefs: Preferences,
    private val context: android.content.Context,
) {

    // ── Label-helpers ─────────────────────────────────────────────────────────

    private fun nightResponsLabel(key: String): String = FclStrings.get(context).nightStyleLabel(key)


    // ── Sectie 1: Situatie ─────────────────────

    private fun buildSituatieSectie(
        isNight: Boolean,
        ui: FclUiSnapshot,
        advice: app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextAdvice?
    ): String {
        val str = FclStrings.get(context)
        val mgdl = BgUnits.isMgdl(context)
        val timeStr = org.joda.time.DateTime.now()
            .toString("HH:mm")
        val header = if (isNight) str.situatieNacht else str.situatieOverdag
        val deltaStr = ui.delta5m?.let { d ->
            val sign = if (d >= 0) "+" else ""
            " ($sign${BgUnits.formatBgValue(d, mgdl)}/5m)"
        } ?: ""
        // Piek-lijn: alleen tonen als er een actieve stijgingsfase is.
        // peakState "IDLE" = geen actieve episode → lijn weglaten.
        // Hiermee wordt voorkomen dat "Sterke stijging actief" staat
        // terwijl de BG aan het dalen is na een maaltijdepisode.
        val peakStateStr = advice?.peakState ?: "IDLE"
        val peakIsActive = peakStateStr == "WATCHING" || peakStateStr == "CONFIRMED"
        val peakLine = when {
            advice == null -> ""
            peakIsActive && advice.predictedPeak != null && advice.predictedPeak > 0 ->
                "${str.fclPiekSterkeStijging}  →  ${str.verwacht} ${BgUnits.formatBg(advice.predictedPeak, mgdl)}"
            peakIsActive -> str.fclPiekWatching
            else -> ""  // IDLE: geen piek-lijn tonen
        }
        return buildString {
            appendLine("🏃 $header  $timeStr")
            appendLine("─────────────────────")
            appendLine("• ${str.glucose}:  ${BgUnits.formatBg(ui.bgNow, mgdl)}$deltaStr")
            appendLine("• ${str.iob}:     ${"%.2f".format(ui.iob)} U")
            if (peakLine.isNotEmpty()) appendLine("• ${str.fclPiek}: $peakLine")
        }
    }

    // ── Sectie 2: Beslissing ─────────────────────────────────────────────────

    private fun buildBeslissingSectie(
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean
    ): String = buildString {
        val str = FclStrings.get(context)
        appendLine("💉 ${str.beslissing}")
        appendLine("─────────────────────")
        if (shouldDeliver) {
            when {
                bolusAmount > 0.0 && basalRate > 0.0 -> {
                    appendLine("• SMB:    ${"%.2f".format(bolusAmount)} U")
                    append(    "• Basal: ${"%.2f".format(basalRate)} U/h")
                }
                bolusAmount > 0.0 -> append("• SMB: ${"%.2f".format(bolusAmount)} U")
                basalRate > 0.0   -> append("• Basaal: ${"%.2f".format(basalRate)} U/h")
                else              -> append("• No insulin this cycle")
            }
        } else {
            append("• ${str.geenIngreep}")
        }
    }

    // ── Sectie 3: Laatste doses ───────────────────────────────────────────────

    private fun buildDosesSectie(
        history: ArrayDeque<Triple<DateTime, Double, Boolean>>
    ): String = buildString {
        val str = FclStrings.get(context)
        appendLine("📋 ${str.latsteDosisFcl}")
        appendLine("─────────────────────")
        if (history.isEmpty()) {
            append(str.latsteDosisFclEmpty)
        } else {
            history.forEachIndexed { i, (ts, dose, isFcl) ->
                val bron = if (isFcl) "🧠 FCLvNext" else "⚙️ AAPS"
                val line = "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U  $bron"
                if (i < history.lastIndex) appendLine(line) else append(line)
            }
        }
    }

    // ── Sectie 5: Analyzer-gestuurde waarden ─────────────────────────────────

    private fun buildAnalyzerConfigSectie(activeConfig: FCLvNextConfig?): String {
        val str = FclStrings.get(context)
        val expertMode = context.getSharedPreferences("fcl_expert_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("expert_mode_active", false)
        if (activeConfig == null) return "🔧 ANALYZER-WAARDEN\n─────────────────────\nConfig nog niet beschikbaar (wacht op eerste FCLvNext cyclus)"

        // maxSMB volgt direct S% — geen aparte liveMaxSmb berekening nodig
        val liveMaxSmb = activeConfig.maxSMB

        val mgdl = BgUnits.isMgdl(context)
        return buildString {
            appendLine("🔧 ${str.analyzerConfigHeader}")
            appendLine("─────────────────────")
            appendLine("• ${str.maxSmbDagLabel}        : ${"%.2f".format(liveMaxSmb)} U  (S% × handmatig)")
            appendLine("• ${str.iobRemdrempel}    : ${"%.3f".format(activeConfig.peakIobBrakeSuppressThreshold)}")
            if (expertMode) {
                appendLine()
                appendLine("⚙️  ${str.analyzerFijnafstemming}")
                appendLine("─────────────────────")
                appendLine("• iobStart          : ${"%.2f".format(activeConfig.iobStart)}")
                appendLine("• commitCooldown    : ${activeConfig.commitCooldownMinutes} min")
                appendLine("• peakPredThreshold : ${BgUnits.formatBg(activeConfig.peakPredictionThreshold, mgdl)}")
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
                append(    "• sustainedMinTarget: ${BgUnits.formatBgValue(activeConfig.sustainedRiseMinTarget.toDouble(), mgdl, 0)} ${BgUnits.unitShort(mgdl)}")
            }
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
        val str = FclStrings.get(context)
        appendLine("════════════════════════")
        appendLine(" 🧠 FCL V6 v2.3.3c")
        appendLine("════════════════════════")
        appendLine()

        appendLine(buildSituatieSectie(isNight, ui, advice))
    //    appendLine()

        appendLine(buildBeslissingSectie(bolusAmount, basalRate, shouldDeliver))
        appendLine()

        appendLine(buildDosesSectie(history))
        appendLine()

        appendLine("🏃 ${str.activiteit}")
        appendLine("─────────────────────")
        appendLine(activityLog ?: str.geenActiviteitdata)
      //  appendLine()

        appendLine("🧬 ${str.autoSensHeader}")
        appendLine("─────────────────────")
        appendLine(resistanceLog ?: str.geenResistentieLog)
    //    appendLine()

        appendLine("📈 ${str.glucoseStatHeader}")
        appendLine("─────────────────────")
        appendLine(metricsText ?: str.nogGeenData)
        appendLine()

        append(buildAnalyzerConfigSectie(activeConfig))
    }
}