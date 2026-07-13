package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer

import java.time.Duration
import java.time.Instant

/**
 * SafetyInvariantChecker (12/07/2026, Ecko)
 *
 * Automatische, generieke bewaking van het §3-patroon uit het overdrachts-
 * document: "late, te grote commit vlak op de piek". In plaats van dit
 * telkens achteraf handmatig in de CSV/grafiek te moeten opmerken, checkt
 * deze functie het zelf, per episode, direct uit de data die de Analyzer
 * toch al inleest (LogRow/Episode — GEEN nieuwe databasevelden, GEEN Room-
 * schemawijziging nodig).
 *
 * INVARIANT: geen enkele geleverde dosis binnen [windowMinutes] van het
 * BG-hoogtepunt van de episode mag groter zijn dan [maxFraction] van de
 * grootste commit die die episode al is gegeven (episodePeakCommitU).
 * Overtreding = precies het patroon van de incidenten op 12/07 15:12 UTC
 * (2,65U, ~100% van episodePeakCommitU, binnen 5 min van de piek) en
 * 18:17 UTC (1,45U, ruim boven wat de commit-tak zelf net had berekend).
 *
 * Dit vervangt geen van de code-fixes in FCLvNext.kt (de graduele
 * omslagdetectie en de universele taper-clamp) — het is een onafhankelijke
 * bewaker ERNAAST, zodat een toekomstig, weer-nieuw mechanisme (het
 * overdrachtsdocument waarschuwt daar expliciet voor in §3/§5) niet
 * onopgemerkt blijft, ook als het via een heel ander pad ontstaat.
 */
object SafetyInvariantChecker {

    // Eerste inschatting (net als de 0.55-drempel in FCLvNext.kt) — evt.
    // bijstellen als dit in de praktijk te gevoelig/ongevoelig blijkt.
    const val DEFAULT_WINDOW_MINUTES = 10
    const val DEFAULT_MAX_FRACTION = 0.50

    // ── Helft-verdeling start→piek (13/07/2026, Ecko) ─────────────────────
    // AANVULLEND op de piek-nabijheid-check hierboven: die mist episodes
    // waarbij de insuline wél geconcentreerd (niet verspreid) werd gegeven,
    // maar niet toevallig vlak bij de piek. Concreet voorbeeld 13/07 08:52
    // UTC: 88% van de dosis viel in kwart 2 van de stijging (~20-25 min vóór
    // de piek) — geen schending volgens de piek-check hierboven, maar
    // evengoed een "alles-in-één-klap"-patroon.
    //
    // Referentiepunt voor "start van de stijging" is bewust episode.start —
    // dezelfde episode-grens die de Analyzer/episode-detector elders al
    // gebruikt, geen nieuwe eigen tijddefinitie. Splitst in twee helften
    // (niet vier kwarten): bij een ~Gaussische stijg-curve komt dat
    // ongeveer overeen met vóór/na het buigpunt, en is met de beperkte
    // resolutie (5 min/cyclus) van korte episodes betrouwbaarder dan vier
    // aparte kwarten.
    //
    // Vuistregel (Ecko, 13/07/2026): bij een goede frontload zit ruwweg 2/3
    // van de dosis in de eerste helft, 1/3 in de tweede.
    // BACKLOADED_THRESHOLD is een eerste, ruime inschatting — pas als
    // structureel signaal gebruiken (zie EpisodeSafetyResult.isBackloaded)
    // nadat een aantal episodes is bekeken, nog NIET gekoppeld aan de
    // learner (bewust stap voor stap, zie overdrachtsdocument §6/§3).
    const val IDEAL_SECOND_HALF_FRACTION = 1.0 / 3.0
    const val BACKLOADED_THRESHOLD = 0.45
    // Minimaal aantal minuten tussen episode.start en de piek om de
    // helft-splitsing betrouwbaar te achten (bij minder cycli per helft is
    // één cyclus toeval al genoeg om de fractie compleet te laten kantelen).
    const val MIN_RISE_MINUTES_FOR_HALVES = 10

    fun checkAll(
        episodes: List<Episode>,
        windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
        maxFraction: Double = DEFAULT_MAX_FRACTION
    ): List<EpisodeSafetyResult> = episodes.map { check(it, windowMinutes, maxFraction) }

    fun check(
        episode: Episode,
        windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
        maxFraction: Double = DEFAULT_MAX_FRACTION
    ): EpisodeSafetyResult {
        val rows = episode.rows
        val peakRow = rows.maxByOrNull { it.bg }

        // episodePeakCommitU: reconstructie uit bestaande data (max van de
        // reeds gelogde commitDoseFinal/deliveredTotal), niet apart opgeslagen —
        // zelfde grootheid als episodePeakCommitU in FCLvNext.kt, maar hier
        // achteraf uit de LogRow-reeks afgeleid.
        val episodePeakCommitU = rows.maxOfOrNull { maxOf(it.commitDoseFinal, it.deliveredTotal) } ?: 0.0

        if (peakRow == null || episodePeakCommitU <= 0.0) {
            return EpisodeSafetyResult(
                episodeId = episode.id,
                episodeStart = episode.start,
                episodeEnd = episode.end,
                peakBg = peakRow?.bg ?: 0.0,
                peakTimestamp = peakRow?.timestamp,
                episodePeakCommitU = episodePeakCommitU,
                violations = emptyList(),
                secondHalfFractionOfDose = null,
                isBackloaded = false
            )
        }

        val threshold = episodePeakCommitU * maxFraction

        val violations = rows
            .filter { row ->
                row.deliveredTotal > 0.0 &&
                    kotlin.math.abs(Duration.between(peakRow.timestamp, row.timestamp).toMinutes()) <= windowMinutes &&
                    row.deliveredTotal > threshold
            }
            .map { row ->
                SafetyViolation(
                    timestamp = row.timestamp,
                    deliveredU = row.deliveredTotal,
                    episodePeakCommitU = episodePeakCommitU,
                    fractionOfPeak = row.deliveredTotal / episodePeakCommitU,
                    minutesFromPeak = Duration.between(peakRow.timestamp, row.timestamp).toMinutes().toInt(),
                    dominantReason = dominantGuardLabel(row)
                )
            }

        // ── Helft-verdeling start→piek — zie kdoc bij MIN_RISE_MINUTES_FOR_HALVES ──
        val riseMinutes = Duration.between(episode.start, peakRow.timestamp).toMinutes()
        val secondHalfFractionOfDose: Double?
        if (riseMinutes >= MIN_RISE_MINUTES_FOR_HALVES) {
            val midpoint = episode.start.plus(Duration.between(episode.start, peakRow.timestamp).dividedBy(2))
            val riseRows = rows.filter {
                !it.timestamp.isBefore(episode.start) && !it.timestamp.isAfter(peakRow.timestamp)
            }
            val totalRiseDose = riseRows.sumOf { it.deliveredTotal }
            secondHalfFractionOfDose = if (totalRiseDose > 0.0) {
                val secondHalfDose = riseRows.filter { it.timestamp.isAfter(midpoint) }.sumOf { it.deliveredTotal }
                secondHalfDose / totalRiseDose
            } else null
        } else {
            secondHalfFractionOfDose = null
        }
        val isBackloaded = secondHalfFractionOfDose != null && secondHalfFractionOfDose > BACKLOADED_THRESHOLD

        return EpisodeSafetyResult(
            episodeId = episode.id,
            episodeStart = episode.start,
            episodeEnd = episode.end,
            peakBg = peakRow.bg,
            peakTimestamp = peakRow.timestamp,
            episodePeakCommitU = episodePeakCommitU,
            violations = violations,
            secondHalfFractionOfDose = secondHalfFractionOfDose,
            isBackloaded = isBackloaded
        )
    }

    /**
     * Leidt af welke bestaande rem (indien enige) actief was op deze cyclus —
     * puur uit velden die al gelogd worden (suppressReason/lockoutReason/
     * commitBlockReason/guards/bgStijgtNogFors). Geen nieuwe instrumentatie
     * nodig. Volgorde = specifiek vóór algemeen.
     *
     * "GEEN REM ACTIEF" is het interessantste resultaat: dat is precies het
     * profiel van beide incidenten van 12/07 — een dosis die groot genoeg was
     * om de invariant te schenden, terwijl geen enkele bekende rem hem had
     * tegengehouden.
     */
    fun dominantGuardLabel(row: LogRow): String = when {
        row.suppressReason.isNotBlank() && row.suppressReason != "NONE" -> row.suppressReason
        row.lockoutReason.isNotBlank() && row.lockoutReason != "NONE" -> row.lockoutReason
        row.commitBlockReason.isNotBlank() && row.commitBlockReason != "NONE" -> row.commitBlockReason
        row.peakIobBrakeActive -> "PEAK_IOB_BRAKE"
        row.topGuardActive -> "TOP_GUARD"
        row.guardMaxSmbLimited -> "MAXSMB_LIMIT"
        row.suppressForPeak -> "SUPPRESS_FOR_PEAK"
        row.bgStijgtNogFors -> "BG_STIJGT_NOG_FORS (vluchtklep)"
        else -> "GEEN REM ACTIEF"
    }
}

data class SafetyViolation(
    val timestamp: Instant,
    val deliveredU: Double,
    val episodePeakCommitU: Double,
    val fractionOfPeak: Double,
    val minutesFromPeak: Int,
    val dominantReason: String
)

data class EpisodeSafetyResult(
    val episodeId: Int,
    val episodeStart: Instant,
    val episodeEnd: Instant,
    val peakBg: Double,
    val peakTimestamp: Instant?,
    val episodePeakCommitU: Double,
    val violations: List<SafetyViolation>,
    // ── Helft-verdeling (13/07/2026, Ecko) — zie kdoc bij MIN_RISE_MINUTES_FOR_HALVES.
    // null = episode te kort (< MIN_RISE_MINUTES_FOR_HALVES) om betrouwbaar te splitsen.
    val secondHalfFractionOfDose: Double? = null,
    // true als secondHalfFractionOfDose > BACKLOADED_THRESHOLD. Puur diagnostisch —
    // (nog) niet gekoppeld aan FrontloadLearner, zie kdoc bovenaan dit bestand.
    val isBackloaded: Boolean = false
) {
    val hasViolation: Boolean get() = violations.isNotEmpty()
}
