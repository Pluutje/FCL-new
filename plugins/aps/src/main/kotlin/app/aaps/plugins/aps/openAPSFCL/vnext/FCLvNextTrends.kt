package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import org.joda.time.Minutes
import kotlin.math.abs
import kotlin.math.sign

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

        // FAST lane (RAW CGM)
        val recentSlope: Double,            // mmol/L per uur (laatste segment)
        val recentDelta5m: Double           // mmol/L per 5 min (genormaliseerd)
    )

    enum class Phase {
        RISING,
        FALLING,
        STABLE,
        ACCELERATING_UP,
        ACCELERATING_DOWN,
        UNKNOWN
    }

    fun calculateTrends(
        rawData: List<BGPoint>,
        filteredData: List<BGPoint>
    ): RobustTrendAnalysis {

        // ✅ Forceer chronologische volgorde (oud → nieuw)
        val raw = rawData.sortedBy { it.time.millis }
        val filtered = filteredData.sortedBy { it.time.millis }

        if (filtered.size < 5 || raw.size < 2) {
            return RobustTrendAnalysis(
                0.0, 0.0, 0.0, 0.0, 0.0, Phase.UNKNOWN, 0.0, 0.0
            )
        }

        // ── SLOW lane (EWMA) ──
        val slopes = calculateSlopes(filtered)
        val first = if (slopes.isNotEmpty()) slopes.average() else 0.0
        val second = calculateSecondDerivative(slopes)

        val dirConsistency = calculateDirectionConsistency(slopes)
        val magConsistency = calculateMagnitudeConsistency(slopes)

        val consistency =
            (0.6 * dirConsistency + 0.4 * magConsistency)
                .coerceIn(0.0, 1.0)

        val phase = determinePhase(first, second, consistency)

        // ── FAST lane (RAW CGM) ──
        val fast = calculateRecentRaw(raw)

        return RobustTrendAnalysis(
            first, second, consistency,
            dirConsistency, magConsistency, phase,
            fast.recentSlope, fast.recentDelta5m
        )
    }

    private data class RecentRaw(
        val recentSlope: Double,
        val recentDelta5m: Double
    )

    private fun calculateRecentRaw(data: List<BGPoint>): RecentRaw {
        if (data.size < 2) return RecentRaw(0.0, 0.0)

        // ✅ Pak de nieuwste 2 punten (na sorting is dit echt CGM-last-two)
        val b = data.last()
        val a = data[data.size - 2]

        val dtMin = Minutes.minutesBetween(a.time, b.time).minutes
        if (dtMin <= 0) return RecentRaw(0.0, 0.0)

        val delta = b.bg - a.bg                 // exact wat jij op CGM ziet
        val slopeHr = delta / (dtMin / 60.0)
        val delta5m = delta * (5.0 / dtMin.toDouble())

        return RecentRaw(
            recentSlope = slopeHr,
            recentDelta5m = delta5m
        )
    }

    private fun calculateSlopes(dataChronological: List<BGPoint>): List<Double> {
        val slopes = mutableListOf<Double>()

        for (i in 1 until dataChronological.size) {
            val prev = dataChronological[i - 1]
            val curr = dataChronological[i]

            val dtMin = Minutes.minutesBetween(prev.time, curr.time).minutes
            if (dtMin <= 0) continue

            val delta = curr.bg - prev.bg
            val slopeHr = delta / (dtMin / 60.0)
            slopes.add(slopeHr)
        }

        return slopes
    }

    private fun calculateSecondDerivative(slopes: List<Double>): Double {
        // ✅ Voorkom NaN als slopes < 2
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
        val avg = mags.average()
        if (avg == 0.0) return 0.0
        return (1.0 - mags.map { abs(it - avg) / avg }.average())
            .coerceIn(0.0, 1.0)
    }

    private fun determinePhase(first: Double, second: Double, consistency: Double): Phase {
        if (consistency < 0.3) return Phase.UNKNOWN
        return when {
            first > 0.3 && second > 0.1 -> Phase.ACCELERATING_UP
            first < -0.3 && second < -0.1 -> Phase.ACCELERATING_DOWN
            first > 0.2 -> Phase.RISING
            first < -0.2 -> Phase.FALLING
            abs(first) < 0.2 -> Phase.STABLE
            else -> Phase.UNKNOWN
        }
    }
}
