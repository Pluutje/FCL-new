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
    val predictedPeak: Double?,
    // 06/07/2026 (Ecko) — verificatieblok: laat de laatste 3 BG-waarden zien
    // exact zoals FCLvNextBgHistoryProvider ze aanlevert (dus .recalculated —
    // gecalibreerd + gesmooth, na LinearCalibration + UKF), zodat in één
    // oogopslag te zien is of FCLvNext met dezelfde waarden rekent als het
    // hoofdscherm. Zie de kdoc bovenaan FCLvNextBgHistoryProvider.kt voor
    // de achtergrond (calibratie-discussie met de hoofdontwikkelaar).
    val last3DbPoints: List<FCLvNextBgHistoryProvider.BgPoint> = emptyList(),
    // 22/07/2026 (Ecko) — geselecteerde pomp + werkelijke max-basaal, voor
    // de nieuwe Pomp-sectie onderaan (zie buildPompSectie). pumpMaxBasalUh
    // is dezelfde, pomptype-bewuste waarde (absoluut of %-van-profiel-basaal)
    // als OapsProfileFCL.max_basal — zie computeRealMaxBasalUh in
    // OpenAPSFCLPlugin.kt voor de berekening zelf.
    val pumpNaam: String = "",
    val pumpMaxBasalUh: Double = 0.0,
    // 22/07/2026 (Ecko) — huidige basaalstand, naast de max; nuttig genoeg om
    // structureel te tonen (i.t.t. het tijdelijke ruwe-waarden-debugblok dat
    // hier eerst stond, nu weer verwijderd).
    val pumpCurrentBasalUh: Double = 0.0,
    // 06/07/2026 (Ecko) — korte activiteitsindicatie ("laatste uur") voor de
    // Activiteit-sectie. -1.0/-1 als er geen data beschikbaar is (zelfde
    // conventie als de kolommen in FCLvNext_ActivityLog_v2.csv). Bewust een
    // apart, licht opvraagje per cyclus — dit toont alleen het laatste uur,
    // niet de volle 8-uurs-geschiedenis die de CSV-logger bijhoudt.
    val recentSteps1h: Int = -1,
    val recentCalories1h: Double = -1.0,
    val recentHr1h: Double = -1.0,
    // 06/07/2026 (Ecko) — gedetecteerd activiteitstype (ON_BICYCLE/WALKING/
    // RUNNING/STILL/IN_VEHICLE/TILTING/ON_FOOT), null als er niets recents is.
    val recentActivityType: String? = null,
    val recentActivityConfidencePct: Int = 0,
    // 14/07/2026 (Ecko) — Activiteits Insuline Gevoeligheids Factor (AIGF).
    // null = AIGF staat uit in Settings, of nog geen geldige berekening
    // beschikbaar (te weinig historie) — dan wordt de regel niet getoond.
    // 100 = neutraal, 125 = 25% gevoeliger (minder insuline), 75 = 25%
    // minder gevoelig (meer insuline). Zie FclActivitySensitivity.kt.
    val aigfPct: Double? = null,
    // 14/07/2026 (Ecko) — staat AIGF AAN in Settings? Los van of er deze
    // cyclus ook een verse berekening was (zie aigfReasonNl). Bepaalt of de
    // AIGF-regel in de Activiteit-sectie überhaupt getoond wordt.
    val aigfEnabled: Boolean = false,
    // Leesbare reden waarom er geen verse berekening was (leeg = wel een
    // verse berekening, of AIGF staat uit).
    val aigfReasonNl: String = "",
    // 16/07/2026 (Ecko) — voor de uitgebreide AIGF-statusregel: geschatte
    // kcal afgelopen 8 uur (huidig) en de 7-daagse mediaan-baseline waar dat
    // tegen afgezet wordt, plus hoeveel dagen die baseline al beslaat.
    val aigfCurrentCal8h: Double = 0.0,
    val aigfBaselineCal8h: Double = 0.0,
    val aigfDaysOfHistory: Double = 0.0
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
        advice: app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextAdvice?,
        // Geleidelijke nacht-overgang (17/07/2026, Ecko): 0.0..1.0. Default
        // gekoppeld aan isNight zodat oudere aanroepen ongewijzigd blijven.
        nightTransitionFraction: Double = if (isNight) 1.0 else 0.0
    ): String {
        val str = FclStrings.get(context)
        val mgdl = BgUnits.isMgdl(context)
        val timeStr = org.joda.time.DateTime.now()
            .toString("HH:mm")
        val header = if (isNight) str.situatieNacht else str.situatieOverdag
        // Alleen tonen tijdens de overgang zelf (niet bij 0% of 100%, dat is
        // gewoon "dag" resp. "volledig nacht" en spreekt al uit de header).
        val overgangLine =
            if (isNight && nightTransitionFraction in 0.001..0.999)
                "  (nacht-overgang: ${(nightTransitionFraction * 100).toInt()}%)"
            else ""
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
            appendLine("🏃 $header  $timeStr$overgangLine")
            appendLine("─────────────────────")
            appendLine("• ${str.glucose}:  ${BgUnits.formatBg(ui.bgNow, mgdl)}$deltaStr")
            appendLine("• ${str.iob}:     ${"%.2f".format(ui.iob)} U")
            // 06/07/2026 (Ecko) — verificatieblok calibratie: liet de laatste 3
            // DB-waarden zien om de calibratiepijplijn te checken. Uitgezet
            // (22/07/2026, Ecko) — check is voorlopig klaar; het onderliggende
            // ui.last3DbPoints-veld en de aanlevering ervan blijven intact,
            // dus dit is met één regel weer aan te zetten als dat nog eens nodig is.
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

    // ── Sectie 4: Pomp ────────────────────────────────────────────────────
    // 22/07/2026 (Ecko) — geselecteerde pomp + werkelijke max-basaal. Bewust
    // als eigen, statische sectie (net als Analyzer-waarden hieronder) i.p.v.
    // in Situatie: dit verandert alleen bij een profiel-/pompwissel, niet
    // elke cyclus, dus het hoort niet tussen de live per-cyclus-waarden.
    private fun buildPompSectie(ui: FclUiSnapshot): String = buildString {
        appendLine("🔌 Pomp")
        appendLine("─────────────────────")
        val naamTxt = ui.pumpNaam.ifEmpty { "onbekend" }
        appendLine("• Pomp        : $naamTxt")
        appendLine("• Max basaal  : ${"%.2f".format(ui.pumpMaxBasalUh)} U/h")
        append(    "• Basaal nu   : ${"%.2f".format(ui.pumpCurrentBasalUh)} U/h")
    }

    // ── Sectie 5: Analyzer-gestuurde waarden ─────────────────────────────────

    private fun buildAnalyzerConfigSectie(activeConfig: FCLvNextConfig?): String {
        val str = FclStrings.get(context)
        if (activeConfig == null) return "🔧 ANALYZER-WAARDEN\n─────────────────────\nConfig nog niet beschikbaar (wacht op eerste FCLvNext cyclus)"

        // maxSMB volgt direct S% — geen aparte liveMaxSmb berekening nodig
        val liveMaxSmb = activeConfig.maxSMB

        val mgdl = BgUnits.isMgdl(context)
        return buildString {
            appendLine("🔧 ${str.analyzerConfigHeader}")
            appendLine("─────────────────────")
            appendLine("• ${str.maxSmbDagLabel}        : ${"%.2f".format(liveMaxSmb)} U  (S% × handmatig)")
            append(    "• ${str.iobRemdrempel}    : ${"%.3f".format(activeConfig.peakIobBrakeSuppressThreshold)}")
            // FIJNAFSTEMMING-sectie verwijderd (01/07/2026, Ecko): Fijnafstelling-tab
            // in de Analyzer leest dezelfde waarden van FclActiveConfigBridge en toont
            // ze overzichtelijker. Dubbele weergave hier achter expert-mode heeft geen
            // toegevoegde waarde meer.
        }
    }

    // ── Hoofd buildStatus ─────────────────────────────────────────────────────

    fun buildStatus(
        isNight: Boolean,
        // Geleidelijke nacht-overgang (17/07/2026, Ecko) — zie kdoc bij
        // buildSituatieSectie()/FCLvNextInput.nightTransitionFraction.
        nightTransitionFraction: Double = if (isNight) 1.0 else 0.0,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        ui: FclUiSnapshot,
        activityLog: String?,
        resistanceLog: String?,
        activeConfig: FCLvNextConfig? = null,
        history: ArrayDeque<Triple<DateTime, Double, Boolean>>
    ): String = buildString {
        val str = FclStrings.get(context)
        appendLine("════════════════════════")
        appendLine(" 🧠 FCL V7 v3.2.2")
        appendLine("════════════════════════")
        appendLine()

        appendLine(buildSituatieSectie(isNight, ui, advice, nightTransitionFraction))

        appendLine(buildBeslissingSectie(bolusAmount, basalRate, shouldDeliver))
        appendLine()

        appendLine(buildDosesSectie(history))
        appendLine()

        appendLine("🏃 ${str.activiteit}")
        appendLine("─────────────────────")
        // 06/07/2026 (Ecko) — korte indicatie laatste uur, los van de uitgebreide
        // 8-uurs-geschiedenis die alleen in FCLvNext_ActivityLog_v2.csv staat.
        val stepsTxt = if (ui.recentSteps1h >= 0) "${ui.recentSteps1h}" else "–"
        val calTxt = if (ui.recentCalories1h >= 0.0) "${"%.0f".format(ui.recentCalories1h)} kcal" else "–"
        val hrTxt = if (ui.recentHr1h >= 0.0) "${"%.0f".format(ui.recentHr1h)} bpm" else "–"
        appendLine("• Laatste uur: 👣 $stepsTxt  🔥 $calTxt  ❤ $hrTxt")
        // 06/07/2026 (Ecko) — gedetecteerd activiteitstype, apart van de meetwaarden
        // hierboven, zodat direct zichtbaar is WELK type de kcal-schatting beïnvloedt.
        val actIcon = when (ui.recentActivityType) {
            "ON_BICYCLE" -> "🚴"
            "RUNNING"    -> "🏃"
            "WALKING", "ON_FOOT" -> "🚶"
            "IN_VEHICLE" -> "🚗"
            "STILL"      -> "🧍"
            else         -> "❔"
        }
        if (ui.recentActivityType != null) {
            appendLine("• Activiteitstype: $actIcon ${ui.recentActivityType} (${ui.recentActivityConfidencePct}%)")
        }
        // 14/07/2026 (Ecko) — AIGF-regel: altijd tonen zodra de functie AAN
        // staat in Settings, ook als er (nog) geen verse berekening is — dan
        // toont de regel de reden i.p.v. stilzwijgend niets te laten zien.
        // 16/07/2026 (Ecko) — bijgewerkt: lege regel voor/na in BEIDE paden
        // (stond eerder alleen bij de reden-tak), leesbare titel i.p.v. de
        // kale afkorting, en bij een verse berekening ook de 8u-nu/7d-basis
        // kcal en (zolang de historie nog opbouwt) de opbouw-voortgang.
        if (ui.aigfEnabled) {
            appendLine()
            if (ui.aigfReasonNl.isNotEmpty()) {
                appendLine("• AIGF (Activiteits Insuline Gevoeligheidsfactor): AAN — ${ui.aigfReasonNl}")
            } else {
                val pct = ui.aigfPct ?: 100.0
                val aigfIcon = when {
                    pct > 100.5 -> "⬆️"
                    pct < 99.5  -> "⬇️"
                    else        -> "➡️"
                }
                appendLine("• AIGF (Activiteits Insuline Gevoeligheidsfactor): $aigfIcon ${"%.1f".format(pct)}%")
                val curTxt = "${"%.0f".format(ui.aigfCurrentCal8h)} kcal"
                val baseTxt = "${"%.0f".format(ui.aigfBaselineCal8h)} kcal"
                val opbouwTxt = if (ui.aigfDaysOfHistory < 5.0)
                    " · opbouw: ${"%.1f".format(ui.aigfDaysOfHistory)}/5,0d" else ""
                appendLine("   8u nu: $curTxt · 7d-basis: $baseTxt$opbouwTxt")
            }
            appendLine()
        }
        appendLine(activityLog ?: str.geenActiviteitdata)

        // AutoSens-sectie verwijderd (18/06/2026)     POST_NOTIFICATIONS
        // Glucose statistieken verwijderd (29/06/2026): zie Statistics-tabblad    onNotificationActionClick

        appendLine(buildPompSectie(ui))
        appendLine()

        append(buildAnalyzerConfigSectie(activeConfig))
    }
}