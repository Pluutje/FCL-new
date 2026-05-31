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

    // ── MaxSmbLearner signaalvelden ──────────────────────────────────────
    // brakeActiveCycles: aantal cycli waarbij iobRatio >= 0.70 (rem actief).
    // Hoge waarde = systeem wilde meer doseren maar werd geblokkeerd door maxSMB.
    val brakeActiveCycles: Int = 0,

    // earlyBoostWasActive: was earlyBoostFactor > 1.0 actief in deze episode?
    // MaxSmbLearner verhoogt maxSMB alleen als dit true is — anders is het
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
    val firstFrontloadMinutes: Int = -1
)