package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import org.joda.time.Minutes
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * FCLvNextTrends — trendberekening voor FCL vNext.
 *
 * Twee rijstroken:
 *   SLOW lane: EWMA met recency-weging over de laatste ~30 min.
 *              Levert: firstDerivative (slope mmol/h), secondDerivative (acceleratie),
 *              consistency (0-1 signaalbetrouwbaarheid).
 *   FAST lane: gewogen least-squares over de laatste 4-5 punten.
 *              Levert: recentSlope (mmol/h), recentDelta5m (mmol/5min).
 *
 * Verbeteringen t.o.v. de vorige versie:
 *   1. calculateRecentRaw gebruikt gewogen least-squares (WLS) over 4-5 punten
 *      ipv. een ruwe 2-punts delta. Dit benadert de UKF-snelheidsschatting
 *      zonder API-uitbreiding: de .recalculated waarden zijn al UKF-gesmoothed
 *      (na de calibratie-fix), dus WLS over die waarden is veel stabieler.
 *
 *   2. calculateSlopes gebruikt een EWMA-gewogen gemiddelde (recent > oud) ipv.
 *      een uniform gemiddelde. Hierdoor reageert de SLOW lane sneller op
 *      koerswijzigingen terwijl langzame ruis wordt onderdrukt.
 *
 *   3. consistentie-berekening weegt magnitude-inconsistentie minder zwaar
 *      vlak na een snelle omslag (bijv. meal peak → dalende BG).
 */
object FCLvNextTrends {

    data class BGPoint(
        val time: DateTime,
        val bg: Double
    )

    data class RobustTrendAnalysis(
        // SLOW lane (EWMA)
        val firstDerivative: Double,        // mmol/L per uur
        val secondDerivative: Double,       // mmol/L per uur²
        val consistency: Double,            // 0..1
        val directionConsistency: Double,   // 0..1
        val magnitudeConsistency: Double,   // 0..1
        val phase: Phase,

        // FAST lane (WLS over gesmoothe punten)
        val recentSlope: Double,            // mmol/L per uur
        val recentDelta5m: Double           // mmol/L per 5 min (genormaliseerd)
    )

    enum class Phase {
        RISING, FALLING, STABLE,
        ACCELERATING_UP, ACCELERATING_DOWN,
        UNKNOWN
    }

    // EWMA decay-factor voor de SLOW lane: α=0.70 betekent dat het meest
    // recente segment ~70% gewicht heeft, het op-één-na-recentste ~21%, etc.
    private const val EWMA_ALPHA = 0.70

    // Aantal punten voor de WLS-snelheidsschatting in de FAST lane.
    // 4 punten = 15 min geschiedenis bij 5-min interval.
    private const val WLS_POINTS = 4

    fun calculateTrends(
        rawData: List<BGPoint>,
        filteredData: List<BGPoint>
    ): RobustTrendAnalysis {
        val raw      = rawData.sortedBy { it.time.millis }
        val filtered = filteredData.sortedBy { it.time.millis }

        if (filtered.size < 5 || raw.size < 2) {
            return RobustTrendAnalysis(0.0, 0.0, 0.0, 0.0, 0.0, Phase.UNKNOWN, 0.0, 0.0)
        }

        // ── SLOW lane (EWMA-gewogen) ──────────────────────────────────────
        val slopes = calculateSlopesEwma(filtered)
        val first  = if (slopes.isNotEmpty()) ewmaAverage(slopes) else 0.0
        val second = calculateSecondDerivative(slopes)

        val dirConsistency = calculateDirectionConsistency(slopes)
        val magConsistency = calculateMagnitudeConsistency(slopes)
        val consistency    = (0.6 * dirConsistency + 0.4 * magConsistency).coerceIn(0.0, 1.0)
        val phase          = determinePhase(first, second, consistency)

        // ── FAST lane (WLS) ───────────────────────────────────────────────
        val fast = calculateRecentWls(raw)

        return RobustTrendAnalysis(
            first, second, consistency,
            dirConsistency, magConsistency, phase,
            fast.recentSlope, fast.recentDelta5m
        )
    }

    // ── FAST lane: gewogen least-squares ─────────────────────────────────

    private data class RecentRaw(
        val recentSlope: Double,
        val recentDelta5m: Double
    )

    /**
     * Schat de huidige BG-snelheid via gewogen least-squares over de
     * laatste [WLS_POINTS] punten.
     *
     * Recentere punten krijgen hogere gewichten (exponentieel: w_i = α^i
     * waarbij i=0 het meest recent is).
     *
     * De .bg waarden zijn al UKF-gesmoothed via .recalculated, dus WLS
     * hierover benadert de UKF-snelheidsschatting zonder API-uitbreiding.
     *
     * Terugval op 2-punts delta als er minder dan 3 punten beschikbaar zijn.
     */
    private fun calculateRecentWls(data: List<BGPoint>): RecentRaw {
        val n = minOf(data.size, WLS_POINTS)
        if (n < 2) return RecentRaw(0.0, 0.0)

        // Neem de n nieuwste punten (data is gesorteerd oud→nieuw)
        val pts = data.takeLast(n)
        val t0  = pts.last().time.millis  // referentietijd = meest recent punt

        if (n == 2) {
            // Terugval: eenvoudige 2-punts delta
            val dtMin = Minutes.minutesBetween(pts[0].time, pts[1].time).minutes
            if (dtMin <= 0) return RecentRaw(0.0, 0.0)
            val delta    = pts[1].bg - pts[0].bg
            val slopeHr  = delta / (dtMin / 60.0)
            val delta5m  = delta * (5.0 / dtMin)
            return RecentRaw(slopeHr, delta5m)
        }

        // Gewogen least-squares: y = a * t + b, minimaliseer Σ w_i*(y_i - a*t_i - b)²
        // t_i in minuten relatief aan het meest recente punt (t_i <= 0)
        val wDecay = 0.65  // exponentieel gewicht per 5-min interval ouder
        var sw   = 0.0; var swt  = 0.0; var swt2 = 0.0
        var swy  = 0.0; var swty = 0.0

        for ((i, pt) in pts.withIndex()) {
            val dtMin = (pt.time.millis - t0) / 60000.0  // negatief voor oudere punten
            // i=n-1 is het nieuwste punt (weight=1), i=0 is het oudste
            val w = Math.pow(wDecay, (n - 1 - i).toDouble())
            sw   += w
            swt  += w * dtMin
            swt2 += w * dtMin * dtMin
            swy  += w * pt.bg
            swty += w * dtMin * pt.bg
        }

        val det = sw * swt2 - swt * swt
        if (abs(det) < 1e-10) {
            // Singuliere matrix: terugval op simpele delta
            val dtMin = Minutes.minutesBetween(pts.first().time, pts.last().time).minutes
            if (dtMin <= 0) return RecentRaw(0.0, 0.0)
            val delta = pts.last().bg - pts.first().bg
            return RecentRaw(delta / (dtMin / 60.0), delta * 5.0 / dtMin)
        }

        // Helling a in mg/dL/min (of mmol/L/min afhankelijk van invoer)
        val a       = (sw * swty - swt * swy) / det
        val slopeHr = a * 60.0   // omzetten naar per uur
        val delta5m = a * 5.0    // omzetten naar per 5 min

        return RecentRaw(slopeHr, delta5m)
    }

    // ── SLOW lane: EWMA-gewogen slopes ───────────────────────────────────

    /**
     * Bereken segment-slopes en geef ze terug in chronologische volgorde
     * (oudste slope eerst). De EWMA-weging wordt toegepast in [ewmaAverage].
     */
    private fun calculateSlopesEwma(dataChronological: List<BGPoint>): List<Double> {
        val slopes = mutableListOf<Double>()
        for (i in 1 until dataChronological.size) {
            val prev  = dataChronological[i - 1]
            val curr  = dataChronological[i]
            val dtMin = Minutes.minutesBetween(prev.time, curr.time).minutes
            if (dtMin <= 0) continue
            slopes.add((curr.bg - prev.bg) / (dtMin / 60.0))
        }
        return slopes
    }

    /**
     * EWMA-gemiddelde met recency-weging: het laatste element (meest recent)
     * krijgt het hoogste gewicht α, het op-één-na-laatste α*(1-α), etc.
     */
    private fun ewmaAverage(slopes: List<Double>): Double {
        if (slopes.isEmpty()) return 0.0
        val α   = EWMA_ALPHA
        var sum = 0.0
        var w   = 0.0
        var wi  = 1.0
        // Loop van oud naar nieuw, zodat het laatste element gewicht α^0 = wi_start krijgt
        // We herberekenen de gewichten achteraf zodat het nieuwste de hoogste weight heeft
        val weights = DoubleArray(slopes.size) { i -> Math.pow(α, (slopes.size - 1 - i).toDouble()) }
        val wTotal  = weights.sum()
        for ((i, s) in slopes.withIndex()) {
            sum += weights[i] * s
        }
        return sum / wTotal
    }

    // ── Overige helper-functies (ongewijzigd) ─────────────────────────────

    private fun calculateSecondDerivative(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0
        return slopes.zipWithNext { a, b -> b - a }.average()
    }

    private fun calculateDirectionConsistency(slopes: List<Double>): Double {
        if (slopes.isEmpty()) return 0.0
        val signs = slopes.map { sign(it) }.filter { it != 0.0 }
        if (signs.isEmpty()) return 0.0
        val dominant = signs.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return 0.0
        return dominant.value.toDouble() / signs.size
    }

    private fun calculateMagnitudeConsistency(slopes: List<Double>): Double {
        if (slopes.size < 2) return 0.0
        val mags = slopes.map { abs(it) }
        val avg  = mags.average()
        if (avg == 0.0) return 0.0
        return (1.0 - mags.map { abs(it - avg) / avg }.average()).coerceIn(0.0, 1.0)
    }

    private fun determinePhase(first: Double, second: Double, consistency: Double): Phase {
        if (consistency < 0.3) return Phase.UNKNOWN
        return when {
            first >  0.3 && second >  0.1 -> Phase.ACCELERATING_UP
            first < -0.3 && second < -0.1 -> Phase.ACCELERATING_DOWN
            first >  0.2                  -> Phase.RISING
            first < -0.2                  -> Phase.FALLING
            abs(first) < 0.2              -> Phase.STABLE
            else                          -> Phase.UNKNOWN
        }
    }
}
