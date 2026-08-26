package app.aaps.plugins.aps.openAPSFCL.vnext.lang

object FclStrings_NL : FclStrings {

    // ── Algemeen ──────────────────────────────────────────────────────────
    override val appName            = "FCL vNext"
    override val statusTabLabel     = "Status"
    override val analyzerTabLabel   = "Analyzer"
    override val settingsTabLabel   = "Instellingen"
    override val close              = "Sluiten"
    override val save               = "Opslaan"
    override val cancel             = "Annuleren"
    override val change             = "Wijzig"
    override val yes                = "Ja"
    override val no                 = "Nee"
    override val unknown            = "Onbekend"
    override val none               = "Geen"
    override val auto               = "Auto"
    override val units              = "U"
    override val mmolL              = "mmol/L"
    override val mgdl               = "mg/dL"
    override val per5min            = "/5min"
    override val perHour            = "/uur"
    override val days               = "dagen"
    override val minutes            = "min"
    override val times              = "×"

    // ── Status formatter ──────────────────────────────────────────────────
    override val situatieOverdag            = "SITUATIE \u2014 OVERDAG"
    override val situatieNacht              = "SITUATIE \u2014 NACHT"
    override val glucose                    = "Glucose"
    override val iob                        = "IOB"
    override val fclPiek                    = "FCL piek"
    override val fclPiekGeen                = "Geen actieve stijging"
    override val fclPiekSterkeStijging      = "Sterke stijging actief"
    override val fclPiekWatching            = "Watching actief"
    override val verwacht                   = "verwacht"
    override val beslissing                 = "BESLISSING"
    override val geenIngreep                = "Geen ingreep deze cyclus"
    override val latsteDosisFcl             = "LAATSTE DOSES"
    override val latsteDosisFclEmpty        = "Geen recente afleveringen"
    override val algoritmeparameters        = "ALGORITMEPARAMETERS"
    override val sterkte                    = "Sterkte  (S)"
    override val timing                     = "Timing   (T)"
    override val volhoudendheid             = "Volhoud. (V)"
    override val frontloadTimingTitel       = "Frontload-timing"
    override val sterkteTitel                = "Sterkte"
    override val timingTitel                 = "Timing"
    override val vasthoudendheidTitel        = "Vasthoudendheid"
    override val nachtN                     = "Nacht-N"
    override val nachtRespons               = "Nacht-respons"
    override val insulineverdeling          = "Insulineverdeling"
    override val activiteit                 = "ACTIVITEIT"
    override val stepsThreshold             = "Steps 5min=%d threshold=%d"

    override fun nightStyleLabel(key: String) = when (key) {
        "VERY_GUARDED" -> "\uD83D\uDED1 Zeer terughoudend"
        "GUARDED"      -> "\uD83E\uDDEF Terughoudend"
        "BALANCED"     -> "\u2696\uFE0F Gebalanceerd"
        "RESPONSIVE"   -> "\uD83C\uDF19 Reageert eerder"
        "PROACTIVE"    -> "\uD83D\uDE80 Proactief"
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

    // ── Analyzer — algemeen ───────────────────────────────────────────────
    override val episodeViewer          = "Episode Viewer"
    override val terug                  = "\u2190 Terug"
    override val live                   = "LIVE"
    override val geenEpisodesGevonden   = "Geen episodes gevonden."
    override val gegevens               = "Gegevens"
    override val gegevensInfo           = "%d cycli \u2022 data direct uit AAPS database"
    override val vernieuwen             = "Vernieuwen"
    override val episodes               = "Episodes"
    override val advisor                = "Automaat"
    override val glucoseBereiken        = "Glucose bereiken"
    override val laatseSyncLabel        = "Laatste sync:"
    override val tbr                    = "TBR"
    override val tbt                    = "TBT"
    override val tir                    = "TIR"
    override val tar                    = "TAR"
    override val reddingskoolhydraten   = "Reddingskoolhydraten"
    override val daadwerkelijkGenomen   = "Daadwerkelijk genomen:"

    // ── Analyzer — episode overzicht ──────────────────────────────────────
    override val overzicht              = "Overzicht"
    override val piekAnalyse            = "Piek & voorspelling"
    override val marges                 = "Remactivaties"
    override val samenvatting           = "Samenvatting"
    override val insulineLabel          = "Insuline"
    override val iobAtPiek              = "IOB@piek"
    override val peakPlusMin            = "Peak +min"
    override val werkelijkePiek         = "Werkelijke piek"
    override val predPiek               = "Pred. piek"
    override val deltaVoorspelling      = "\u0394 voorspelling"
    override val gewicht                = "Gewicht"
    override val meegeteldInAanbeveling = "Meegeteld in laatste aanbeveling"
    override val verbruiktNaWijziging   = "Verbruikt na profielwijziging"
    override val behoortBijAanbeveling  = "Behoort bij laatste aanbeveling"
    override val beschikbaarVoorAdvies  = "Beschikbaar voor advies"
    override val glucoseBleefStabiel    = "Glucose bleef stabiel binnen doel."
    override val episodeClassLabel      = "Maaltijdtype"
    override fun episodeTypeLabel(key: String) = when (key) {
        "SNEL"    -> "\u26A1 Snel"
        "NORMAAL" -> "\uD83C\uDF7D\uFE0F Normaal"
        "TRAAG"   -> "\uD83D\uDC22 Traag"
        else      -> key
    }

    // ── Analyzer — piekanalyse ────────────────────────────────────────────
    override val piekVoorspellingVsWerkelijkheid = "Piekvoorspelling vs werkelijkheid"
    override val xAsMinutenNaStart  = "X-as: minuten na episodestart"
    override val yAsBg              = "Y-as: BG"
    override val geleKnip           = "Gele lijn = werkelijke piek"
    override val vroegeFout         = "Vroege fout (0\u201320 min)"
    override val foutBijPiek        = "Fout bij piek"
    override val werkelijkePiekLabel = "Werkelijke piek"
    override val werkelijkBg        = "Werkelijk BG"
    override val predIobCorrect     = "Pred (IOB-correct.)"
    override val ballistisch        = "Ballistisch"

    // ── Analyzer — marges ─────────────────────────────────────────────────
    override val margesTotDrempels  = "Marges tot drempels over tijd"
    override val margesUitleg       = "Negatief = onder drempel (rem inactief) \u00B7 Nul = drempel geraakt \u00B7 Positief = rem actief"
    override val iobMargeLabel      = "IOB-marge tot rem"
    override val predMargeWatching  = "Pred-marge tot WATCHING"
    override val predMargeDoel      = "Pred-marge tot doel"
    override val voorspellingsKwaliteit = "Voorspellingskwaliteit"
    override val gemVoorspelling    = "Gem. voorspelling"
    override val iobCorrectie       = "IOB-correctie"
    override val gemFoutPerTijdvenster = "Gemiddelde fout per tijdvenster"
    override val venster0_20        = "0\u201320 min"
    override val venster20_40       = "20\u201340 min"
    override val vensterBoven40     = ">40 min"
    override val floorCorrectieActief = "Floor-correctie actief in %d cycli (gem. +%.2f mmol) \u2014 compenseert"
    override val dalenTrendGeblokkeerd = "Dalende trend geblokkeerd"
    override val remActivaties      = "Rem-activaties"
    override val geenRemActivaties  = "Geen rem-activaties in deze episode"
    override val suppress           = "Suppress"
    override val lockout            = "Lockout"
    override val commitGeblokkeerd  = "Commit geblokkeerd"
    override val overigeEvents      = "Overige events"
    override val earlyReset         = "Early reset"
    override val downtrendLocked    = "Downtrend locked"
    override val sensorBlip         = "Sensor blip"

    // ── Advisor ───────────────────────────────────────────────────────────
    override val advisorAnalyse     = "Leer Automaat"
    override val advisorOverzicht   = "Analyse-overzicht"
    override val automaat           = "Dag"
    override val maxSmb             = "MaxSMB"
    override val parameters         = "Fijnafstelling"
    override val nachtNLabel        = "Nacht"
    override val isfLabel           = "ISF"
    override val analyse            = "Patroonanalyse"
    override val confidence         = "Confidence"
    override val gebruikt           = "Gebruikt"
    override val uitgesloten        = "Uitgesloten"
    override val avgPeak            = "Avg peak"
    override val avgInsuline        = "Avg insuline"
    override val avgDuur            = "Avg duur"
    override val hyper              = "Hyper"
    override val hypo               = "Hypo"
    override val actuelParams       = "Actuele parameters"
    override val watchingMinDelta   = "Watching min delta"
    override val peakPredThreshold  = "Piekdrempel"
    override val autoLeert          = "Automaat leert"
    override val tempo              = "Tempo"
    override val leertMee           = "Leert mee"

    // ── DFControl tab ─────────────────────────────────────────────────────
    override val dfControlToepassen     = "Toepassen in AAPS"
    override val dfControlHoeVroeg      = "hoe vroeg reageert het systeem"
    override val sterkteLower           = "sterkte"
    override val timingLower            = "timing"
    override val volhoudendheidLower    = "volhoudendheid"
    override val zeervroeg              = "Zeer vroeg"
    override val vroeg                  = "Vroeg"
    override val normaal                = "Normaal"
    override val laat                   = "Laat"
    override val zeerlaat               = "Zeer laat"
    override val aggressiviteitsKaart   = "Aggressiviteit"
    override val aggressiviteitsUitleg  = "Verschuift de geleerde S/T/V en frontload-timing \u2014 niveau 5 = geen effect"

    // ── MaxSMB tab ────────────────────────────────────────────────────────
    override val maxSmbGeschiedenisLabel = "Leergeschiedenis MaxSMB"
    override val maxSmbActiefLabel       = "Actieve maxSMB"
    override val maxSmbAutoLabel         = "Automaat"

    // ── Instellingen ──────────────────────────────────────────────────────
    override val settingsDosisBehavior          = "\uD83D\uDC89 Dosering & gedrag"
    override val settingsMaxBolusDay            = "Max bolus dag"
    override val settingsMaxBolusNight          = "Max bolus nacht"
    override val settingsMaxIob                 = "Max IOB"
    override val settingsInsulineverdeling      = "\uD83D\uDC89 Insulineverdeling"
    override val settingsNachtrespons           = "\uD83C\uDF19 Nachtrespons"
    override val settingsDagNacht               = "\u23F0 Dag / nacht context"
    override val settingsAutosens               = "\uD83D\uDEE1\uFE0F AutoSens & activiteit"
    override val settingsActiviteit             = "\uD83D\uDEB6 Activiteit"
    override val settingsWeekendDagen           = "Weekenddagen"
    override val settingsOchtendStart           = "Ochtendstart (doordeweeks)"
    override val settingsOchtendStartWeekend    = "Ochtendstart (weekend)"
    override val settingsNachtStart             = "Nachtstart"
    override val expertModus                    = "Expert modus"
    override val expertModusActief              = "Expertmodus actief."
    override val expertModusMeerOpties          = "Meer opties volgen in een toekomstige versie."
    override val expertT1BoostTitel              = "T1-versterking (experimenteel)"
    override val expertT1BoostUitleg             = "Geeft iets meer vroege insuline bij een maaltijd, maar alleen als de stijging al meerdere meetpunten aanhoudt. Grijpt nooit in op de allereerste, nog onzekere dosis. Standaard uit."
    override val expertPin                      = "Voer de pincode in (standaard: 0000)."
    override val expertPinFout                  = "Onjuiste pincode"
    override val pincode                        = "Pincode"
    override val openen                         = "Openen"
    override val annuleren                      = "Annuleren"
    override val sluiten                        = "Sluiten"

    override fun autosensGedragLabel(key: String) = when (key) {
        "OFF"        -> "Uitgeschakeld"
        "LIGHT"      -> "Licht"
        "NORMAL"     -> "Normaal (aanbevolen)"
        "STRONG"     -> "Sterk"
        "AGGRESSIVE" -> "Agressief"
        else         -> key
    }

    override fun autosensStabiliteitLabel(key: String) = when (key) {
        "VERY_STABLE" -> "Zeer stabiel"
        "STANDARD"    -> "Standaard"
        "RESPONSIVE"  -> "Responsief"
        else          -> key
    }

    override fun activiteitLabel(key: String) = when (key) {
        "OFF" -> "Uit"
        else  -> "Aan"
    }

    // ── Advisor: leertoelichting per as ─────────────────────────────────────
    override fun diagnoseTekst(diagnose: String) = when (diagnose) {
        "OK" ->
            "Resultaat binnen verwachting, geen aanpassing nodig"
        "HYPO" ->
            "Te lage BG na de maaltijd — dosis verlaagd"
        "TBT" ->
            "BG bleef lang onder streefdoel zonder echte hypo — dosis licht verlaagd"
        "HYPO_D_PROBLEEM" ->
            "Te lage BG ondanks normale opbouw — sterkte verlaagd"
        "HYPO_D_DEMPED" ->
            "Te lage BG, deels gedempt door afterload — sterkte licht verlaagd"
        "HYPO_FRONTLOAD" ->
            "Te lage BG na een te grote eerste dosis — sterkte en timing verlaagd"
        "TE_VROEG_SOLO" ->
            "Eerste dosis te groot zonder vervolgdoses — timing teruggenomen"
        "MEER_DOSIS" ->
            "Piek te hoog — sterkte verhoogd"
        "MEER_DOSIS_VROEG_ONDERSCHAT" ->
            "Piek te hoog door onderschatting vroeg in de maaltijd — sterkte verhoogd"
        "TE_WEINIG" ->
            "Piek te laag — sterkte verlaagd"
        "PIEK_LAAG_GEEN_BEWIJS" ->
            "Piek laag, maar geen hypo/reddingskoolhydraten — waarschijnlijk gewoon goed gedoseerd, geen aanpassing"
        "TIMING_SPREAD" ->
            "Piek te hoog en dosis te laat verdeeld — timing naar voren bijgesteld"
        "TIMING_SPREAD_VROEG" ->
            "Piek te hoog ondanks vroege start — timing verder naar voren bijgesteld"
        "FRONTLOAD_LAG" ->
            "Eerste dosis kwam te laat op gang — timing naar voren bijgesteld"
        "FRONTLOAD_LAG_VROEG" ->
            "Eerste dosis kwam ondanks vroege start te laat op gang — timing verder naar voren bijgesteld"
        "IOB_SPREAD_TE_LAAT" ->
            "Opgebouwde insuline werkte te laat door — timing bijgesteld"
        "IOB_SPREAD_TE_LAAT_VERSPREID" ->
            "Insuline gelijkmatig verspreid maar had meer naar voren gemoeten — timing bijgesteld"
        "HYPO_LATE_TIMING" ->
            "Te lage BG door te late insuline, niet door te veel — timing naar voren bijgesteld"
        "RESCUE_OVERPOWERED" ->
            "Reddingsdosis nodig ondanks normale opbouw — sterkte verlaagd"
        "EARLYBOOST_TE_KLEIN" ->
            "Vroege boost was te klein om de piek te dempen — frontload verhoogd"
        "AFTERLOAD_GUARD_OK" ->
            "Afterload-grens werkte zoals bedoeld, geen aanpassing"
        "AUTO_DISABLED" ->
            "Automaat staat uit — geen aanpassingen toegepast"
        "COOLDOWN" ->
            "Recent al aangepast — wacht op afkoelperiode"
        "GEEN_EPISODE", "SKIP_GEEN_EPISODE" ->
            "Geen geschikte episode gevonden om van te leren"
        else -> diagnose
    }

    override fun timingDiagnoseTekst(diagnose: String) = when (diagnose) {
        // Diagnoses die F aanpassen — timing-gerichte omschrijving
        "HYPO_FRONTLOAD" ->
            "Timing teruggenomen — eerste dosis was te groot voor de maaltijdsnelheid"
        "TE_VROEG_SOLO"  ->
            "Timing teruggenomen — eerste commit te dominant zonder vervolgdoses"
        "TIMING_SPREAD", "TIMING_SPREAD_VROEG" ->
            "Timing naar voren bijgesteld — insuline werd te laat verdeeld"
        "FRONTLOAD_LAG", "FRONTLOAD_LAG_VROEG" ->
            "Timing naar voren bijgesteld — eerste grote dosis kwam te laat op gang"
        "IOB_SPREAD_TE_LAAT" ->
            "Timing naar voren bijgesteld — opgebouwde insuline werkte te laat door"
        "MEER_DOSIS_VROEG_ONDERSCHAT" ->
            "Timing naar voren bijgesteld — systeem onderschatte de stijging vroeg in de maaltijd"
        else -> ""  // Geen timing-aanpassing bij deze diagnose
    }

    override fun frontloadTekst(richting: String, gemiddeldeMargeMin: Int) = when (richting) {
        "EERDER" -> when {
            gemiddeldeMargeMin < 0 ->
                // piek trad vóór of vlak na de frontload op → trigger eerder
                "Piek viel gemiddeld ${-gemiddeldeMargeMin} min te vroeg — frontload eerder ingesteld"
            else ->
                // piek trad laat op → frontload had nog eerder gemoeten
                "Piek viel gemiddeld $gemiddeldeMargeMin min na frontload — frontload eerder ingesteld"
        }
        // "LATER" wordt niet meer toegewezen door de learner — alleen nog
        // aanwezig voor terugwaartse compatibiliteit met opgeslagen history.
        "LATER" ->
            "Piek viel gemiddeld ${-gemiddeldeMargeMin} min te vroeg (historisch opgeslagen — logica herzien)"
        "GOED" ->
            "Piektiming binnen marge, geen aanpassing nodig"
        else -> richting
    }

    override fun vSignaalTekst(signal: String) = when (signal) {
        "FORWARD" ->
            "Persistente correctie hielp niet genoeg — vasthoudendheid verhoogd"
        "BACK" ->
            "Persistente correctie werkte te sterk door — vasthoudendheid verlaagd"
        "NONE" ->
            "Persistente correcties werken naar verwachting"
        else -> signal
    }

    override fun frontloadStatusTekst(key: String) = when (key) {
        "STANDAARD"    -> "standaard"
        "LICHT_VOOR"   -> "licht naar voren"
        "STERK_VOOR"   -> "sterk naar voren"
        "LICHT_TERUG"  -> "licht teruggenomen"
        "STERK_TERUG"  -> "sterk teruggenomen"
        else           -> key
    }

    override fun relatieveToelichting(deltaPct: Int) = when {
        deltaPct == 0 -> "Op standaardniveau"
        deltaPct > 0  -> "$deltaPct% hoger dan standaard"
        else          -> "${-deltaPct}% lager dan standaard"
    }

    // ── Dag/nacht labels ──────────────────────────────────────────────────
    override val sterkteLabelLaag       = "Laag"
    override val sterkteLabelNormaal    = "Normaal"
    override val sterkteLabelHoog       = "Hoog"
    override val timingLabelTeVroeg     = "Veel te vroeg"
    override val timingLabelVroeg       = "Sterk vroeg"
    override val timingLabelNormaal     = "Normaal"
    override val timingLabelLaat        = "Laat"
    override val volhoudendheidLabel    = "Iets voorzichtiger"
    // StatusFormatter inline labels
    override val frontloadSterkVroeg       = "Sterk vroeg"
    override val frontloadVroeg            = "Vroeg"
    override val frontloadNeutraal         = "Neutraal"
    override val frontloadLaat             = "Laat"
    override val frontloadSterkLaat        = "Sterk laat"
    override val sterkteHoog               = "Hoog"
    override val sterkteIetsBoven          = "Iets boven standaard"
    override val sterkteStandaard          = "Standaard"
    override val sterkteIetsOnder          = "Iets onder standaard"
    override val sterkteLaagLabel          = "Laag"
    override val volhVasthoudend           = "Vasthoudend"
    override val volhIetsVasthoudender     = "Iets vasthoudender"
    override val volhStandaard             = "Standaard"
    override val volhIetsVoorzichtiger     = "Iets voorzichtiger"
    override val volhVoorzichtig           = "Voorzichtig"
    override val nachtRustigerDanDag       = "%d%% rustiger dan dag"
    override val analyzerConfigHeader      = "ANALYZER-GESTUURDE WAARDEN (config)"
    override val analyzerFijnafstemming    = "FIJNAFSTEMMING"
    override val maxSmbDagLabel            = "MaxSMB dag"
    override val maxSmbVerlaagd            = "verlaagd"
    override val maxSmbVerhoogd            = "verhoogd"
    override val iobRemdrempel             = "IOB-remdrempel"
    override val geenActiviteitdata        = "Geen activiteitdata"
    override val geenResistentieLog        = "Geen resistentie-log"
    override val nogGeenData               = "Nog geen data"
    override val autoSensHeader            = "AUTO-SENS"
    override val configNogNietBeschikbaar  = "Config nog niet beschikbaar (wacht op eerste FCLvNext cyclus)"
    override val expertModeAan             = "Expert modus aan"
    override val expertModeUit             = "Expert modus uit"
    override val fijnafstemming            = "Fijnafstemming"
    override val autosensOverdagUit        = "AutoSens: ☀️ Overdag uitgeschakeld (factor = 1.0)"
    override val autosensUit               = "AutoSens: ❌ UIT"

}