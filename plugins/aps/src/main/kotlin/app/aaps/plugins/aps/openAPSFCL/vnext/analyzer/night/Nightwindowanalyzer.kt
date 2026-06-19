package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.night

import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.EpisodeEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.database.FCLCycleLogEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.NightWindowEntity
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.database.BasalProfileHistoryEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

enum class NightWindowClass {
    FASTING_STABLE,
    LIGHT_CORRECTION,
    MEAL_CARRYOVER,
    DISTURBED
}

enum class NightDriftSignal {
    BASAL_UP,
    BASAL_DOWN,
    NEUTRAL,
    UNCERTAIN
}

object NightWindowAnalyzer {

    private const val WINDOW_MINUTES = 90L
    private const val STEP_MINUTES = 30L
    private const val LAST_WINDOW_START_HOUR = 6
    private const val LAST_WINDOW_START_MINUTE = 30
    private const val BASAL_SHIFT_MINUTES = 75L

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        rows: List<FCLCycleLogEntity>,
        episodes: List<EpisodeEntity>,
        profiles: List<BasalProfileHistoryEntity>,
        zone: ZoneId = ZoneId.of("Europe/Amsterdam")
    ): List<NightWindowEntity> {
        if (rows.isEmpty()) return emptyList()

        val parsedRows = rows.map { ParsedRow(it, java.time.Instant.ofEpochMilli(it.timestampMs)) }
            .sortedBy { it.instant }

        val parsedEpisodes = episodes.map {
            ParsedEpisode(
                entity = it,
                start = Instant.parse(it.startTs),
                end = Instant.parse(it.endTs)
            )
        }

        val nightSegments = splitIntoNightSegments(parsedRows)

        return nightSegments.flatMap { segment ->
            val segmentStartLocal = segment.first().instant.atZone(zone).toLocalDateTime()
            val segmentEndLocal = segment.last().instant.atZone(zone).toLocalDateTime()

            generateWindowStartsForSegment(
                segmentStart = segmentStartLocal,
                segmentEnd = segmentEndLocal
            ).mapNotNull { startLocal ->

                val endLocal = startLocal.plusMinutes(WINDOW_MINUTES)
                val startInstant = startLocal.atZone(zone).toInstant()
                val endInstant = endLocal.atZone(zone).toInstant()

                val windowRows = segment.filter {
                    it.instant >= startInstant && it.instant < endInstant
                }

                if (windowRows.isEmpty()) {
                    null
                } else {
                    val nightBucketDate =
                        if (startLocal.hour < 12) startLocal.toLocalDate().minusDays(1)
                        else startLocal.toLocalDate()

                    buildWindow(
                        date = nightBucketDate,
                        startLocal = startLocal,
                        endLocal = endLocal,
                        startInstant = startInstant,
                        endInstant = endInstant,
                        rows = windowRows,
                        episodes = parsedEpisodes,
                        profiles = profiles,
                        zone = zone
                    )
                }
            }
        }.sortedByDescending { it.startTs }
    }

    private fun buildWindow(
        date: LocalDate,
        startLocal: LocalDateTime,
        endLocal: LocalDateTime,
        startInstant: Instant,
        endInstant: Instant,
        rows: List<ParsedRow>,
        episodes: List<ParsedEpisode>,
        profiles: List<BasalProfileHistoryEntity>,
        zone: ZoneId
    ): NightWindowEntity {
        val sortedRows = rows.sortedBy { it.instant }

        val first = sortedRows.first()
        val last = sortedRows.last()

        val rowCount = sortedRows.size
        val avgBg = sortedRows.map { it.entity.bg }.average()
        val avgTarget = sortedRows.map { it.entity.target }.average()
        val minBg = sortedRows.minOf { it.entity.bg }
        val maxBg = sortedRows.maxOf { it.entity.bg }
        val bgDelta = last.entity.bg - first.entity.bg

        // Directe TAT/TBT meting per venster (band = ±0.7 mmol/L rond target)
        val tatPct = if (rowCount == 0) 0.0
        else sortedRows.count { it.entity.bg > avgTarget + 0.7 }.toDouble() / rowCount * 100.0
        val tbtPct = if (rowCount == 0) 0.0
        else sortedRows.count { it.entity.bg < avgTarget - 0.7 }.toDouble() / rowCount * 100.0

        val durationHours =
            max(1.0 / 60.0, Duration.between(startInstant, endInstant).toMinutes().toDouble() / 60.0)
        val bgSlopePerHour = bgDelta / durationHours

        val avgIob = sortedRows.map { it.entity.iob }.average()
        val iobDelta = last.entity.iob - first.entity.iob

        val maxGapMinutes = sortedRows.zipWithNext { a, b ->
            Duration.between(a.instant, b.instant).toMinutes()
        }.maxOrNull() ?: 0L

        val overlappingEpisodes = episodes.filter {
            it.end > startInstant && it.start < endInstant
        }

        val mealCarryoverScore = computeMealCarryoverScore(
            rows = sortedRows,
            overlappingEpisodes = overlappingEpisodes,
            avgBg = avgBg,
            avgTarget = avgTarget,
            maxBg = maxBg,
            avgIob = avgIob,
            bgDelta = bgDelta
        )

        // Alleen CONFIRMED rescue telt als verstoring — ARMED is een voorzorgsmaatregel
        // die bij nachtelijke lage BG heel gewoon voorkomt en geen echte hypo aangeeft.
        val rescueActive = sortedRows.any {
            it.entity.rescueState.trim() == "CONFIRMED"
        }

        val repeatedHypoGuard = sortedRows.count { it.entity.hypoActive } >= 3

        val classification = when {
            rowCount < 8 -> NightWindowClass.DISTURBED
            maxGapMinutes > 40L -> NightWindowClass.DISTURBED  // 40 min = 8 gemiste CGM-cycli
            rescueActive -> NightWindowClass.DISTURBED
            repeatedHypoGuard && minBg < 4.0 -> NightWindowClass.DISTURBED
            mealCarryoverScore >= 0.55 -> NightWindowClass.MEAL_CARRYOVER
            avgIob >= 1.0 -> NightWindowClass.LIGHT_CORRECTION
            abs(iobDelta) >= 0.5 -> NightWindowClass.LIGHT_CORRECTION
            (maxBg - minBg) >= 1.6 -> NightWindowClass.LIGHT_CORRECTION
            overlappingEpisodes.isNotEmpty() -> NightWindowClass.LIGHT_CORRECTION
            else -> NightWindowClass.FASTING_STABLE
        }

        val suitabilityWeight = computeSuitabilityWeight(
            classification = classification,
            avgIob = avgIob,
            bgDelta = bgDelta,
            rowCount = rowCount,
            overlappingEpisodeCount = overlappingEpisodes.size
        )

        val signal = computeDriftSignal(
            suitabilityWeight = suitabilityWeight,
            avgBg = avgBg,
            avgTarget = avgTarget,
            bgSlopePerHour = bgSlopePerHour,
            avgIob = avgIob,
            iobDelta = iobDelta
        )

        val driftStrength = computeDriftStrength(
            suitabilityWeight = suitabilityWeight,
            avgBg = avgBg,
            avgTarget = avgTarget,
            bgSlopePerHour = bgSlopePerHour
        )

        val driftReason = buildDriftReason(
            signal = signal,
            avgBg = avgBg,
            avgTarget = avgTarget,
            bgSlopePerHour = bgSlopePerHour,
            avgIob = avgIob
        )

        val shiftedStart = startLocal.minusMinutes(BASAL_SHIFT_MINUTES)
        val shiftedEnd = endLocal.minusMinutes(BASAL_SHIFT_MINUTES)
        val shiftedMid = shiftedStart.plusMinutes(WINDOW_MINUTES / 2)
        val effectHour = shiftedMid.hour
        val effectHourLabel =
            "${effectHour.toString().padStart(2, '0')}:00–${((effectHour + 1) % 24).toString().padStart(2, '0')}:00"

        val resolvedProfile = resolveActiveProfile(
            windowStart = startInstant,
            profiles = profiles
        )

        val activeProfileSignature = resolvedProfile?.signature ?: "UNKNOWN"
        val activeProfileKnown = resolvedProfile?.knownFromHistory ?: false
        val activeProfileAssumedBeforeHistory = resolvedProfile?.assumedBeforeHistory ?: false
        val activeProfileSourceTsUtc = resolvedProfile?.sourceTsUtc ?: ""
        val activeProfileBasalUph = resolvedProfile?.basalAtHour?.invoke(effectHour) ?: 0.0

        val lastRow = sortedRows.lastOrNull()?.entity
        val activeSterktePct        = lastRow?.sterktePct        ?: 100
        val activeTimingPct         = lastRow?.timingPct         ?: 100
        val activeVolhoudendheidPct = lastRow?.volhoudendheidPct ?: 100
        val activeDoseDistributionStyle =
            latestNonBlank(sortedRows, { it.doseDistributionStyle }, "UNKNOWN_DOSE_DISTRIBUTION")

        val activeNightResponseStyle =
            latestNonBlank(sortedRows, { it.nightResponseStyle }, "BALANCED")  // legacy-veld; nfLevel via DFLearner

        val activeSettingsSignature = listOf(
            activeProfileSourceTsUtc.ifBlank { "NO_BASAL_HISTORY" },
            activeProfileSignature.ifBlank { "UNKNOWN_PROFILE_SIGNATURE" },
            "S${activeSterktePct}T${activeTimingPct}V${activeVolhoudendheidPct}",
            activeNightResponseStyle
        ).joinToString("|")

        val accessLimitedRatio =
            sortedRows.count {
                it.entity.doseAccess == "BLOCKED" ||
                    it.entity.doseAccess == "MICRO_ONLY" ||
                    it.entity.doseAccess == "SMALL"
            }.toDouble() / rowCount.toDouble()

        val guardLimitedRatio =
            sortedRows.count {
                it.entity.guardIobLimited ||
                    it.entity.guardPeakLimited ||
                    it.entity.guardMaxSmbLimited ||
                    it.entity.guardMinDeliverClipped ||
                    it.entity.guardZoneLimited ||
                    it.entity.trajectoryHardBlock ||
                    it.entity.topGuardActive
            }.toDouble() / rowCount.toDouble()

        val riseDespiteIobScore =
            (
                (if (avgBg > avgTarget + 0.8) 0.25 else 0.0) +
                    (if (bgDelta > 0.3) 0.20 else 0.0) +
                    (if (iobDelta > 0.10) 0.20 else 0.0) +
                    (if (accessLimitedRatio >= 0.33) 0.20 else 0.0) +
                    (if (sortedRows.any { it.entity.pred60 > it.entity.bg + 0.4 }) 0.15 else 0.0)
                ).coerceIn(0.0, 1.0)

        val lateOvershootRiskScore =
            (
                (if (sortedRows.any { it.entity.hypoActive && it.entity.bg > it.entity.target + 1.0 }) 0.45 else 0.0) +
                    (if (sortedRows.any { it.entity.hypoProjectedBg in 0.1..4.6 && it.entity.bg > it.entity.target + 1.5 }) 0.35 else 0.0) +
                    (if (avgIob >= 0.8 && bgDelta < -0.5) 0.20 else 0.0)
                ).coerceIn(0.0, 1.0)

        val guardFrictionScore =
            (
                (if (avgBg > avgTarget + 0.8) 0.25 else 0.0) +
                    (if (iobDelta > 0.05) 0.15 else 0.0) +
                    (if (accessLimitedRatio >= 0.40) 0.30 else 0.0) +
                    (if (guardLimitedRatio >= 0.25) 0.30 else 0.0)
                ).coerceIn(0.0, 1.0)

        val nightMechanism = when {
            classification == NightWindowClass.MEAL_CARRYOVER ->
                "MEAL_CARRYOVER"

            classification == NightWindowClass.DISTURBED ->
                "DISTURBED"

            riseDespiteIobScore >= 0.45 && lateOvershootRiskScore >= 0.45 ->
                "DELAYED_RESPONSE_WITH_OVERSHOOT"

            guardFrictionScore >= 0.55 ->
                "GUARD_FRICTION"

            riseDespiteIobScore >= 0.45 ->
                "DELAYED_RESPONSE"

            else ->
                "CLEAN_BASAL"
        }

        return NightWindowEntity(
            id = startInstant.toString(),
            localDate = date.toString(),
            startTs = startInstant.toString(),
            endTs = endInstant.toString(),
            slotLabel = "${timeFormatter.format(startLocal)}–${timeFormatter.format(endLocal)}",
            classification = classification.name,
            suitabilityWeight = suitabilityWeight,
            rowCount = rowCount,
            avgBg = avgBg,
            avgTarget = avgTarget,
            minBg = minBg,
            maxBg = maxBg,
            bgDelta = bgDelta,
            bgSlopePerHour = bgSlopePerHour,
            avgIob = avgIob,
            iobDelta = iobDelta,
            mealCarryoverScore = mealCarryoverScore,
            overlappingEpisodeCount = overlappingEpisodes.size,
            driftSignal = signal.name,
            driftStrength = driftStrength,
            driftReason = driftReason,
            shiftedBlockLabel = "${timeFormatter.format(shiftedStart)}–${timeFormatter.format(shiftedEnd)}",
            effectHour = effectHour,
            effectHourLabel = effectHourLabel,
            activeProfileSignature = activeProfileSignature,
            activeProfileKnown = activeProfileKnown,
            activeProfileAssumedBeforeHistory = activeProfileAssumedBeforeHistory,
            activeProfileSourceTsUtc = activeProfileSourceTsUtc,
            activeProfileBasalUph = activeProfileBasalUph,

            activeSterktePct = activeSterktePct,
            activeTimingPct = activeTimingPct,
            activeVolhoudendheidPct = activeVolhoudendheidPct,
            activeDoseDistributionStyle = activeDoseDistributionStyle,

            activeNightResponseStyle = activeNightResponseStyle,
            activeSettingsSignature = activeSettingsSignature,

            nightMechanism = nightMechanism,
            riseDespiteIobScore = riseDespiteIobScore,
            lateOvershootRiskScore = lateOvershootRiskScore,
            guardFrictionScore = guardFrictionScore,
            tatPct = tatPct,
            tbtPct = tbtPct
        )
    }


    private fun latestNonBlank(
        rows: List<ParsedRow>,
        selector: (FCLCycleLogEntity) -> String,
        fallback: String
    ): String =
        rows.asReversed()
            .map { selector(it.entity).trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: fallback

    private fun computeMealCarryoverScore(
        rows: List<ParsedRow>,
        overlappingEpisodes: List<ParsedEpisode>,
        avgBg: Double,
        avgTarget: Double,
        maxBg: Double,
        avgIob: Double,
        bgDelta: Double
    ): Double {
        var score = 0.0

        if (overlappingEpisodes.any { it.entity.peakBg >= 10.0 && it.entity.totalInsulinDelivered >= 1.0 }) {
            score += 0.45
        }

        if (overlappingEpisodes.any { it.entity.hyper }) {
            score += 0.20
        }

        if (rows.any { it.entity.peakEpisodeActive || it.entity.mealState.contains("WATCH", ignoreCase = true) }) {
            score += 0.10
        }

        if (maxBg >= 9.8) {
            score += 0.20
        }

        if (avgBg > avgTarget + 2.0) {
            score += 0.15
        }

        if (avgIob >= 1.5) {
            score += 0.10
        }

        if (bgDelta <= -1.0 && maxBg >= 9.0) {
            score += 0.10
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun computeSuitabilityWeight(
        classification: NightWindowClass,
        avgIob: Double,
        bgDelta: Double,
        rowCount: Int,
        overlappingEpisodeCount: Int
    ): Double {
        val base = when (classification) {
            NightWindowClass.FASTING_STABLE -> 1.0
            NightWindowClass.LIGHT_CORRECTION -> 0.55
            NightWindowClass.MEAL_CARRYOVER -> 0.0
            NightWindowClass.DISTURBED -> 0.0
        }

        var weight = base

        if (avgIob >= 2.0) weight *= 0.70
        if (abs(bgDelta) >= 1.5) weight *= 0.85
        if (rowCount < 14) weight *= 0.80
        if (overlappingEpisodeCount > 0 && classification == NightWindowClass.LIGHT_CORRECTION) {
            weight *= 0.80
        }

        return weight.coerceIn(0.0, 1.0)
    }

    private fun computeDriftSignal(
        suitabilityWeight: Double,
        avgBg: Double,
        avgTarget: Double,
        bgSlopePerHour: Double,
        avgIob: Double,
        iobDelta: Double
    ): NightDriftSignal {
        if (suitabilityWeight < 0.20) return NightDriftSignal.UNCERTAIN

        return when {
            avgBg >= avgTarget + 1.0 &&
                bgSlopePerHour >= 0.10 &&
                avgIob <= 1.5 &&
                iobDelta <= 0.25 ->
                NightDriftSignal.BASAL_UP

            avgBg <= avgTarget - 0.6 &&
                bgSlopePerHour <= -0.10 &&
                avgIob <= 1.2 ->
                NightDriftSignal.BASAL_DOWN

            abs(avgBg - avgTarget) <= 0.5 &&
                abs(bgSlopePerHour) <= 0.15 ->
                NightDriftSignal.NEUTRAL

            else ->
                NightDriftSignal.UNCERTAIN
        }
    }

    private fun computeDriftStrength(
        suitabilityWeight: Double,
        avgBg: Double,
        avgTarget: Double,
        bgSlopePerHour: Double
    ): Double {
        val targetDelta = abs(avgBg - avgTarget).coerceAtMost(3.0) / 3.0
        val slopeComponent = abs(bgSlopePerHour).coerceAtMost(1.0)
        return (suitabilityWeight * (0.65 * targetDelta + 0.35 * slopeComponent))
            .coerceIn(0.0, 1.0)
    }

    private fun buildDriftReason(
        signal: NightDriftSignal,
        avgBg: Double,
        avgTarget: Double,
        bgSlopePerHour: Double,
        avgIob: Double
    ): String {
        return when (signal) {
            NightDriftSignal.BASAL_UP ->
                "BG ligt gemiddeld %.1f boven target en stijgt nog door bij relatief lage/aflopende IOB."
                    .format(avgBg - avgTarget)

            NightDriftSignal.BASAL_DOWN ->
                "BG ligt gemiddeld %.1f onder target en daalt verder zonder duidelijke hoge IOB-belasting."
                    .format(avgTarget - avgBg)

            NightDriftSignal.NEUTRAL ->
                "BG blijft dicht bij target met beperkte drift en een interpreteerbare IOB-context."

            NightDriftSignal.UNCERTAIN ->
                "Geen zuiver basaal signaal; BG/IOB-context is gemengd of nog te beïnvloed."
        }
    }

    private fun generateWindowStarts(date: LocalDate): List<LocalDateTime> {
        val result = mutableListOf<LocalDateTime>()
        var current = date.atTime(0, 0)
        val last = date.atTime(LAST_WINDOW_START_HOUR, LAST_WINDOW_START_MINUTE)

        while (!current.isAfter(last)) {
            result += current
            current = current.plusMinutes(STEP_MINUTES)
        }

        return result
    }

    private data class ResolvedProfile(
        val signature: String,
        val sourceTsUtc: String,
        val knownFromHistory: Boolean,
        val assumedBeforeHistory: Boolean,
        val basalAtHour: (Int) -> Double
    )

    private fun resolveActiveProfile(
        windowStart: Instant,
        profiles: List<BasalProfileHistoryEntity>
    ): ResolvedProfile? {
        if (profiles.isEmpty()) return null

        val sorted = profiles.sortedBy { it.tsUtc }

        val latestBeforeOrAt = sorted.lastOrNull { java.time.Instant.parse(it.tsUtc) <= windowStart }
        if (latestBeforeOrAt != null) {
            return ResolvedProfile(
                signature = latestBeforeOrAt.profileSignature,
                sourceTsUtc = latestBeforeOrAt.tsUtc,
                knownFromHistory = true,
                assumedBeforeHistory = false,
                basalAtHour = { hour -> latestBeforeOrAt.basalAtHour(hour) }
            )
        }

        val earliest = sorted.first()
        return ResolvedProfile(
            signature = earliest.profileSignature,
            sourceTsUtc = earliest.tsUtc,
            knownFromHistory = false,
            assumedBeforeHistory = true,
            basalAtHour = { hour -> earliest.basalAtHour(hour) }
        )
    }

    private fun splitIntoNightSegments(
        rows: List<ParsedRow>
    ): List<List<ParsedRow>> {
        val result = mutableListOf<MutableList<ParsedRow>>()
        var current = mutableListOf<ParsedRow>()

        rows.forEach { row ->
            if (row.entity.isNight) {
                current += row
            } else if (current.isNotEmpty()) {
                result += current
                current = mutableListOf()
            }
        }

        if (current.isNotEmpty()) {
            result += current
        }

        return result
    }

    private fun generateWindowStartsForSegment(
        segmentStart: LocalDateTime,
        segmentEnd: LocalDateTime
    ): List<LocalDateTime> {
        val alignedStart =
            segmentStart
                .withMinute((segmentStart.minute / STEP_MINUTES.toInt()) * STEP_MINUTES.toInt())
                .withSecond(0)
                .withNano(0)

        val lastAllowed = segmentEnd.minusMinutes(WINDOW_MINUTES)

        if (lastAllowed.isBefore(alignedStart)) return emptyList()

        val result = mutableListOf<LocalDateTime>()
        var current = alignedStart

        while (!current.isAfter(lastAllowed)) {
            result += current
            current = current.plusMinutes(STEP_MINUTES)
        }

        return result
    }

    private data class ParsedRow(
        val entity: FCLCycleLogEntity,
        val instant: Instant
    )

    private data class ParsedEpisode(
        val entity: EpisodeEntity,
        val start: Instant,
        val end: Instant
    )
}