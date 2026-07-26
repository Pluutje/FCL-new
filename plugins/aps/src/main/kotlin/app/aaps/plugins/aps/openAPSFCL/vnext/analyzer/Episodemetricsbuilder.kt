package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration

object EpisodeMetricsBuilder {

    // Drempels voor de automatische near-hypo-detectie — bewust gelijk aan
    // de bestaande rescue-ARM/CONFIRM-constanten in FCLvNext.kt, zodat
    // beide mechanismen consistent dezelfde situatie als "verdacht" zien.
    private const val NEAR_HYPO_THRESH        = 4.8   // mmol/L — waarschuwingsdrempel, boven de harde hypo-grens (3.9)
    // Projectie-drempel voor projectedSevereLowAverted (19/07/2026, Ecko) —
    // "ruim onder de 4": lager dan NEAR_HYPO_THRESH, want dit is een
    // PROJECTIE (hypoProjectedBg), geen gemeten waarde — een projectie mag
    // een grotere marge onder de harde hypo-grens hebben voor het als
    // betekenisvol bewijs telt.
    private const val SEVERE_PROJECTION_THRESH = 3.5   // mmol/L
    // "Rond target" voor de afvlak/rebound-check hieronder.
    private const val NEAR_TARGET_BAND         = 1.2   // mmol/L

    // Gedeelde maat voor "is dit een betekenisvolle hoeveelheid insuline voor
    // déze patiënt" — RELATIEF aan maxSMB, niet absoluut (zie controlevraag
    // Ecko 20/06/2026: bij 3× zo'n grote maxSMB is een vast getal in U niet
    // meer betekenisvol — wat voor de ene patiënt "significant" is, is voor
    // een ander ruis, en omgekeerd). Hergebruikt voor zowel "nog actieve IOB
    // op het dieptepunt" (NEAR_HYPO) als "was deze commit significant"
    // (significantThresholdU) hieronder — beide stellen feitelijk dezelfde
    // vraag. .coerceAtLeast() is puur een veiligheidsvloer voor het
    // randgeval maxSMB≈0 (bv. bij init), niet een herintroductie van een
    // vast getal als norm.
    private const val SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB = 0.12
    // Iets ruimer dan SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB: een vervolgcommit telt
    // al mee als die "niet verwaarloosbaar" is, een lagere lat dan "significant".
    private const val FOLLOWUP_COMMIT_FRAC_OF_MAXSMB  = 0.04

    // Let op: bewust RUWE IOB (U), niet iobRatio (= IOB/maxIOB, genormaliseerd
    // tegen de Max-IOB-veiligheidsgrens — bv. 0,5U/10,5U=0,05, wat een
    // "klein" getal lijkt terwijl 0,5U nog steeds actief werkende insuline
    // is).
    private const val REBOUND_ACCEL_MIN       = 0.18
    private const val REBOUND_SLOPE_MIN       = 0.25



    fun build(
        episodes: List<Episode>,
        manualMaxSmb: Double = 1.25   // fallback als bridge niet beschikbaar
    ): List<EpisodeMetrics> {
        val newestStart = episodes.maxOfOrNull { it.start.epochSecond }
            ?.let { java.time.Instant.ofEpochSecond(it) }
            ?: java.time.Instant.EPOCH

        return episodes.map { episode ->
            val rows = episode.rows
            val duration = Duration.between(episode.start, episode.end).toMinutes()

            val peakRow = rows.maxByOrNull { it.bg }
            val peakBg = peakRow?.bg ?: episode.startBg
            val maxIobRatio = rows.maxOfOrNull { it.iobRatio } ?: 0.0
            val iobAtPeak = peakRow?.iobRatio ?: 0.0

            val avgBg = if (rows.isNotEmpty()) rows.map { it.bg }.average() else episode.startBg
            val endBg = rows.lastOrNull()?.bg ?: episode.startBg

            // (13/07/2026, Ecko) Hergebruik van de Veiligheidscontrole
            // (SafetyInvariantChecker, Analyzer-UI 12/07/2026) als extra
            // leersignaal voor FrontloadLearner — zie kdoc bij
            // hasLateCommitViolation in EpisodeMetrics.kt.
            val safetyCheck = SafetyInvariantChecker.check(episode)

            // FCLvNext eigen doses
            val totalInsulinDelivered = rows.sumOf { it.deliveredTotal }
            // Externe bolussen (handmatig of AAPS SMB) per cyclus gesommeerd
            val externalBolusTotal = rows.sumOf { it.externalBolusU }
            // Totaal inclusief externe bolussen = werkelijke insulinebelasting
            val totalInsulinIncExternal = totalInsulinDelivered + externalBolusTotal
            // Markeer episode als handmatig gecorrigeerd (>= 0.5U extern)
            val hasManualCorrection = externalBolusTotal >= 0.5
            val ageDays = Duration.between(episode.start, newestStart).toDays().coerceAtLeast(0)

            // Frontload kwaliteit
            val firstCommitRow = rows
                .filter { it.episodeCommitNr == 1 }
                .maxByOrNull { it.deliveredTotal }
            val firstCommitU = firstCommitRow?.deliveredTotal ?: 0.0

            // iobRatio op t+15min
            val iobRatioAt15min = rows
                .filter { it.minutesSinceMealStart != null }
                .minByOrNull { kotlin.math.abs((it.minutesSinceMealStart ?: 999) - 15) }
                ?.iobRatio ?: 0.0

            // firstBigCommitFrac: grootste afzonderlijke commit / totaal
            // Gebruik max delivered per tijdstip (niet per commitNr, want
            // kleine correcties voor de echte bolus tellen mee in commitNr)
            val biggestSingleCommit = rows.maxOfOrNull { it.deliveredTotal } ?: 0.0
            val firstBigCommitFrac = if (totalInsulinDelivered > 0.1)
                biggestSingleCommit / totalInsulinDelivered else 0.0

            // followUpCommitCount: commits die LATER komen dan de grootste
            // commit, en zelf nog substantieel waren (RELATIEF aan maxSMB —
            // was voorheen een vaste 0,10U, zie controlevraag Ecko 20/06/2026:
            // bij een patiënt met 3× zo'n grote maxSMB zou 0,10U verwaarloosbaar
            // ruis zijn i.p.v. een echte vervolgcommit).
            val followUpThresholdU = (FOLLOWUP_COMMIT_FRAC_OF_MAXSMB * manualMaxSmb).coerceAtLeast(0.03)
            val bigCommitTs = rows.maxByOrNull { it.deliveredTotal }?.timestamp
            val followUpCommitCount = if (bigCommitTs != null)
                rows.count { it.deliveredTotal > followUpThresholdU && it.timestamp > bigCommitTs }
            else 0

            // ── Staart-analyse ────────────────────────────────────────────────
            // Drempel: een commit is "significant" als hij zowel substantieel is
            // t.o.v. maxSMB (RELATIEF — was voorheen een vaste 0,30U) ALS
            // > 15% van de totale insuline in de episode beslaat. Dit filtert
            // de kleine correctiedoses eruit maar houdt echte bolussen over.
            // Zelfde fractie (12%) als NEAR_HYPO_MIN_IOB_FRAC_OF_MAXSMB
            // hierboven — beide vragen feitelijk "is dit een betekenisvolle
            // hoeveelheid insuline voor déze patiënt", dus bewust dezelfde maat.
            val significantThresholdU = (SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB * manualMaxSmb).coerceAtLeast(0.15)
            val significantThresholdFrac = 0.15
            val significantCommits = rows
                .filter { row ->
                    row.deliveredTotal > significantThresholdU &&
                        (totalInsulinDelivered <= 0.1 || row.deliveredTotal / totalInsulinDelivered > significantThresholdFrac)
                }
                .sortedBy { it.timestamp }

            // Laatste significante commit (gesorteerd op tijd)
            val lastSigCommit = significantCommits.lastOrNull()
            val lastSignificantCommitFrac = if (lastSigCommit != null && totalInsulinDelivered > 0.1)
                lastSigCommit.deliveredTotal / totalInsulinDelivered else 0.0

            // Hoe ver voor (of na) de piek viel die laatste significante commit?
            // Negatief = NA de piek — dit is het kritieke geval dat we willen herkennen.
            val lastSignificantCommitMinutesBeforePeak: Int? = if (lastSigCommit != null && peakRow != null) {
                Duration.between(lastSigCommit.timestamp, peakRow.timestamp).toMinutes().toInt()
            } else null

            // ── Dosisanalyse velden ──────────────────────────────────────
            // brakeActiveCycles: cycli waarbij iobRatio >= 0.70
            val brakeActiveCycles = rows.count { it.iobRatio >= 0.70 }

            // earlyBoostWasActive: was earlyBoost ooit actief in deze episode?
            val earlyBoostWasActive = rows.any { it.earlyBoostActive && it.earlyBoostFactor > 1.01 }

            // afterloadWasActive: heeft de afterload guard ingegrepen tijdens deze episode?
            // fd60Scale < 0.99: futureDrop60 guard actief (grote verwachte daling)
            // highIobScale < 0.99: hoge IOB in late fase guard actief
            // Minimaal 2 cycli actief om ruis te filteren.
            val afterloadWasActive = rows.count {
                it.afterloadFutureDrop60Scale < 0.99 || it.afterloadHighIobLateScale < 0.99
            } >= 2

            // EarlyBoost verdeling: som van doses gegeven als earlyBoost-stage.
            // Alleen commits 1–3 tellen als "echte" vroege commits.
            // highBgContinuation-commits (episodeCommitNr > 3, earlyBoostActive=true
            // bij BG >= 12.0) worden bewust uitgesloten: zij zijn een noodmaatregel
            // bij extreme maaltijden, geen maatstaf voor frontload-timing.
            // Zonder deze correctie blaast één highBgContinuation-commit de frac
            // kunstmatig op zodat de learner ten onrechte NONE geeft.
            val earlyBoostDeliveredU = rows
                .filter { it.earlyBoostActive && it.episodeCommitNr <= 3 }
                .sumOf { it.deliveredTotal }
            val earlyBoostFrac = if (totalInsulinDelivered > 0.1)
                earlyBoostDeliveredU / totalInsulinDelivered else 0.0

            // Gemiddelde voorspellingsfout per tijdvenster
            // predFout = gemiddelde(predictedPeak) - werkelijke piek
            // Positief = overschatting, negatief = onderschatting
            fun predFoutVenster(minStart: Int, minEnd: Int): Double? {
                val venster = rows.filter {
                    val m = it.minutesSinceMealStart ?: return@filter false
                    m in minStart..minEnd && (it.predictedPeak ?: 0.0) > 0.0
                }
                if (venster.isEmpty()) return null
                return venster.mapNotNull { it.predictedPeak }.average() - peakBg
            }
            val predFout0_20  = predFoutVenster(0, 20)
            val predFout20_40 = predFoutVenster(21, 40)

            // capReachedCycles: cycli waarbij maxSMB de limiterende factor was
            // Dit is het directe bewijs dat de cap te laag staat — systeem wilde meer
            val capReachedCycles = rows.count { it.guardMaxSmbLimited }

            // currentSterkte: meest recente S-waarde in de episode
            val currentSterkte = rows.lastOrNull { it.sterktePct > 0 }?.sterktePct ?: 100

            // firstFrontloadMinutes: minuten na episodestart van eerste frontload-trigger
            val firstFrontloadMinutes = rows
                .filter { it.watchingFrontloadTriggered && it.minutesSinceMealStart != null }
                .minByOrNull { it.minutesSinceMealStart!! }
                ?.minutesSinceMealStart ?: -1

            // FIX 3: advisorWeight straft episodes met hoge IOB op de piek af.
            // Een episode met iobRatioAtPeak >= 0.70 is het resultaat van te-laat-burst
            // gedrag en is daarmee minder representatief voor de "echte" profielkwaliteit.
            val advisorWeightBase = computeAdvisorWeight(
                totalInsulinDelivered = totalInsulinDelivered,
                ageDays               = ageDays,
                iobRatioAtPeak        = iobAtPeak,
                manualMaxSmb          = manualMaxSmb
            )
            // Episodes met handmatige correctie zijn minder representatief:
            // de piekdaling was mede door de externe bolus, niet door de parameters.
            // Halveer het gewicht zodat het leeralgoritme hier minder op leunt.
            val advisorWeight = if (hasManualCorrection) advisorWeightBase * 0.30 else advisorWeightBase

            val timeToPeakMinutes = peakRow?.let {
                Duration.between(episode.start, it.timestamp).toMinutes()
            }

            // ── Automatische near-hypo-detectie (20/06/2026) ───────────────
            // Doel: een hypo herkennen die alleen is uitgebleven doordat de
            // gebruiker (rescue-)koolhydraten at — zonder afhankelijk te zijn
            // van een handmatige "Ja"-bevestiging (die was oorspronkelijk
            // bedoeld om de rescue-detector-drempels te kalibreren, niet als
            // vereiste bewijslast — episodes die niemand bekeek bestonden
            // evengoed). Gezocht wordt, ná de episode-piek, naar een
            // dieptepunt met nog substantiële IOB gevolgd door een
            // duidelijke rebound. Drempels zijn bewust gelijk aan de
            // bestaande rescue-ARM/CONFIRM-logica in FCLvNext.kt, BEHALVE
            // de daar aanwezige eis dat er geen insuline gelijktijdig wordt
            // afgegeven — die eis is hier weggelaten: insuline kan een
            // BG-stijging niet verklaren, dus sluit die eis het probleem
            // hier juist uit i.p.v. bevestigt 'm.
            val rowsAfterPeak = peakRow?.let { pr -> rows.filter { it.timestamp > pr.timestamp } } ?: emptyList()
            val nadirRow = rowsAfterPeak.minByOrNull { it.bg }
            val nearHypoAverted = nadirRow != null &&
                nadirRow.bg < NEAR_HYPO_THRESH &&
                nadirRow.iob >= manualMaxSmb * SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB &&
                rowsAfterPeak.any {
                    it.timestamp > nadirRow.timestamp &&
                        it.accel >= REBOUND_ACCEL_MIN &&
                        it.slope >= REBOUND_SLOPE_MIN
                }

            // ── projectedSevereLowAverted (19/07/2026, Ecko) ────────────────
            // Zie kdoc bij EpisodeMetrics.projectedSevereLowAverted. Twee stappen:
            // 1. was er, terwijl BG zelf nog geen alarmerende waarde had maar er
            //    wel significante IOB actief was, een moment waarop de PROJECTIE
            //    (hypoProjectedBg, dezelfde live IOB/ISF/BG-berekening als
            //    FCLvNext.kt elke cyclus al maakt) ernstig laag stond?
            // 2. vlakte de BG daarna, rond target, met nog steeds significante
            //    IOB aanwezig, af of steeg die weer — zonder dat de gemeten BG
            //    ooit zelf de NEAR_HYPO-drempel raakte (anders vangt
            //    nearHypoAverted hierboven het al af)?
            val severeProjectionRow = rowsAfterPeak.firstOrNull {
                it.iob >= manualMaxSmb * SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB &&
                    it.hypoProjectedBg <= SEVERE_PROJECTION_THRESH   // ook (sterk) negatieve projecties tellen
            }
            val projectedSevereLowAverted = severeProjectionRow != null && !nearHypoAverted &&
                rowsAfterPeak.any {
                    it.timestamp > severeProjectionRow.timestamp &&
                        kotlin.math.abs(it.bg - it.target) <= NEAR_TARGET_BAND &&
                        it.iob >= manualMaxSmb * SUBSTANTIAL_DOSE_FRAC_OF_MAXSMB &&
                        it.slope >= -0.10
                }

            // 26/07/2026 (Ecko) — zie kdoc bij EpisodeMetrics.isNight.
            val isNight = rows.firstOrNull()?.isNight ?: false

            EpisodeMetrics(
                id = episode.id,
                isNight = isNight,
                start = episode.start,
                end = episode.end,
                postWindowEnd = episode.postWindowEnd,
                durationMinutes = duration,
                startBg = episode.startBg,
                avgBg = avgBg,
                endBg = endBg,
                peakBg = peakBg,
                riseMagnitude = peakBg - episode.startBg,
                timeToPeakMinutes = timeToPeakMinutes,
                minBgCore = episode.minBgCore,
                minBgInWindow = episode.minBgWindow,
                hypoDetected = episode.hypoDetected,
                hypoMinutes = episode.hypoMinutes,
                tbtDetected = episode.tbtDetected,
                tbtMinutes = episode.tbtMinutes,
                maxIobRatio = maxIobRatio,
                iobRatioAtPeak = iobAtPeak,
                totalInsulinDelivered = totalInsulinDelivered,
                totalInsulinIncExternal = totalInsulinIncExternal,
                externalBolusTotal = externalBolusTotal,
                hasManualCorrection = hasManualCorrection,
                advisorWeight = advisorWeight,
                includedInAdvice = false,
                adviceStatus = "NEW",
                rescueConfirmed = false,  // wordt later overschreven vanuit DB
                nearHypoAverted = nearHypoAverted,
                projectedSevereLowAverted = projectedSevereLowAverted,
                firstCommitU = firstCommitU,
                iobRatioAt15min = iobRatioAt15min,
                firstBigCommitFrac = firstBigCommitFrac,
                followUpCommitCount = followUpCommitCount,
                lastSignificantCommitFrac = lastSignificantCommitFrac,
                lastSignificantCommitMinutesBeforePeak = lastSignificantCommitMinutesBeforePeak,
                brakeActiveCycles = brakeActiveCycles,
                earlyBoostWasActive = earlyBoostWasActive,
                capReachedCycles = capReachedCycles,
                currentSterkte = currentSterkte,
                firstFrontloadMinutes = firstFrontloadMinutes,
                predFout0_20       = predFout0_20,
                predFout20_40      = predFout20_40,
                afterloadWasActive = afterloadWasActive,
                earlyBoostDeliveredU = earlyBoostDeliveredU,
                earlyBoostFrac = earlyBoostFrac,
                hasLateCommitViolation = safetyCheck.hasViolation,
                lateCommitViolationFraction = safetyCheck.violations.maxOfOrNull { it.fractionOfPeak } ?: 0.0
            )
        }
    }
}

private fun computeAdvisorWeight(
    totalInsulinDelivered: Double,
    ageDays: Long,
    iobRatioAtPeak: Double,
    manualMaxSmb: Double
): Double {

    /**
     * Minimale insulinedrempel voor een episode om mee te tellen in het leerproces.
     * Uitgedrukt als fractie van maxBolus (manualMaxBolus).
     * Default fallback als maxBolus niet beschikbaar: 1.0U absoluut minimum.
     *
     * Voorbeelden bij maxBolus = 2.5U:
     *   MIN_INSULIN_FRAC = 0.60 → min = 1.0U
     * Voorbeelden bij maxBolus = 1.25U:
     *   MIN_INSULIN_FRAC = 0.40 → min = 0.50U
     */
    val MIN_INSULIN_FRAC     = 0.60   // 60% van maxBolus
    val MIN_IOBR_AT_PEAK     = 0.10   // min 10% van maxIOB op piek (laag voor Lyumjev U200)

    val insulinDrempel = (MIN_INSULIN_FRAC * manualMaxSmb)
        .coerceAtLeast(0.40)   // absoluut minimum als fallback

    val insulinWeight = when {
        totalInsulinDelivered < insulinDrempel -> 0.0   // te weinig — verwerp
        totalInsulinDelivered < manualMaxSmb * 0.80 -> 0.5  // matig — half gewicht
        else -> 1.0
    }

    // ── IOB-bereik op piek: % van maxIOB ────────────────────────────────
    // Bij snelwerkende insuline (Lyumjev U200) is de insuline grotendeels
    // uitgewerkt tegen de piek — IOB@piek is structureel laag.
    // Drempel verlaagd naar 0.10 zodat snelwerkende insuline niet wordt
    // verworpen puur op basis van lage IOB@piek.
    val iobReachWeight = when {
        iobRatioAtPeak < MIN_IOBR_AT_PEAK -> 0.0
        else -> 1.0
    }

    // Als één van beide nul is → weight = 0 → "te weinig insuline"
    if (insulinWeight == 0.0 || iobReachWeight == 0.0) return 0.0

    val recencyFactor = when {
        ageDays <= 2  -> 1.00
        ageDays <= 5  -> 0.90
        ageDays <= 10 -> 0.75
        else          -> 0.60
    }

    // IOB-piek penalty (bestaande logica)
    val iobPeakPenalty = when {
        iobRatioAtPeak < 0.50  -> 1.00
        iobRatioAtPeak >= 0.85 -> 0.50
        else -> {
            val t = (iobRatioAtPeak - 0.50) / (0.85 - 0.50)
            1.00 - t * 0.50
        }
    }

    return (insulinWeight * recencyFactor * iobPeakPenalty).coerceIn(0.0, 1.0)
}