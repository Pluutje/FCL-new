package app.aaps.plugins.aps.openAPSFCL.vnext.logging

import android.os.Environment
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.EpisodeMetrics
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * FclLearnerLogger — schrijft één CSV-regel per learner-evaluatie.
 *
 * Bestandsnaam: FCLvNext_LearnerLog_v1.csv
 * Locatie:      Documents/AAPS/ANALYSE/ (zelfde map als de algoritme-CSV)
 *
 * Dit bestand bevat ALLE informatie die de learner gebruikte voor
 * zijn beslissing: de ruwe EpisodeMetrics zoals de analyzer ze zag,
 * de interne accumulatoren, de diagnose en het resultaat.
 *
 * Het is bewust los van FCLvNext_Log_v8.csv omdat die per 5-min-cyclus
 * logt en de learner per episode evalueert — die twee tijdschalen
 * zijn niet te combineren zonder data-herhaling.
 *
 * Eén bestand bevat alle leertypes (EPISODE, EB, FRONTLOAD) zodat
 * de evaluatievolgorde precies te reconstrueren is.
 */
object FclLearnerLogger {

    private const val SCHEMA_VERSION = "v1"
    private const val FILE_NAME      = "FCLvNext_LearnerLog_v1.csv"
    private const val SEP            = ";"

    /**
     * Optionele callback die wordt aangeroepen na elk volledig episode-log.
     * Geregistreerd door DetermineBasalFCL (die wél een Context heeft) zodat
     * FclLearnerLogger zelf context-vrij blijft.
     *
     * Gebruik: onEpisodeLogged = { metrics -> FclAiAdvisorScheduler.runIfDue(context, listOf(metrics)) }
     *
     * (01/07/2026, Ecko): dit is de koppelplek voor de AI-parameter-adviseur.
     * Episode-afsluiting is het logische moment: alle metrics zijn beschikbaar
     * en de AI kan per episode beoordelen of er reden is voor een dagrapport.
     */
    var onEpisodeLogged: ((EpisodeMetrics) -> Unit)? = null

    /**
     * Volledige, canonieke kolomvolgorde over alle vier regeltypes
     * (EPISODE, EB, FRONTLOAD, V). De header wordt bij de eerste regel
     * uit values.keys opgebouwd — als verschillende types verschillende
     * keysets/volgordes hebben, raakt de CSV na de eerste regel corrupt.
     * append() valideert hiertegen.
     */
    private val ALL_COLUMNS = listOf(
        "schema_version", "ts_eval_utc", "type", "ep_start_utc", "ep_end_utc", "ep_duration_min",
        "peak_bg", "nadir_bg", "start_bg", "rise_magnitude", "time_to_peak_min", "hypo_detected",
        "hypo_minutes", "total_ins_u", "total_ins_inc_ext_u", "external_bolus_u", "has_manual_correction", "first_big_commit_frac",
        "first_commit_u", "follow_up_commit_count", "iob_ratio_at_peak", "iob_ratio_at_15min", "max_iob_ratio", "brake_active_cycles",
        "cap_reached_cycles", "early_boost_was_active", "early_boost_delivered_u", "early_boost_frac", "afterload_was_active", "first_frontload_minutes",
        "pred_fout_0_20", "pred_fout_20_40", "advisor_weight", "current_sterkte_pct", "diagnose", "raw_delta_d",
        "raw_delta_f", "accum_d_before", "accum_f_before", "ep_count_before", "week_delta_d_before", "week_delta_f_before",
        "aanpassing_geblokt", "old_d", "old_f", "new_d", "new_f", "eb_signal",
        "eb_tier", "eb_old_boost", "eb_new_boost", "eb_old_watching", "eb_new_watching", "eb_step",
        "fl_richting", "fl_gemiddelde_marge_min", "fl_oude_wmd", "fl_nieuwe_wmd", "fl_oude_wff", "fl_nieuwe_wff", "fl_oude_peak_bias", "fl_nieuwe_peak_bias", "fl_bruikbaar_count", "v_signal",
        "v_old_extra", "v_new_extra", "v_step", "v_cluster_fires",
        "lcd_signal", "lcd_old_ref", "lcd_new_ref", "lcd_step", "lcd_last_commit_frac", "lcd_last_commit_min_before_peak"
    )

    /** Start een rij met alle 64 kolommen leeg, in de canonieke volgorde. */
    private fun emptyRow(): LinkedHashMap<String, String> {
        val values = linkedMapOf<String, String>()
        ALL_COLUMNS.forEach { values[it] = "" }
        return values
    }

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    // ── Pubieke schrijffuncties ────────────────────────────────────────────

    /**
     * Schrijf een EPISODE-regel: wat DFLearner.evaluate() zag en besloot.
     *
     * @param metrics       De EpisodeMetrics zoals de analyzer ze berekende.
     * @param diagnose      Diagnosecode uit de when-tak (bijv. "TIMING_SPREAD").
     * @param rawDeltaD     Ongeschaalde D-stap berekend voor deze episode.
     * @param rawDeltaF     Ongeschaalde F-stap berekend voor deze episode.
     * @param accumDVoor    Accumulatiewaarde D vóór toevoeging van deze episode.
     * @param accumFVoor    Accumulatiewaarde F vóór toevoeging van deze episode.
     * @param epCountVoor   Episodeteller vóór toevoeging van deze episode.
     * @param weekDVoor     Weekgrens-teller D vóór eventuele aanpassing.
     * @param weekFVoor     Weekgrens-teller F vóór eventuele aanpassing.
     * @param blokkade      Reden waarom er geen aanpassing was, of "" als wel aanpassing.
     * @param oldD          D vóór aanpassing (0.0 als geen aanpassing).
     * @param oldF          F vóór aanpassing (0.0 als geen aanpassing).
     * @param newD          D ná aanpassing (0.0 als geen aanpassing).
     * @param newF          F ná aanpassing (0.0 als geen aanpassing).
     */
    fun logEpisode(
        metrics:      EpisodeMetrics,
        diagnose:     String,
        rawDeltaD:    Double,
        rawDeltaF:    Double,
        accumDVoor:   Double,
        accumFVoor:   Double,
        epCountVoor:  Int,
        weekDVoor:    Double,
        weekFVoor:    Double,
        blokkade:     String,
        oldD:         Double = 0.0,
        oldF:         Double = 0.0,
        newD:         Double = 0.0,
        newF:         Double = 0.0
    ) {
        val values = emptyRow()
        fillCommon(values, "EPISODE", metrics)

        // DFLearner beslissing
        values["diagnose"]           = diagnose
        values["raw_delta_d"]        = fmt4(rawDeltaD)
        values["raw_delta_f"]        = fmt4(rawDeltaF)
        values["accum_d_before"]     = fmt4(accumDVoor)
        values["accum_f_before"]     = fmt4(accumFVoor)
        values["ep_count_before"]    = epCountVoor.toString()
        values["week_delta_d_before"]= fmt4(weekDVoor)
        values["week_delta_f_before"]= fmt4(weekFVoor)
        values["aanpassing_geblokt"] = blokkade
        values["old_d"]              = if (oldD != 0.0) fmt4(oldD) else ""
        values["old_f"]              = if (oldF != 0.0) fmt4(oldF) else ""
        values["new_d"]              = if (newD != 0.0) fmt4(newD) else ""
        values["new_f"]              = if (newF != 0.0) fmt4(newF) else ""

        // EB, FL en V kolommen leeg bij EPISODE
        values["eb_signal"]               = ""
        values["eb_tier"]                 = ""
        values["eb_old_boost"]            = ""
        values["eb_new_boost"]            = ""
        values["eb_old_watching"]         = ""
        values["eb_new_watching"]         = ""
        values["eb_step"]                 = ""
        values["fl_richting"]             = ""
        values["fl_gemiddelde_marge_min"] = ""
        values["fl_oude_wmd"]             = ""
        values["fl_nieuwe_wmd"]           = ""
        values["fl_oude_wff"]             = ""
        values["fl_nieuwe_wff"]           = ""
        values["fl_oude_peak_bias"]       = ""
        values["fl_nieuwe_peak_bias"]     = ""
        values["fl_bruikbaar_count"]      = ""
        values["v_signal"]        = ""
        values["v_old_extra"]     = ""
        values["v_new_extra"]     = ""
        values["v_step"]          = ""
        values["v_cluster_fires"] = ""

        append(values)

        // AI-trigger: callback aanroepen na het wegschrijven (niet ervóór — logging
        // is de definitieve bevestiging dat de episode-data compleet is).
        // Loopt op de aanroepende thread (doorgaans een achtergrond-werkthread van de
        // Analyzer); FclAiAdvisorScheduler.runIfDue() keert direct terug als het
        // interval nog niet verstreken is, of start de AI-call op zijn eigen executor.
        try { onEpisodeLogged?.invoke(metrics) } catch (_: Exception) { /* niet kritiek */ }
    }

    /**
     * Schrijf een EB-regel: wat evaluateEarlyBoost() zag en besloot.
     */
    fun logEarlyBoost(
        metrics:    EpisodeMetrics,
        signal:     String,   // "FORWARD" | "BACK" | "NONE"
        tier:       String,   // "T1" | "T2" | ""
        oldBoost:   Double,
        newBoost:   Double,
        oldWatching:Double,
        newWatching:Double,
        step:       Double
    ) {
        val values = emptyRow()
        fillCommon(values, "EB", metrics)

        // DFLearner kolommen leeg
        values["diagnose"]           = ""
        values["raw_delta_d"]        = ""
        values["raw_delta_f"]        = ""
        values["accum_d_before"]     = ""
        values["accum_f_before"]     = ""
        values["ep_count_before"]    = ""
        values["week_delta_d_before"]= ""
        values["week_delta_f_before"]= ""
        values["aanpassing_geblokt"] = ""
        values["old_d"]              = ""
        values["old_f"]              = ""
        values["new_d"]              = ""
        values["new_f"]              = ""

        // EarlyBoost
        values["eb_signal"]    = signal
        values["eb_tier"]      = tier
        values["eb_old_boost"] = fmt4(oldBoost)
        values["eb_new_boost"] = if (signal != "NONE") fmt4(newBoost) else ""
        values["eb_old_watching"] = fmt4(oldWatching)
        values["eb_new_watching"] = if (signal != "NONE") fmt4(newWatching) else ""
        values["eb_step"]      = fmt4(step)

        // FL en V leeg
        values["fl_richting"]             = ""
        values["fl_gemiddelde_marge_min"] = ""
        values["fl_oude_wmd"]             = ""
        values["fl_nieuwe_wmd"]           = ""
        values["fl_oude_wff"]             = ""
        values["fl_nieuwe_wff"]           = ""
        values["fl_oude_peak_bias"]       = ""
        values["fl_nieuwe_peak_bias"]     = ""
        values["fl_bruikbaar_count"]      = ""
        values["v_signal"]        = ""
        values["v_old_extra"]     = ""
        values["v_new_extra"]     = ""
        values["v_step"]          = ""
        values["v_cluster_fires"] = ""

        append(values)
    }

    /**
     * Schrijf een LCD-regel: wat DFLearner.evaluateLateCommitDecay() besloot
     * (de losse, van F ontkoppelde leeras voor refLcd — zie kdoc bij
     * DFMapping.REF_LCD_DEFAULT).
     */
    fun logLateCommitDecay(
        metrics: EpisodeMetrics,
        signal:  String,   // "FORWARD" | "BACK" | "NONE"
        oldRef:  Double,
        newRef:  Double,
        step:    Double
    ) {
        val values = emptyRow()
        fillCommon(values, "LCD", metrics)

        values["lcd_signal"]  = signal
        values["lcd_old_ref"] = fmt4(oldRef)
        values["lcd_new_ref"] = if (signal != "NONE") fmt4(newRef) else ""
        values["lcd_step"]    = fmt4(step)
        values["lcd_last_commit_frac"] = fmt4(metrics.lastSignificantCommitFrac)
        values["lcd_last_commit_min_before_peak"] =
            metrics.lastSignificantCommitMinutesBeforePeak?.toString() ?: ""

        append(values)
    }

    /**
     * Schrijf een FRONTLOAD-regel: wat FrontloadLearner.evaluate() besloot.
     * Episode-metrics zijn hier de gemiddelden over alle bruikbare episodes;
     * we loggen een synthetische "nul-metrics" met alleen de FL-velden gevuld.
     */
    fun logFrontload(
        richting:       String,   // "EERDER" | "LATER" | "GOED"
        gemMarge:       Int,
        oudeWmd:        Double,
        nieuweWmd:      Double,
        oudeWff:        Double = 0.0,
        nieuweWff:      Double = 0.0,
        oudePeakBias:   Double = 0.0,
        nieuwePeakBias: Double = 0.0,
        bruikbaarCount: Int
    ) {
        val values = emptyRow()

        values["schema_version"] = SCHEMA_VERSION
        values["ts_eval_utc"]    = fmt.format(java.time.Instant.now())
        values["type"]           = "FRONTLOAD"

        // Episode-kolommen niet van toepassing
        val leeg = listOf(
            "ep_start_utc","ep_end_utc","ep_duration_min",
            "peak_bg","nadir_bg","start_bg","rise_magnitude","time_to_peak_min",
            "hypo_detected","hypo_minutes","total_ins_u","total_ins_inc_ext_u",
            "external_bolus_u","has_manual_correction",
            "first_big_commit_frac","first_commit_u","follow_up_commit_count",
            "iob_ratio_at_peak","iob_ratio_at_15min","max_iob_ratio",
            "brake_active_cycles","cap_reached_cycles",
            "early_boost_was_active","early_boost_delivered_u","early_boost_frac",
            "afterload_was_active","first_frontload_minutes",
            "pred_fout_0_20","pred_fout_20_40","advisor_weight","current_sterkte_pct",
            "diagnose","raw_delta_d","raw_delta_f","accum_d_before","accum_f_before",
            "ep_count_before","week_delta_d_before","week_delta_f_before",
            "aanpassing_geblokt","old_d","old_f","new_d","new_f",
            "eb_signal","eb_tier","eb_old_boost","eb_new_boost",
            "eb_old_watching","eb_new_watching","eb_step",
            "v_signal","v_old_extra","v_new_extra","v_step","v_cluster_fires"
        )
        leeg.forEach { values[it] = "" }

        values["fl_richting"]             = richting
        values["fl_gemiddelde_marge_min"] = gemMarge.toString()
        values["fl_oude_wmd"]             = fmt4(oudeWmd)
        values["fl_nieuwe_wmd"]           = if (richting != "GOED") fmt4(nieuweWmd) else ""
        values["fl_oude_wff"]             = fmt4(oudeWff)
        values["fl_nieuwe_wff"]           = if (abs(nieuweWff - oudeWff) >= 0.001) fmt4(nieuweWff) else ""
        values["fl_oude_peak_bias"]       = fmt4(oudePeakBias)
        values["fl_nieuwe_peak_bias"]     = if (abs(nieuwePeakBias - oudePeakBias) >= 0.001) fmt4(nieuwePeakBias) else ""
        values["fl_bruikbaar_count"]      = bruikbaarCount.toString()

        append(values)
    }

    /**
     * Schrijf een V-regel: wat VLearner.evaluate() besloot voor een
     * PERSIST-cluster. Geen EpisodeMetrics — PERSIST-clusters zijn
     * onafhankelijk van maaltijd-episodes (zie VLearner.kt).
     */
    fun logVLearner(
        signal:       String,   // "FORWARD" | "BACK" | "NONE"
        oldVExtra:    Double,
        newVExtra:    Double,
        step:         Double,
        clusterFires: Int
    ) {
        val values = emptyRow()

        values["schema_version"] = SCHEMA_VERSION
        values["ts_eval_utc"]    = fmt.format(java.time.Instant.now())
        values["type"]           = "V"

        // Episode-, EB- en FL-kolommen niet van toepassing
        val leeg = listOf(
            "ep_start_utc","ep_end_utc","ep_duration_min",
            "peak_bg","nadir_bg","start_bg","rise_magnitude","time_to_peak_min",
            "hypo_detected","hypo_minutes","total_ins_u","total_ins_inc_ext_u",
            "external_bolus_u","has_manual_correction",
            "first_big_commit_frac","first_commit_u","follow_up_commit_count",
            "iob_ratio_at_peak","iob_ratio_at_15min","max_iob_ratio",
            "brake_active_cycles","cap_reached_cycles",
            "early_boost_was_active","early_boost_delivered_u","early_boost_frac",
            "afterload_was_active","first_frontload_minutes",
            "pred_fout_0_20","pred_fout_20_40","advisor_weight","current_sterkte_pct",
            "diagnose","raw_delta_d","raw_delta_f","accum_d_before","accum_f_before",
            "ep_count_before","week_delta_d_before","week_delta_f_before",
            "aanpassing_geblokt","old_d","old_f","new_d","new_f",
            "eb_signal","eb_tier","eb_old_boost","eb_new_boost",
            "eb_old_watching","eb_new_watching","eb_step",
            "fl_richting","fl_gemiddelde_marge_min","fl_oude_wmd","fl_nieuwe_wmd","fl_oude_wff","fl_nieuwe_wff","fl_oude_peak_bias","fl_nieuwe_peak_bias","fl_bruikbaar_count"
        )
        leeg.forEach { values[it] = "" }

        values["v_signal"]       = signal
        values["v_old_extra"]    = fmt4(oldVExtra)
        values["v_new_extra"]    = if (signal != "NONE") fmt4(newVExtra) else ""
        values["v_step"]         = fmt4(step)
        values["v_cluster_fires"]= clusterFires.toString()

        append(values)
    }

    // ── Intern ────────────────────────────────────────────────────────────

    /** Vul de gemeenschappelijke episode-kolommen. */
    private fun fillCommon(values: LinkedHashMap<String, String>, type: String, m: EpisodeMetrics) {
        // values is al een emptyRow() — alleen overschrijven wat van toepassing is
        values["schema_version"]  = SCHEMA_VERSION
        values["ts_eval_utc"]     = fmt.format(java.time.Instant.now())
        values["type"]            = type

        values["ep_start_utc"]    = fmt.format(m.start)
        values["ep_end_utc"]      = fmt.format(m.end)
        values["ep_duration_min"] = m.durationMinutes.toString()

        values["peak_bg"]         = fmt2(m.peakBg)
        values["nadir_bg"]        = fmt2(m.minBgInWindow)
        values["start_bg"]        = fmt2(m.startBg)
        values["rise_magnitude"]  = fmt2(m.riseMagnitude)
        values["time_to_peak_min"]= m.timeToPeakMinutes?.toString() ?: ""
        values["hypo_detected"]   = m.hypoDetected.toString()
        values["hypo_minutes"]    = m.hypoMinutes.toString()

        values["total_ins_u"]          = fmt2(m.totalInsulinDelivered)
        values["total_ins_inc_ext_u"]  = fmt2(m.totalInsulinIncExternal)
        values["external_bolus_u"]     = fmt2(m.externalBolusTotal)
        values["has_manual_correction"]= m.hasManualCorrection.toString()

        values["first_big_commit_frac"]  = fmt3(m.firstBigCommitFrac)
        values["first_commit_u"]         = fmt2(m.firstCommitU)
        values["follow_up_commit_count"] = m.followUpCommitCount.toString()
        values["iob_ratio_at_peak"]      = fmt3(m.iobRatioAtPeak)
        values["iob_ratio_at_15min"]     = fmt3(m.iobRatioAt15min)
        values["max_iob_ratio"]          = fmt3(m.maxIobRatio)
        values["brake_active_cycles"]    = m.brakeActiveCycles.toString()
        values["cap_reached_cycles"]     = m.capReachedCycles.toString()

        values["early_boost_was_active"] = m.earlyBoostWasActive.toString()
        values["early_boost_delivered_u"]= fmt2(m.earlyBoostDeliveredU)
        values["early_boost_frac"]       = fmt3(m.earlyBoostFrac)

        values["afterload_was_active"]   = m.afterloadWasActive.toString()
        values["first_frontload_minutes"]= m.firstFrontloadMinutes.toString()

        values["pred_fout_0_20"]  = m.predFout0_20?.let  { fmt2(it) } ?: ""
        values["pred_fout_20_40"] = m.predFout20_40?.let { fmt2(it) } ?: ""

        values["advisor_weight"]       = fmt3(m.advisorWeight)
        values["current_sterkte_pct"]  = m.currentSterkte.toString()
    }

    private fun fmt2(v: Double) = "%.2f".format(v)
    private fun fmt3(v: Double) = "%.3f".format(v)
    private fun fmt4(v: Double) = "%.4f".format(v)

    /** Schrijf één regel naar het CSV-bestand. Maakt het bestand + header aan als nodig. */
    private fun append(values: LinkedHashMap<String, String>) {
        try {
            val dir = File(
                Environment.getExternalStorageDirectory(),
                "Documents/AAPS/ANALYSE"
            )
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, FILE_NAME)
            val isNew = !file.exists() || file.length() == 0L

            file.appendText(buildString {
                if (isNew) {
                    append(values.keys.joinToString(SEP))
                    append("\n")
                }
                append(values.values.joinToString(SEP))
                append("\n")
            })
        } catch (_: Exception) {
            // Stille fout: loggen mag het algoritme nooit onderbreken
        }
    }
}