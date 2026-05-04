package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration
import java.time.Instant
import kotlin.math.max

object EpisodeDetector {

    // Kotlin: enum mag niet lokaal in een functie
    private enum class State { IDLE, ACTIVE }

    data class Config(
        // windows / timing
        val preWindowMinutes: Long = 30,
        val postWindowMinutes: Long = 180,          // algemene postwindow als NIET gesplit
        val minTailMinutes: Long = 30,              // minimaal wachten na laatste dosis voordat je op IOB mag eindigen
        val maxEpisodeMinutes: Long = 360,          // harde stop

        // dose detectie / split
        val minDoseStep: Double = 0.05,             // minimale stap in deliveredTotal om als "dose event" te tellen
        val relativeSplitFactor: Double = 0.30,     // nieuwe liability >= 30% van tot nu toe gegeven episode-dosis
        val minDropFromPeak: Double = 0.8,          // mmol/L onder piek voordat we "inDescent" accepteren

        // einde-criteria
        val iobRatioEndThreshold: Double = 0.05,    // IOB-ratio laag genoeg om episode te sluiten (mits minTailMinutes)

        // hypo detect (laat je staan; jij koppelt hypo later aan postWindowEnd)
        val postHypoSearchMinutes: Long = 180,
        val hypoThreshold: Double = 3.9,

        // sampling/gaps (nu niet gebruikt in minimal versie, maar laten staan voor later)
        val expectedSampleMinutes: Long = 5,
        val maxAllowedGapMinutes: Long = 25,
        val maxStepClampMinutes: Long = 10,

        val reversalSlopeThreshold: Double = 0.4,   // mmol/L per uur
        val reversalRisePerSample: Double = 0.15,   // mmol/L stijging per sample
        val reversalConfirmSamples: Int = 2         // aantal opeenvolgende bevestigingen
    )

    fun detect(rows: List<LogRow>, config: Config = Config()): List<Episode> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedBy { it.timestamp }

        val episodes = mutableListOf<Episode>()
        var state = State.IDLE

        var episodeRows = mutableListOf<LogRow>()
        var episodeStart: Instant? = null
        var episodeTotalDose = 0.0
        var lastDelivered = sorted.first().deliveredTotal
        var lastDoseTime: Instant? = null

        var peakBg = Double.NEGATIVE_INFINITY
        var inDescent = false

        var episodeId = 0

        var reversalCount = 0
        var lastBg = sorted.first().bg

        fun resetState() {
            episodeRows = mutableListOf()
            episodeStart = null
            episodeTotalDose = 0.0
            lastDoseTime = null
            peakBg = Double.NEGATIVE_INFINITY
            inDescent = false
            state = State.IDLE
        }

        fun finalizeEpisode(endTime: Instant, split: Boolean) {
            if (episodeRows.isEmpty() || episodeStart == null) return

            val startTs = episodeStart!!
            val endTs = endTime

            val postEnd = if (split) {
                endTs // 🔴 split: postwindow stopt direct
            } else {
                endTs.plusSeconds(config.postWindowMinutes * 60)
            }

            val rowsCopy = episodeRows.toList()

            episodes.add(
                Episode(
                    id = episodeId++,
                    rows = rowsCopy,
                    start = startTs,
                    end = endTs,
                    postWindowEnd = postEnd,
                    coreStart = startTs,
                    firstDoseTime = lastDoseTime,            // (zoals jij had; als je een echte "first" wil: aparte var)
                    startBg = rowsCopy.first().bg,
                    minBgCore = rowsCopy.minOf { it.bg },
                    minBgWindow = rowsCopy.minOf { it.bg },
                    hypoDetected = false,
                    hypoMinutes = 0,
                    tbtDetected = false,
                    tbtMinutes = 0,
                    isComplete = true,

                    sterktePct = rowsCopy.first().sterktePct,
                    timingPct = rowsCopy.first().timingPct,
                    volhoudendheidPct = rowsCopy.first().volhoudendheidPct,

                    doseDistribution = rowsCopy.first().doseDistribution
                )
            )

            resetState()
        }

        for (r in sorted) {
            val deltaDose = r.deliveredTotal - lastDelivered
            lastDelivered = r.deliveredTotal

            val doseEvent = deltaDose >= config.minDoseStep

            when (state) {
                State.IDLE -> {
                    if (doseEvent) {
                        state = State.ACTIVE
                        episodeStart = r.timestamp.minusSeconds(config.preWindowMinutes * 60)
                        episodeTotalDose = deltaDose
                        lastDoseTime = r.timestamp
                        peakBg = r.bg
                        inDescent = false
                        episodeRows.add(r)
                    }
                }

                State.ACTIVE -> {

                    episodeRows.add(r)

                    if (r.bg > peakBg) peakBg = r.bg

                    // dalingsfase detectie
                    if (!inDescent &&
                        r.bg <= peakBg - config.minDropFromPeak &&
                        r.slope <= 0.0
                    ) {
                        inDescent = true
                    }

                    // 🔴 Reversal detectie
                    val bgRise = r.bg - lastBg

                    val reversalSignal =
                        r.slope >= config.reversalSlopeThreshold ||
                            bgRise >= config.reversalRisePerSample

                    if (inDescent && reversalSignal) {
                        reversalCount++
                    } else {
                        reversalCount = 0
                    }

                    lastBg = r.bg

                    if (doseEvent) {

                        val minSplitDose = max(
                            config.minDoseStep,
                            config.relativeSplitFactor * episodeTotalDose
                        )

                        val confirmedReversal =
                            reversalCount >= config.reversalConfirmSamples

                        if (inDescent && deltaDose >= minSplitDose && confirmedReversal) {

                            // 🔴 SPLIT zonder overlap
                            episodeRows.removeAt(episodeRows.lastIndex)

                            finalizeEpisode(r.timestamp, split = true)

                            // nieuwe episode starten
                            state = State.ACTIVE
                            episodeStart = r.timestamp.minusSeconds(config.preWindowMinutes * 60)
                            episodeTotalDose = deltaDose
                            lastDoseTime = r.timestamp
                            peakBg = r.bg
                            inDescent = false
                            reversalCount = 0
                            episodeRows.add(r)

                            continue
                        }

                        episodeTotalDose += deltaDose
                        lastDoseTime = r.timestamp
                    }

                    val minutesSinceLastDose =
                        lastDoseTime?.let { Duration.between(it, r.timestamp).toMinutes() } ?: 0L

                    val episodeDuration =
                        episodeStart?.let { Duration.between(it, r.timestamp).toMinutes() } ?: 0L

                    val endByIob =
                        (r.iobRatio < config.iobRatioEndThreshold) &&
                            (minutesSinceLastDose >= config.minTailMinutes)

                    val endByMax =
                        episodeDuration >= config.maxEpisodeMinutes

                    if (endByIob || endByMax) {
                        finalizeEpisode(r.timestamp, split = false)
                    }
                }
            }
        }

        if (state == State.ACTIVE && episodeRows.isNotEmpty() && episodeStart != null) {

            val rowsCopy = episodeRows.toList()

            episodes.add(
                Episode(
                    id = episodeId++,
                    rows = rowsCopy,
                    start = episodeStart!!,
                    end = sorted.last().timestamp,
                    postWindowEnd = sorted.last().timestamp, // nog geen echte post window
                    coreStart = episodeStart!!,
                    firstDoseTime = lastDoseTime,
                    startBg = rowsCopy.first().bg,
                    minBgCore = rowsCopy.minOf { it.bg },
                    minBgWindow = rowsCopy.minOf { it.bg },
                    hypoDetected = false,
                    hypoMinutes = 0,
                    tbtDetected = false,
                    tbtMinutes = 0,
                    isComplete = false,

                    sterktePct = rowsCopy.first().sterktePct,
                    timingPct = rowsCopy.first().timingPct,
                    volhoudendheidPct = rowsCopy.first().volhoudendheidPct,

                    doseDistribution = rowsCopy.first().doseDistribution
                )
            )
        }

        return episodes
    }
}