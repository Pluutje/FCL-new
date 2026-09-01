package app.aaps.plugins.aps.openAPSFCL.vnext.lang

/**
 * FclStrings — centrale interface voor alle UI-teksten in FCL vNext.
 *
 * Elke taal implementeert deze interface in een eigen bestand:
 *   FclStrings_NL.kt  — Nederlands
 *   FclStrings_EN.kt  — Engels
 *   FclStrings_DE.kt  — Duits (toekomstig)
 *
 * Gebruik in code:
 *   val s = FclStrings.get()          // automatische systeemtaal
 *   val s = FclStrings.get(FclLocale.EN)  // geforceerd Engels
 *
 * Nieuwe string toevoegen:
 *   1. Voeg een val toe aan deze interface
 *   2. Implementeer in FclStrings_NL.kt en FclStrings_EN.kt
 *   3. Kotlin-compiler signaleert ontbrekende implementaties
 */
interface FclStrings {

    // ── Algemeen ──────────────────────────────────────────────────────────
    val appName: String
    val statusTabLabel: String
    val analyzerTabLabel: String
    val settingsTabLabel: String
    val close: String
    val save: String
    val cancel: String
    val change: String
    val yes: String
    val no: String
    val unknown: String
    val none: String
    val auto: String
    val units: String       // "U"
    val mmolL: String
    val mgdl: String
    val per5min: String
    val perHour: String
    val days: String
    val minutes: String
    val times: String       // "×"

    // ── Status formatter ──────────────────────────────────────────────────
    val situatieOverdag: String
    val situatieNacht: String
    val glucose: String
    val iob: String
    val fclPiek: String
    val fclPiekGeen: String
    val fclPiekSterkeStijging: String
    val fclPiekWatching: String
    val verwacht: String
    val beslissing: String
    val geenIngreep: String
    val latsteDosisFcl: String
    val latsteDosisFclEmpty: String
    val algoritmeparameters: String
    val sterkte: String
    val timing: String
    val volhoudendheid: String
    val frontloadTimingTitel: String

    /** Volledige asnamen zonder (S)/(T)/(V)-afkorting, voor de Advisor-kaarten. */
    val sterkteTitel: String
    val timingTitel: String
    val vasthoudendheidTitel: String
    val nachtN: String
    val nachtRespons: String
    val insulineverdeling: String
    val activiteit: String
    val stepsThreshold: String

    // NightStyle labels
    fun nightStyleLabel(key: String): String
    // DoseStyle labels
    fun doseStyleLabel(key: String): String

    // ── Analyzer — algemeen ───────────────────────────────────────────────
    val episodeViewer: String
    val terug: String
    val live: String
    val geenEpisodesGevonden: String
    val gegevens: String
    val gegevensInfo: String    // "N cycli • data direct uit AAPS database"
    val vernieuwen: String
    val episodes: String
    val advisor: String
    val glucoseBereiken: String
    val laatseSyncLabel: String // "Laatste sync:"
    val tbr: String
    val tbt: String
    val tir: String
    val tar: String
    val reddingskoolhydraten: String
    val daadwerkelijkGenomen: String

    // ── Analyzer — episode overzicht ──────────────────────────────────────
    val overzicht: String
    val piekAnalyse: String
    val marges: String
    val samenvatting: String
    val insulineLabel: String
    val iobAtPiek: String
    val peakPlusMin: String
    val werkelijkePiek: String
    val predPiek: String
    val deltaVoorspelling: String
    val gewicht: String
    val meegeteldInAanbeveling: String
    val verbruiktNaWijziging: String
    val behoortBijAanbeveling: String
    val beschikbaarVoorAdvies: String
    val glucoseBleefStabiel: String
    val episodeClassLabel: String
    fun episodeTypeLabel(key: String): String

    // ── Analyzer — piekanalyse ────────────────────────────────────────────
    val piekVoorspellingVsWerkelijkheid: String
    val xAsMinutenNaStart: String
    val yAsBg: String
    val geleKnip: String        // "Gele lijn = werkelijke piek"
    val vroegeFout: String
    val foutBijPiek: String
    val werkelijkePiekLabel: String
    val werkelijkBg: String
    val predIobCorrect: String
    val ballistisch: String

    // ── Analyzer — marges ─────────────────────────────────────────────────
    val margesTotDrempels: String
    val margesUitleg: String
    val iobMargeLabel: String   // "IOB-marge tot rem"
    val predMargeWatching: String
    val predMargeDoel: String
    val voorspellingsKwaliteit: String
    val gemVoorspelling: String
    val iobCorrectie: String
    val gemFoutPerTijdvenster: String
    val venster0_20: String
    val venster20_40: String
    val vensterBoven40: String
    val floorCorrectieActief: String // "Floor-correctie actief in N cycli (gem. +X mmol)"
    val dalenTrendGeblokkeerd: String
    val remActivaties: String
    val geenRemActivaties: String
    val suppress: String
    val lockout: String
    val commitGeblokkeerd: String
    val overigeEvents: String
    val earlyReset: String
    val downtrendLocked: String
    val sensorBlip: String

    // ── Advisor ───────────────────────────────────────────────────────────
    val advisorAnalyse: String
    val advisorOverzicht: String
    val automaat: String
    val maxSmb: String
    val parameters: String
    val nachtNLabel: String
    // Eigen ISF-tabblad (19/08/2026) — voorheen stond de ISF-kaart op het
    // Dag-tabblad; ISF-data bouwt zo langzaam op dat dit de rest van Dag
    // (Agressiviteit e.d.) onnodig naar onderen duwde. Zelfde plek in de
    // volgorde als Dag/Nacht, geen submenu van iets anders.
    val isfLabel: String
    val analyse: String
    val confidence: String
    val gebruikt: String
    val uitgesloten: String
    val avgPeak: String
    val avgInsuline: String
    val avgDuur: String
    val hyper: String
    val hypo: String
    val actuelParams: String
    val watchingMinDelta: String
    val peakPredThreshold: String
    val autoLeert: String
    val tempo: String
    val leertMee: String

    // ── DFControl tab ─────────────────────────────────────────────────────
    val dfControlToepassen: String
    val dfControlHoeVroeg: String
    val sterkteLower: String     // "sterkte" (lowercase)
    val timingLower: String
    val volhoudendheidLower: String
    val zeervroeg: String
    val vroeg: String
    val normaal: String
    val laat: String
    val zeerlaat: String
    val aggressiviteitsKaart: String
    val aggressiviteitsUitleg: String

    // ── MaxSMB tab ────────────────────────────────────────────────────────
    val maxSmbGeschiedenisLabel: String
    val maxSmbActiefLabel: String
    val maxSmbAutoLabel: String

    // ── Instellingen ──────────────────────────────────────────────────────
    val settingsDosisBehavior: String
    val settingsMaxBolusDay: String
    val settingsMaxBolusNight: String
    val settingsMaxIob: String
    val settingsInsulineverdeling: String
    val settingsNachtrespons: String
    val settingsDagNacht: String
    val settingsAutosens: String
    val settingsActiviteit: String
    val settingsWeekendDagen: String
    val settingsOchtendStart: String
    val settingsOchtendStartWeekend: String
    val settingsNachtStart: String
    val expertModus: String
    val expertModusActief: String
    val expertModusMeerOpties: String
    val expertT1BoostTitel: String
    val expertT1BoostUitleg: String
    val expertPeakStartTitel: String
    val expertPeakStartUitleg: String
    val expertPeakStartWaarde: String
    val expertPin: String
    val expertPinFout: String
    val pincode: String
    val openen: String
    val annuleren: String
    val sluiten: String

    // AutoSens opties
    fun autosensGedragLabel(key: String): String
    fun autosensStabiliteitLabel(key: String): String
    fun activiteitLabel(key: String): String

    // ── Advisor: leertoelichting per as ─────────────────────────────────────
    // Vertaalt interne diagnose-/signaalcodes van de learners naar korte
    // toelichtingszinnen voor de Advisor-kaarten (Sterkte/Timing/
    // Vasthoudendheid/Frontload-timing). Bron van de codes:
    //   - DFLearner.LearningStep.diagnose      → diagnoseTekst()
    //   - FrontloadLearner.FrontloadLearningStep.richting → frontloadTekst()
    //   - VLearner.VHistoryPoint.signal        → vSignaalTekst()
    // Onbekende codes vallen terug op de ruwe code (else -> key), zodat een
    // ontbrekende vertaling zichtbaar is i.p.v. stil te verdwijnen.
    fun diagnoseTekst(diagnose: String): String

    /**
     * Timing-gerichte toelichting bij een episode-diagnose, voor onder
     * de Timing-kaart. Toont alleen als er daadwerkelijk een F-aanpassing was.
     * Beschrijft WAT er met de timing is gedaan, niet de D-aanpassing.
     */
    fun timingDiagnoseTekst(diagnose: String): String
    fun frontloadTekst(richting: String, gemiddeldeMargeMin: Int): String
    fun vSignaalTekst(signal: String): String

    /**
     * Korte statusfrase voor de Frontload-timing kaart, op basis van hoe
     * ver de huidige WMD afwijkt van de standaardwaarde.
     * key ∈ {"STANDAARD","LICHT_VOOR","STERK_VOOR","LICHT_TERUG","STERK_TERUG"}
     */
    fun frontloadStatusTekst(key: String): String

    /**
     * Relatieve toelichting t.o.v. standaard (100%) voor de Sterkte/Timing/
     * Vasthoudendheid-kaarten: "17% lager dan standaard" / "op standaardniveau".
     */
    fun relatieveToelichting(deltaPct: Int): String

    // ── Dag/nacht labels ──────────────────────────────────────────────────
    // ── StatusFormatter inline labels ────────────────
    val frontloadSterkVroeg: String
    val frontloadVroeg: String
    val frontloadNeutraal: String
    val frontloadLaat: String
    val frontloadSterkLaat: String
    val sterkteHoog: String
    val sterkteIetsBoven: String
    val sterkteStandaard: String
    val sterkteIetsOnder: String
    val sterkteLaagLabel: String
    val volhVasthoudend: String
    val volhIetsVasthoudender: String
    val volhStandaard: String
    val volhIetsVoorzichtiger: String
    val volhVoorzichtig: String
    val nachtRustigerDanDag: String
    val analyzerConfigHeader: String
    val analyzerFijnafstemming: String
    val maxSmbDagLabel: String
    val maxSmbVerlaagd: String
    val maxSmbVerhoogd: String
    val iobRemdrempel: String
    val geenActiviteitdata: String
    val geenResistentieLog: String
    val nogGeenData: String
    val autoSensHeader: String
    val configNogNietBeschikbaar: String
    val expertModeAan: String
    val expertModeUit: String
    val fijnafstemming: String
    val autosensOverdagUit: String
    val autosensUit: String

    val sterkteLabelLaag: String
    val sterkteLabelNormaal: String
    val sterkteLabelHoog: String
    val timingLabelTeVroeg: String
    val timingLabelVroeg: String
    val timingLabelNormaal: String
    val timingLabelLaat: String
    val volhoudendheidLabel: String

    // ── Companion ─────────────────────────────────────────────────────────
    companion object {
        private const val PREFS_NAME = "fcl_vnext_lang"
        private const val KEY_LOCALE  = "locale_override"

        /**
         * Sla een taalvoorkeur op in SharedPreferences.
         * null = gebruik systeemtaal (standaard).
         */
        fun saveLocale(ctx: android.content.Context, locale: FclLocale?) {
            ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (locale == null) remove(KEY_LOCALE) else putString(KEY_LOCALE, locale.code)
                    apply()
                }
        }

        /**
         * Lees de opgeslagen taalvoorkeur.
         * Terugvaloptie: systeemtaal detectie.
         */
        fun loadLocale(ctx: android.content.Context): FclLocale {
            val code = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LOCALE, null)
            return if (code != null) FclLocale.fromCode(code) else FclLocale.fromSystem()
        }

        /**
         * Snelle toegang zonder context (gebruikt bij het opstarten
         * voor de context beschikbaar is). Na app-start altijd
         * get(context) gebruiken.
         */
        fun get(locale: FclLocale = FclLocale.fromSystem()): FclStrings = forLocale(locale)

        /** Primaire aanroep — leest de opgeslagen taalvoorkeur. */
        fun get(ctx: android.content.Context): FclStrings =
            forLocale(loadLocale(ctx))

        /** Vertaaltabel per taal. Hier een nieuwe taal registreren. */
        fun forLocale(locale: FclLocale): FclStrings = when (locale) {
            FclLocale.NL -> FclStrings_NL
            FclLocale.EN -> FclStrings_EN
            FclLocale.DE -> FclStrings_DE
        }
    }
}