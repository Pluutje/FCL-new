package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration

object EpisodeMetricsBuilder {



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

            // followUpCommitCount: commits met deliveredTotal > 0.10U
            // die LATER komen dan de grootste commit
            val bigCommitTs = rows.maxByOrNull { it.deliveredTotal }?.timestamp
            val followUpCommitCount = if (bigCommitTs != null)
                rows.count { it.deliveredTotal > 0.10 && it.timestamp > bigCommitTs }
            else 0

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

            // EarlyBoost verdeling: som van doses gegeven als earlyBoost-stage
            val earlyBoostDeliveredU = rows
                .filter { it.earlyBoostActive }
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

            EpisodeMetrics(
                id = episode.id,
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
                firstCommitU = firstCommitU,
                iobRatioAt15min = iobRatioAt15min,
                firstBigCommitFrac = firstBigCommitFrac,
                followUpCommitCount = followUpCommitCount,
                brakeActiveCycles = brakeActiveCycles,
                earlyBoostWasActive = earlyBoostWasActive,
                capReachedCycles = capReachedCycles,
                currentSterkte = currentSterkte,
                firstFrontloadMinutes = firstFrontloadMinutes,
                predFout0_20       = predFout0_20,
                predFout20_40      = predFout20_40,
                afterloadWasActive = afterloadWasActive,
                earlyBoostDeliveredU = earlyBoostDeliveredU,
                earlyBoostFrac = earlyBoostFrac
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