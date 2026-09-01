package app.aaps.plugins.aps.openAPSFCL.vnext.lang

object FclStrings_EN : FclStrings {

    // ── General ───────────────────────────────────────────────────────────
    override val appName            = "FCL vNext"
    override val statusTabLabel     = "Status"
    override val analyzerTabLabel   = "Analyzer"
    override val settingsTabLabel   = "Settings"
    override val close              = "Close"
    override val save               = "Save"
    override val cancel             = "Cancel"
    override val change             = "Change"
    override val yes                = "Yes"
    override val no                 = "No"
    override val unknown            = "Unknown"
    override val none               = "None"
    override val auto               = "Auto"
    override val units              = "U"
    override val mmolL              = "mmol/L"
    override val mgdl               = "mg/dL"
    override val per5min            = "/5min"
    override val perHour            = "/hr"
    override val days               = "days"
    override val minutes            = "min"
    override val times              = "\u00D7"

    // ── Status formatter ──────────────────────────────────────────────────
    override val situatieOverdag            = "SITUATION \u2014 DAYTIME"
    override val situatieNacht              = "SITUATION \u2014 NIGHT"
    override val glucose                    = "Glucose"
    override val iob                        = "IOB"
    override val fclPiek                    = "FCL peak"
    override val fclPiekGeen                = "No active rise"
    override val fclPiekSterkeStijging      = "Strong rise active"
    override val fclPiekWatching            = "Watching active"
    override val verwacht                   = "expected"
    override val beslissing                 = "DECISION"
    override val geenIngreep                = "No intervention this cycle"
    override val latsteDosisFcl             = "RECENT DOSES"
    override val latsteDosisFclEmpty        = "No recent deliveries"
    override val algoritmeparameters        = "ALGORITHM PARAMETERS"
    override val sterkte                    = "Strength (S)"
    override val timing                     = "Timing   (T)"
    override val volhoudendheid             = "Persist.  (V)"
    override val frontloadTimingTitel       = "Frontload timing"
    override val sterkteTitel                = "Strength"
    override val timingTitel                 = "Timing"
    override val vasthoudendheidTitel        = "Persistence"
    override val nachtN                     = "Night-N"
    override val nachtRespons               = "Night response"
    override val insulineverdeling          = "Insulin distribution"
    override val activiteit                 = "ACTIVITY"
    override val stepsThreshold             = "Steps 5min=%d threshold=%d"

    override fun nightStyleLabel(key: String) = when (key) {
        "VERY_GUARDED" -> "\uD83D\uDED1 Very cautious"
        "GUARDED"      -> "\uD83E\uDDEF Cautious"
        "BALANCED"     -> "\u2696\uFE0F Balanced"
        "RESPONSIVE"   -> "\uD83C\uDF19 Responds earlier"
        "PROACTIVE"    -> "\uD83D\uDE80 Proactive"
        else           -> key
    }

    override fun doseStyleLabel(key: String) = when (key) {
        "SUPER_SMOOTH" -> "\uD83C\uDF0A\uD83C\uDF0A Super smooth"
        "VERY_SMOOTH" -> "\uD83C\uDF0A Ultra smooth"
        "SMOOTH"      -> "\uD83E\uDEB7 Smooth"
        "BALANCED"    -> "\u2696\uFE0F Balanced"
        "PULSED"      -> "\uD83D\uDD28 Pulsed"
        "VERY_PULSED" -> "\u26A1 Ultra pulsed"
        else          -> key
    }

    // ── Analyzer — general ────────────────────────────────────────────────
    override val episodeViewer          = "Episode Viewer"
    override val terug                  = "\u2190 Back"
    override val live                   = "LIVE"
    override val geenEpisodesGevonden   = "No episodes found."
    override val gegevens               = "Data"
    override val gegevensInfo           = "%d cycles \u2022 data from AAPS database"
    override val vernieuwen             = "Refresh"
    override val episodes               = "Episodes"
    override val advisor                = "Automation"
    override val glucoseBereiken        = "Glucose ranges"
    override val laatseSyncLabel        = "Last sync:"
    override val tbr                    = "TBR"
    override val tbt                    = "TBT"
    override val tir                    = "TIR"
    override val tar                    = "TAR"
    override val reddingskoolhydraten   = "Rescue carbs"
    override val daadwerkelijkGenomen   = "Actually taken:"

    // ── Analyzer — episode overview ───────────────────────────────────────
    override val overzicht              = "Overview"
    override val piekAnalyse            = "Peak & forecast"
    override val marges                 = "Brake activity"
    override val samenvatting           = "Summary"
    override val insulineLabel          = "Insulin"
    override val iobAtPiek              = "IOB@peak"
    override val peakPlusMin            = "Peak +min"
    override val werkelijkePiek         = "Actual peak"
    override val predPiek               = "Pred. peak"
    override val deltaVoorspelling      = "\u0394 prediction"
    override val gewicht                = "Weight"
    override val meegeteldInAanbeveling = "Included in last recommendation"
    override val verbruiktNaWijziging   = "Consumed after profile change"
    override val behoortBijAanbeveling  = "Belongs to last recommendation"
    override val beschikbaarVoorAdvies  = "Available for advice"
    override val glucoseBleefStabiel    = "Glucose remained stable within target."
    override val episodeClassLabel      = "Meal type"
    override fun episodeTypeLabel(key: String) = when (key) {
        "SNEL"    -> "\u26A1 Fast"
        "NORMAAL" -> "\uD83C\uDF7D\uFE0F Normal"
        "TRAAG"   -> "\uD83D\uDC22 Slow"
        else      -> key
    }

    // ── Analyzer — peak analysis ──────────────────────────────────────────
    override val piekVoorspellingVsWerkelijkheid = "Peak prediction vs reality"
    override val xAsMinutenNaStart  = "X-axis: minutes since episode start"
    override val yAsBg              = "Y-axis: BG"
    override val geleKnip           = "Yellow line = actual peak"
    override val vroegeFout         = "Early error (0\u201320 min)"
    override val foutBijPiek        = "Error at peak"
    override val werkelijkePiekLabel = "Actual peak"
    override val werkelijkBg        = "Actual BG"
    override val predIobCorrect     = "Pred (IOB-corrected)"
    override val ballistisch        = "Ballistic"

    // ── Analyzer — margins ────────────────────────────────────────────────
    override val margesTotDrempels  = "Margins to thresholds over time"
    override val margesUitleg       = "Negative = below threshold (brake inactive) \u00B7 Zero = threshold reached \u00B7 Positive = brake active"
    override val iobMargeLabel      = "IOB margin to brake"
    override val predMargeWatching  = "Pred margin to WATCHING"
    override val predMargeDoel      = "Pred margin to target"
    override val voorspellingsKwaliteit = "Prediction quality"
    override val gemVoorspelling    = "Avg. prediction"
    override val iobCorrectie       = "IOB correction"
    override val gemFoutPerTijdvenster = "Average error per time window"
    override val venster0_20        = "0\u201320 min"
    override val venster20_40       = "20\u201340 min"
    override val vensterBoven40     = ">40 min"
    override val floorCorrectieActief = "Floor correction active in %d cycles (avg. +%.2f mmol) \u2014 compensates"
    override val dalenTrendGeblokkeerd = "Downward trend blocked"
    override val remActivaties      = "Brake activations"
    override val geenRemActivaties  = "No brake activations in this episode"
    override val suppress           = "Suppress"
    override val lockout            = "Lockout"
    override val commitGeblokkeerd  = "Commit blocked"
    override val overigeEvents      = "Other events"
    override val earlyReset         = "Early reset"
    override val downtrendLocked    = "Downtrend locked"
    override val sensorBlip         = "Sensor blip"

    // ── Advisor ───────────────────────────────────────────────────────────
    override val advisorAnalyse     = "Auto Learner"
    override val advisorOverzicht   = "Analysis overview"
    override val automaat           = "Day"
    override val maxSmb             = "MaxSMB"
    override val parameters         = "Fine-tuning"
    override val nachtNLabel        = "Night"
    override val isfLabel           = "ISF"
    override val analyse            = "Pattern analysis"
    override val confidence         = "Confidence"
    override val gebruikt           = "Used"
    override val uitgesloten        = "Excluded"
    override val avgPeak            = "Avg peak"
    override val avgInsuline        = "Avg insulin"
    override val avgDuur            = "Avg duration"
    override val hyper              = "High"
    override val hypo               = "Low"
    override val actuelParams       = "Current parameters"
    override val watchingMinDelta   = "Watching min delta"
    override val peakPredThreshold  = "Peak threshold"
    override val autoLeert          = "Auto-learns"
    override val tempo              = "Rate"
    override val leertMee           = "Learning"

    // ── DFControl tab ─────────────────────────────────────────────────────
    override val dfControlToepassen     = "Apply in AAPS"
    override val dfControlHoeVroeg      = "how early the system responds"
    override val sterkteLower           = "strength"
    override val timingLower            = "timing"
    override val volhoudendheidLower    = "persistence"
    override val zeervroeg              = "Very early"
    override val vroeg                  = "Early"
    override val normaal                = "Normal"
    override val laat                   = "Late"
    override val zeerlaat               = "Very late"
    override val aggressiviteitsKaart   = "Aggressiveness"
    override val aggressiviteitsUitleg  = "Shifts learned S/T/V and frontload timing \u2014 level 5 = no effect"

    // ── MaxSMB tab ────────────────────────────────────────────────────────
    override val maxSmbGeschiedenisLabel = "MaxSMB learning history"
    override val maxSmbActiefLabel       = "Active maxSMB"
    override val maxSmbAutoLabel         = "Auto-learn"

    // ── Settings ──────────────────────────────────────────────────────────
    override val settingsDosisBehavior          = "\uD83D\uDC89 Dosing & behaviour"
    override val settingsMaxBolusDay            = "Max bolus day"
    override val settingsMaxBolusNight          = "Max bolus night"
    override val settingsMaxIob                 = "Max IOB"
    override val settingsInsulineverdeling      = "\uD83D\uDC89 Insulin distribution"
    override val settingsNachtrespons           = "\uD83C\uDF19 Night response"
    override val settingsDagNacht               = "\u23F0 Day / night context"
    override val settingsAutosens               = "\uD83D\uDEE1\uFE0F AutoSens & activity"
    override val settingsActiviteit             = "\uD83D\uDEB6 Activity"
    override val settingsWeekendDagen           = "Weekend days"
    override val settingsOchtendStart           = "Morning start (weekdays)"
    override val settingsOchtendStartWeekend    = "Morning start (weekend)"
    override val settingsNachtStart             = "Night start"
    override val expertModus                    = "Expert mode"
    override val expertModusActief              = "Expert mode active."
    override val expertModusMeerOpties          = "More options coming in a future version."
    override val expertT1BoostTitel              = "T1 boost (experimental)"
    override val expertT1BoostUitleg             = "Gives slightly more early insulin during a meal, but only once the rise has held for several consecutive readings. Never affects the very first, still-uncertain dose. Off by default."
    override val expertPeakStartTitel             = "Early-signal threshold (test phase)"
    override val expertPeakStartUitleg            = "How high the predicted peak must already be before WatchingFrontload can become eligible at all. Lower = eligible earlier, even for meals with a lower predicted peak. Default 9.0 mmol/L = current, unchanged behaviour. Lower it very gradually and watch the outcome yourself."
    override val expertPeakStartWaarde            = "Threshold: %.1f mmol/L"
    override val expertPin                      = "Enter the PIN code (default: 0000)."
    override val expertPinFout                  = "Incorrect PIN"
    override val pincode                        = "PIN"
    override val openen                         = "Open"
    override val annuleren                      = "Cancel"
    override val sluiten                        = "Close"

    override fun autosensGedragLabel(key: String) = when (key) {
        "OFF"        -> "Disabled"
        "LIGHT"      -> "Light"
        "NORMAL"     -> "Normal (recommended)"
        "STRONG"     -> "Strong"
        "AGGRESSIVE" -> "Aggressive"
        else         -> key
    }

    override fun autosensStabiliteitLabel(key: String) = when (key) {
        "VERY_STABLE" -> "Very stable"
        "STANDARD"    -> "Standard"
        "RESPONSIVE"  -> "Responsive"
        else          -> key
    }

    override fun activiteitLabel(key: String) = when (key) {
        "OFF" -> "Off"
        else  -> "On"
    }

    // ── Advisor: learning explanation per axis ──────────────────────────────
    override fun diagnoseTekst(diagnose: String) = when (diagnose) {
        "OK" ->
            "Result within expectation, no adjustment needed"
        "HYPO" ->
            "BG too low after the meal — dose reduced"
        "TBT" ->
            "BG stayed below target for a prolonged time without a true hypo — dose slightly reduced"
        "HYPO_D_PROBLEEM" ->
            "BG too low despite normal buildup — strength reduced"
        "HYPO_D_DEMPED" ->
            "BG too low, partly dampened by afterload — strength slightly reduced"
        "HYPO_FRONTLOAD" ->
            "BG too low after an oversized first dose — strength and timing reduced"
        "TE_VROEG_SOLO" ->
            "First dose too large with no follow-up doses — timing pulled back"
        "MEER_DOSIS" ->
            "Peak too high — strength increased"
        "MEER_DOSIS_VROEG_ONDERSCHAT" ->
            "Peak too high due to underestimation early in the meal — strength increased"
        "TE_WEINIG" ->
            "Peak too low — strength reduced"
        "TIMING_SPREAD" ->
            "Peak too high and dose spread too late — timing shifted earlier"
        "TIMING_SPREAD_VROEG" ->
            "Peak too high despite an early start — timing shifted further earlier"
        "FRONTLOAD_LAG" ->
            "First dose was slow to start — timing shifted earlier"
        "FRONTLOAD_LAG_VROEG" ->
            "First dose was slow to start despite an early start — timing shifted further earlier"
        "IOB_SPREAD_TE_LAAT" ->
            "Built-up insulin took effect too late — timing adjusted"
        "IOB_SPREAD_TE_LAAT_VERSPREID" ->
            "Insulin spread evenly but should have been shifted forward — timing adjusted"
        "HYPO_LATE_TIMING" ->
            "BG too low due to late insulin, not too much — timing shifted earlier"
        "RESCUE_OVERPOWERED" ->
            "Rescue dose needed despite normal buildup — strength reduced"
        "EARLYBOOST_TE_KLEIN" ->
            "Early boost was too small to dampen the peak — frontload increased"
        "AFTERLOAD_GUARD_OK" ->
            "Afterload guard worked as intended, no adjustment"
        "AUTO_DISABLED" ->
            "Automaton is off — no adjustments applied"
        "COOLDOWN" ->
            "Recently adjusted — waiting for cooldown"
        "GEEN_EPISODE", "SKIP_GEEN_EPISODE" ->
            "No suitable episode found to learn from"
        else -> diagnose
    }

    override fun timingDiagnoseTekst(diagnose: String) = when (diagnose) {
        "HYPO_FRONTLOAD" ->
            "Timing pulled back — first dose was too large for the meal speed"
        "TE_VROEG_SOLO"  ->
            "Timing pulled back — first commit too dominant without follow-up doses"
        "TIMING_SPREAD", "TIMING_SPREAD_VROEG" ->
            "Timing shifted earlier — insulin was distributed too late"
        "FRONTLOAD_LAG", "FRONTLOAD_LAG_VROEG" ->
            "Timing shifted earlier — first large dose was slow to start"
        "IOB_SPREAD_TE_LAAT" ->
            "Timing shifted earlier — accumulated insulin took effect too late"
        "MEER_DOSIS_VROEG_ONDERSCHAT" ->
            "Timing shifted earlier — system underestimated the rise early in the meal"
        else -> ""
    }

    override fun frontloadTekst(richting: String, gemiddeldeMargeMin: Int) = when (richting) {
        "EERDER" ->
            "Peak occurred $gemiddeldeMargeMin min late on average — timing shifted earlier"
        "LATER" ->
            "Peak occurred ${-gemiddeldeMargeMin} min early on average — timing shifted later"
        "GOED" ->
            "Peak timing within margin, no adjustment needed"
        else -> richting
    }

    override fun vSignaalTekst(signal: String) = when (signal) {
        "FORWARD" ->
            "Persistent correction didn't help enough — persistence increased"
        "BACK" ->
            "Persistent correction overshot — persistence decreased"
        "NONE" ->
            "Persistent corrections are working as expected"
        else -> signal
    }

    override fun frontloadStatusTekst(key: String) = when (key) {
        "STANDAARD"    -> "standard"
        "LICHT_VOOR"   -> "slightly earlier"
        "STERK_VOOR"   -> "much earlier"
        "LICHT_TERUG"  -> "slightly later"
        "STERK_TERUG"  -> "much later"
        else           -> key
    }

    override fun relatieveToelichting(deltaPct: Int) = when {
        deltaPct == 0 -> "At standard level"
        deltaPct > 0  -> "$deltaPct% higher than standard"
        else          -> "${-deltaPct}% lower than standard"
    }

    // ── Day/night labels ──────────────────────────────────────────────────
    override val sterkteLabelLaag       = "Low"
    override val sterkteLabelNormaal    = "Normal"
    override val sterkteLabelHoog       = "High"
    override val timingLabelTeVroeg     = "Much too early"
    override val timingLabelVroeg       = "Strongly early"
    override val timingLabelNormaal     = "Normal"
    override val timingLabelLaat        = "Late"
    override val volhoudendheidLabel    = "Slightly cautious"
    // StatusFormatter inline labels
    override val frontloadSterkVroeg       = "Strongly early"
    override val frontloadVroeg            = "Early"
    override val frontloadNeutraal         = "Neutral"
    override val frontloadLaat             = "Late"
    override val frontloadSterkLaat        = "Strongly late"
    override val sterkteHoog               = "High"
    override val sterkteIetsBoven          = "Slightly above standard"
    override val sterkteStandaard          = "Standard"
    override val sterkteIetsOnder          = "Slightly below standard"
    override val sterkteLaagLabel          = "Low"
    override val volhVasthoudend           = "Persistent"
    override val volhIetsVasthoudender     = "Slightly more persistent"
    override val volhStandaard             = "Standard"
    override val volhIetsVoorzichtiger     = "Slightly cautious"
    override val volhVoorzichtig           = "Cautious"
    override val nachtRustigerDanDag       = "%d%% quieter than day"
    override val analyzerConfigHeader      = "ANALYZER-DRIVEN VALUES (config)"
    override val analyzerFijnafstemming    = "FINE-TUNING"
    override val maxSmbDagLabel            = "MaxSMB day"
    override val maxSmbVerlaagd            = "lowered"
    override val maxSmbVerhoogd            = "raised"
    override val iobRemdrempel             = "IOB brake threshold"
    override val geenActiviteitdata        = "No activity data"
    override val geenResistentieLog        = "No resistance log"
    override val nogGeenData               = "No data yet"
    override val autoSensHeader            = "AUTO-SENS"
    override val configNogNietBeschikbaar  = "Config not yet available (waiting for first FCLvNext cycle)"
    override val expertModeAan             = "Expert mode on"
    override val expertModeUit             = "Expert mode off"
    override val fijnafstemming            = "Fine-tuning"
    override val autosensOverdagUit        = "AutoSens: ☀️ Daytime disabled (factor = 1.0)"
    override val autosensUit               = "AutoSens: ❌ OFF"

}