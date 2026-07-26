package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

data class EpisodeMetrics(
    val id: Int,

    // 26/07/2026 (Ecko) — dag/nacht-splitsing Learner/AI-adviseur. Bepaald
    // door EpisodeMetricsBuilder uit de eerste rij van de episode (een
    // episode wisselt zelden binnen zijn eigen duur van dag naar nacht of
    // omgekeerd; de EERSTE rij is bepalend voor "welk regime hoorde hierbij").
    val isNight: Boolean = false,

    val start: Instant,
    val end: Instant,
    val postWindowEnd: Instant,

    val durationMinutes: Long,
    val startBg: Double,
    val avgBg: Double,
    val endBg: Double,

    val peakBg: Double,
    val riseMagnitude: Double,
    val timeToPeakMinutes: Long?,

    val minBgCore: Double,
    val minBgInWindow: Double,

    val hypoDetected: Boolean,
    val hypoMinutes: Long,

    val tbtDetected: Boolean,
    val tbtMinutes: Long,

    val maxIobRatio: Double,

    // FIX 3: iobRatioAtPeak toegevoegd als expliciet veld zodat EpisodeMetricsBuilder
    // en FclPatternAdvisor het kunnen gebruiken voor kwaliteitsweging
    val iobRatioAtPeak: Double,

    val totalInsulinDelivered: Double,   // FCLvNext eigen doses
    val totalInsulinIncExternal: Double, // incl. handmatige bolussen en AAPS SMBs
    val externalBolusTotal: Double,      // som van alle externe bolussen in episode
    val hasManualCorrection: Boolean,    // True als handmatige bolus >= 0.5U tijdens episode
    val advisorWeight: Double,
    val includedInAdvice: Boolean,
    val adviceStatus: String,
    val rescueConfirmed: Boolean = false,  // gebruiker bevestigde rescue carbs

    // ── Frontload kwaliteit ──────────────────────────────────────────────
    // firstCommitU: dosis van de eerste commit (episodeCommitNr=1).
    val firstCommitU: Double = 0.0,

    // iobRatioAt15min: IOB-ratio op t+15min na episode-start.
    val iobRatioAt15min: Double = 0.0,

    // firstBigCommitFrac: grootste commit / totaal.
    // Hoge waarde (≥0.45) = goede frontload: één dominante vroege bolus.
    // Lage waarde (<0.35) = insuline verspreid over veel kleine commits.
    val firstBigCommitFrac: Double = 0.0,

    // followUpCommitCount: aantal commits NA de grootste commit.
    // ≥2 = er volgde insuline na de eerste grote bolus → veilig bewijs
    //       dat die eerste niet te groot was (anders zou BG al gedaald zijn).
    // 0  = alleen één bolus → onzeker of die te groot was.
    val followUpCommitCount: Int = 0,

    // ── Staart-analyse: hoe groot was de laatste significante commit ────────
    // lastSignificantCommitFrac: de laatste commit die groter was dan
    // SIGNIFICANT_COMMIT_THRESHOLD (0.3U EN > 15% van totaal) als fractie van
    // totale insuline. Hoog (> 0.25) betekent dat er veel insuline in de
    // "staart" zat die eigenlijk eerder had kunnen worden gegeven.
    val lastSignificantCommitFrac: Double = 0.0,

    // lastSignificantCommitMinutesBeforePeak: hoeveel minuten VOOR de piek
    // de laatste significante commit viel. Negatief = NA de piek (probleem).
    // null = geen significante commit gevonden of piek onbekend.
    val lastSignificantCommitMinutesBeforePeak: Int? = null,

    // ── Dosisanalyse velden ──────────────────────────────────────────────
    // brakeActiveCycles: aantal cycli waarbij iobRatio >= 0.70 (rem actief).
    // Hoge waarde = systeem wilde meer doseren maar werd geblokkeerd door maxSMB.
    val brakeActiveCycles: Int = 0,

    // earlyBoostWasActive: was earlyBoostFactor > 1.0 actief in deze episode?
    // Indicator dat de maxSMB cap actief was in deze cyclus
    // onduidelijk of maxSMB de limiterende factor was.
    val earlyBoostWasActive: Boolean = false,

    // Gemiddelde voorspellingsfout per tijdvenster (pred - werkelijkePiek)
    // Positief = overschatting, negatief = onderschatting
    // null = geen data in dit venster
    val predFout0_20:  Double? = null,   // 0-20 min: frontload fase
    val predFout20_40: Double? = null,   // 20-40 min: watching fase

    // capReachedCycles: aantal cycli waarbij de finale dosis gecapped was door maxSMB.
    // Direct signaal dat maxSMB structureel te laag is — systeem wilde meer maar kon niet.
    val capReachedCycles: Int = 0,

    // currentSterkte: S-waarde (%) actief tijdens deze episode.
    // Nodig om te detecteren of S al op zijn minimum staat.
    val currentSterkte: Int = 100,

    // ── Frontload timing ─────────────────────────────────────────────────
    // firstFrontloadMinutes: minuten na episodestart van de EERSTE frontload-trigger.
    // -1 = geen frontload getriggerd in deze episode.
    val firstFrontloadMinutes: Int = -1,
    // True als afterload guard (fd60 of highIob) actief was tijdens deze episode.
    // Gebruikt door DFLearner: als episode goed afliep MET afterload, remmen we
    // verdere D-daling — het guard deed het werk, niet een te lage D.
    val afterloadWasActive: Boolean = false,

    // ── EarlyBoost verdeling ─────────────────────────────────────────────
    // earlyBoostDeliveredU: totale insuline in earlyBoost stages (1+2+3).
    // earlyBoostFrac: earlyBoostDeliveredU / totalInsulinDelivered.
    // Budget-neutraal leren: frac te laag → earlyBoostFactor omhoog,
    // watchingFrontloadFrac evenredig omlaag zodat totaal gelijk blijft.
    val earlyBoostDeliveredU: Double = 0.0,
    val earlyBoostFrac: Double = 0.0,

    // ── Automatische near-hypo-detectie (20/06/2026) ─────────────────────
    // True wanneer de BG-kinetiek zelf (dieptepunt + substantiële IOB +
    // duidelijke rebound erna) er sterk op wijst dat een hypo alleen is
    // uitgebleven door koolhydraten (rescue of gewoon een vervolgmaaltijd) —
    // automatisch berekend uit de cyclusdata, NIET afhankelijk van de
    // handmatige "Ja/Nee"-bevestiging in de Episode Viewer (die was
    // oorspronkelijk bedoeld om de detector-drempels te kalibreren, niet als
    // vereiste bewijslast — zie EpisodeMetricsBuilder voor de exacte criteria).
    // In tegenstelling tot de rescue-ARM/CONFIRM-detector in FCLvNext.kt
    // (real-time gebruikerswaarschuwing) is hier GEEN eis dat er geen
    // insuline gelijktijdig werd afgegeven — insuline kan een BG-stijging
    // toch niet verklaren, dus die eis zou hier het probleem juist
    // uitsluiten in plaats van bevestigen.
    val nearHypoAverted: Boolean = false,

    // ── Geprojecteerde ernstige daling, afgewend zonder harde hypo ────────
    // (19/07/2026, Ecko) AANLEIDING: nearHypoAverted hierboven vereist dat de
    // WERKELIJK GEMETEN BG ooit onder NEAR_HYPO_THRESH (4,8) kwam. Dat mist
    // precies de gevallen waar de dosering te vroeg te veel gaf, maar de BG
    // dankzij een tijdige interventie (of gewoon de eigen fysiologie) nooit
    // echt laag heeft gestaan — terwijl de PROJECTIE (dezelfde hypoProjectedBg
    // die FCLvNext.kt elke cyclus al berekent op basis van IOB/ISF/BG) op dat
    // moment wel degelijk ruim onder de 4 wees. Bewijs hiervoor: die projectie
    // was op enig moment ernstig laag, EN later — rond target, met nog steeds
    // significante IOB aanwezig — vlakte de BG af of steeg zelfs weer. Die
    // combinatie kan insuline zelf niet verklaren (insuline kan een stijging
    // nooit veroorzaken) — sterke aanwijzing dat er vóór de piek te veel is
    // gegeven. Zie EpisodeMetricsBuilder voor de exacte criteria.
    val projectedSevereLowAverted: Boolean = false,

    // ── Veiligheidscontrole: late/te grote afsluitende commit ────────────
    // (13/07/2026, Ecko) Herbruikt SafetyInvariantChecker (Analyzer-UI,
    // 12/07/2026) als extra, complementair leersignaal voor FrontloadLearner.
    // firstBigCommitFrac zegt iets over de EERSTE commit; dit zegt iets over
    // de LAATSTE — een episode kan een prima fbc hebben en alsnog een te
    // grote, te late afsluitende commit (zie 12/07 15:12 UTC: eerste commit
    // was niet klein, de opbouw daarna piekte alsnog te laat/te groot).
    val hasLateCommitViolation: Boolean = false,
    // grootste schending als fractie van de grootste commit die episode
    // (episodePeakCommitU) — puur informatief/voor logging, geen leerinput.
    val lateCommitViolationFraction: Double = 0.0
)