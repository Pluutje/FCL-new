package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Instant

data class EpisodeMetrics(
    val id: Int,

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
    val earlyBoostFrac: Double = 0.0
)