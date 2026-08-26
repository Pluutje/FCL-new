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
    // 06/07/2026 — verificatieblok: laat de laatste 3 BG-waarden zien
    // exact zoals FCLvNextBgHistoryProvider ze aanlevert (dus .recalculated —
    // gecalibreerd + gesmooth, na LinearCalibration + UKF), zodat in één
    // oogopslag te zien is of FCLvNext met dezelfde waarden rekent als het
    // hoofdscherm. Zie de kdoc bovenaan FCLvNextBgHistoryProvider.kt voor
    // de achtergrond (calibratie-discussie met de hoofdontwikkelaar).
    val last3DbPoints: List<FCLvNextBgHistoryProvider.BgPoint> = emptyList(),
    // 22/07/2026 — geselecteerde pomp + werkelijke max-basaal, voor
    // de nieuwe Pomp-sectie onderaan (zie buildPompSectie). pumpMaxBasalUh
    // is dezelfde, pomptype-bewuste waarde (absoluut of %-van-profiel-basaal)
    // als OapsProfileFCL.max_basal — zie computeRealMaxBasalUh in
    // OpenAPSFCLPlugin.kt voor de berekening zelf.
    val pumpNaam: String = "",
    val pumpMaxBasalUh: Double = 0.0,
    // 22/07/2026 — huidige basaalstand, naast de max; nuttig genoeg om
    // structureel te tonen (i.t.t. het tijdelijke ruwe-waarden-debugblok dat
    // hier eerst stond, nu weer verwijderd).
    val pumpCurrentBasalUh: Double = 0.0,
    // 06/07/2026 — korte activiteitsindicatie ("laatste uur") voor de
    // Activiteit-sectie. -1.0/-1 als er geen data beschikbaar is (zelfde
    // conventie als de kolommen in FCLvNext_ActivityLog_v2.csv). Bewust een
    // apart, licht opvraagje per cyclus — dit toont alleen het laatste uur,
    // niet de volle 8-uurs-geschiedenis die de CSV-logger bijhoudt.
    val recentSteps1h: Int = -1,
    val recentCalories1h: Double = -1.0,
    val recentHr1h: Double = -1.0,
    // 06/07/2026 — gedetecteerd activiteitstype (ON_BICYCLE/WALKING/
    // RUNNING/STILL/IN_VEHICLE/TILTING/ON_FOOT), null als er niets recents is.
    val recentActivityType: String? = null,
    val recentActivityConfidencePct: Int = 0,
    // 14/07/2026 — Activiteits Insuline Gevoeligheids Factor (AIGF),
    // HERONTWORPEN 28/07/2026 in twee losse componenten (zie uitgebreide
    // kdoc bij FclActivitySensitivity.kt): A = vorige dag/naijling (continu),
    // B = recente uren vóór dit moment (sinds 16/08/2026 ook continu/live,
    // elke cyclus opnieuw berekend — niet meer bevroren per maaltijd-episode,
    // zie kdoc bij lastSmoothedAigfBPct in FCLvNext.kt). 100 = neutraal,
    // 125 = 25% gevoeliger (minder insuline), 75 = 25% minder gevoelig (meer
    // insuline).
    val aigfEnabled: Boolean = false,
    // Component A — continu, stuurt de afterload-reductie aan.
    val aigfAPct: Double? = null,
    val aigfAReasonNl: String = "",
    val aigfACurrentCal24h: Double = 0.0,
    val aigfABaselineCal24h: Double = 0.0,
    val aigfADaysOfHistory: Double = 0.0,
    // Component B — sinds 16/08/2026 ook continu/live (elke cyclus), stuurt
    // de commit-verhoging aan.
    val aigfBPct: Double? = null,
    val aigfBReasonNl: String = "",
    val aigfBCurrentCal4h: Double = 0.0,
    val aigfBBaselineCal4h: Double = 0.0,
    val aigfBDaysOfHistory: Double = 0.0,
    // Hoeveel van het laatst-beoordeelde 4-uursvenster binnen wakkere uren
    // viel (0..1) — legt uit waarom aigfBPct dicht bij 100 kan blijven.
    val aigfBWakeOverlapFrac: Double = 0.0,
    // Vandaag gedetecteerde "dag begon om"-tijdstip (epoch ms), null als nog
    // niet vastgesteld — zie FclWakeDetector.kt.
    val aigfDaystartTodayMs: Long? = null
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
        // Geleidelijke nacht-overgang (17/07/2026): 0.0..1.0. Default
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
            // 06/07/2026 — verificatieblok calibratie: liet de laatste 3
            // DB-waarden zien om de calibratiepijplijn te checken. Uitgezet
            // (22/07/2026) — check is voorlopig klaar; het onderliggende
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
    // 22/07/2026 — geselecteerde pomp + werkelijke max-basaal. Bewust
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
            // FIJNAFSTEMMING-sectie verwijderd (01/07/2026): Fijnafstelling-tab
            // in de Analyzer leest dezelfde waarden van FclActiveConfigBridge en toont
            // ze overzichtelijker. Dubbele weergave hier achter expert-mode heeft geen
            // toegevoegde waarde meer.
        }
    }

    // ── Hoofd buildStatus ─────────────────────────────────────────────────────

    fun buildStatus(
        isNight: Boolean,
        // Geleidelijke nacht-overgang (17/07/2026) — zie kdoc bij
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
        appendLine(" 🧠 FCL V7 v5.3.2")
        appendLine("════════════════════════")
        appendLine()

        appendLine(buildSituatieSectie(isNight, ui, advice, nightTransitionFraction))

        appendLine(buildBeslissingSectie(bolusAmount, basalRate, shouldDeliver))
        appendLine()

        appendLine(buildDosesSectie(history))
        appendLine()

        appendLine("🏃 ${str.activiteit}")
        appendLine("─────────────────────")
        // 06/07/2026 — korte indicatie laatste uur, los van de uitgebreide
        // 8-uurs-geschiedenis die alleen in FCLvNext_ActivityLog_v2.csv staat.
        val stepsTxt = if (ui.recentSteps1h >= 0) "${ui.recentSteps1h}" else "–"
        val calTxt = if (ui.recentCalories1h >= 0.0) "${"%.0f".format(ui.recentCalories1h)} kcal" else "–"
        val hrTxt = if (ui.recentHr1h >= 0.0) "${"%.0f".format(ui.recentHr1h)} bpm" else "–"
        appendLine("• Laatste uur: 👣 $stepsTxt  🔥 $calTxt  ❤ $hrTxt")
        // 06/07/2026 — gedetecteerd activiteitstype, apart van de meetwaarden
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
        // 14/07/2026 — AIGF-regel: altijd tonen zodra de functie AAN
        // staat in Settings. HERSCHREVEN 28/07/2026 na het herontwerp in twee
        // componenten. HERSCHREVEN 16/08/2026 — controlevraag: de tekst
        // gaf tot nu toe alleen het percentage en een losse zin, zonder te
        // laten zien wat dat CONCREET voor de dosering betekent, en zonder
        // duidelijk te maken dat A alleen kan VERLAGEN en B alleen kan
        // VERHOGEN (asymmetrisch, bewust — zie kdoc bij aigfCommitBoost/
        // aigfAfterloadScale in FCLvNext.kt). Nu met een expliciete
        // "→ effect op dosering"-regel per component, en zonder de dode
        // "nog geen commit deze episode"-tak (component B is sinds het
        // HERONTWERP altijd live, nooit meer per-episode bevroren — zie kdoc
        // bij lastSmoothedAigfBPct in FCLvNext.kt).
        if (ui.aigfEnabled) {
            appendLine()
            appendLine("• AIGF (Activiteits Insuline Gevoeligheidsfactor): AAN")

            // ── Component A: vorige dag/naijling — kan dosering alleen VERLAGEN ──
            if (ui.aigfAReasonNl.isNotEmpty()) {
                appendLine("   Laatste 24u (naijling vorige dag): nog geen berekening — ${ui.aigfAReasonNl}")
            } else {
                val pctA = ui.aigfAPct ?: 100.0
                val uitlegA = when {
                    pctA > 100.5 -> "duidelijk actiever dan gebruikelijk"
                    pctA < 99.5  -> "rustiger dan gebruikelijk (verlaagt niets — component A verhoogt nooit)"
                    else         -> "ongeveer zoals gebruikelijk"
                }
                val effectA = if (pctA > 100.5) "×${"%.2f".format(100.0 / pctA)} op de dosering na de piek (iets minder)" else "geen effect"
                appendLine("   Laatste 24u (naijling vorige dag): ${"%.0f".format(pctA)}% — $uitlegA")
                appendLine("     → effect op dosering: $effectA")
                val opbouwA = if (ui.aigfADaysOfHistory < 5.0)
                    " (nog opbouwend: ${"%.1f".format(ui.aigfADaysOfHistory)}/5,0 dagen historie)" else ""
                appendLine(
                    "     ${"%.0f".format(ui.aigfACurrentCal24h)} kcal laatste 24u, " +
                        "gebruikelijk ${"%.0f".format(ui.aigfABaselineCal24h)} kcal$opbouwA"
                )
            }

            // ── Component B: recente uren, live — kan dosering alleen VERHOGEN ──
            if (ui.aigfBReasonNl.isNotEmpty()) {
                appendLine("   Laatste 4u (live, elke cyclus bijgewerkt): nog geen berekening — ${ui.aigfBReasonNl}")
            } else {
                val pctB = ui.aigfBPct ?: 100.0
                val wakePct = ui.aigfBWakeOverlapFrac * 100.0
                val uitlegB = when {
                    wakePct < 25.0 -> "grotendeels nachtrust in dit venster, telt nauwelijks mee"
                    pctB > 100.5   -> "duidelijk actiever dan gebruikelijk (verhoogt niets — component B verlaagt nooit)"
                    pctB < 99.5    -> "rustiger dan gebruikelijk"
                    else           -> "ongeveer zoals gebruikelijk"
                }
                val effectB = if (pctB < 99.5) "×${"%.2f".format(100.0 / pctB)} op grote commits (iets meer)" else "geen effect"
                appendLine("   Laatste 4u (live, elke cyclus bijgewerkt): ${"%.0f".format(pctB)}% (wakker-aandeel ${"%.0f".format(wakePct)}%) — $uitlegB")
                appendLine("     → effect op dosering: $effectB")
                appendLine(
                    "     ${"%.0f".format(ui.aigfBCurrentCal4h)} kcal laatste 4u, " +
                        "gebruikelijk ${"%.0f".format(ui.aigfBBaselineCal4h)} kcal"
                )
            }

            // ── Vandaag "wakker sinds" ──
            val wakkerTxt = ui.aigfDaystartTodayMs?.let { DateTime(it).toString("HH:mm") }
                ?: "nog niet vastgesteld (nog geen ${FclWakeDetector.STEP_THRESHOLD} stappen binnen ${FclWakeDetector.STEP_WINDOW_MIN} min gezien vandaag)"
            appendLine("   Actief sinds vandaag: $wakkerTxt")
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