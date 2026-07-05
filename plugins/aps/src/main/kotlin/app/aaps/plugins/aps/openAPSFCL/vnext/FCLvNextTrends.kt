package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import org.joda.time.Minutes
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * FCLvNextTrends — trendberekening voor FCL vNext.
 *
 * Drie rijstroken:
 *   SLOW lane: EWMA met recency-weging over de laatste ~30 min.
 *              Levert: firstDerivative (slope mmol/h), secondDerivative (acceleratie),
 *              consistency (0-1 signaalbetrouwbaarheid).
 *   FAST lane: gewogen least-squares over de laatste 4-5 punten.
 *              Levert: recentSlope (mmol/h), recentDelta5m (mmol/5min).
 *   CURVE-FIT lane: kwadratische regressie over ruwe BG, laatste ~45 min
 *              (toegevoegd 04/07/2026, Ecko — geïnspireerd op AutoISF).
 *              Levert: curveFitR2 (0-1 fit-kwaliteit), curveAcceleration
 *              (mmol/h² uit de parabool). Gebruikt door FCLvNext.kt om
 *              peakPressureBonus en late-commit-decay EERDER te laten
 *              reageren bij een bevestigd schoon signaal — nooit om de
 *              dosering tijdens een stijging te vertragen.
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
        val recentDelta5m: Double,          // mmol/L per 5 min (genormaliseerd)

        // CURVE-FIT lane (kwadratische regressie over ruwe BG, laatste ~45 min)
        // Meet iets anders dan `consistency` hierboven: consistency kijkt naar
        // richting/grootte-overeenstemming tussen los-berekende segment-slopes,
        // curveFitR2 kijkt naar hoe goed ÉÉN vloeiende curve de hele periode
        // beschrijft. Beide kunnen dus onafhankelijk hoog/laag zijn — vandaar
        // een apart veld i.p.v. hergebruik van consistency. (04/07/2026, Ecko)
        val curveFitR2: Double,             // 0..1 — fit-kwaliteit van de parabool
        val curveAcceleration: Double       // mmol/L per uur² — uit de parabool-fit
    )

    enum class Phase {
        RISING, FALLING, STABLE,
        ACCELERATING_UP, ACCELERATING_DOWN,
        UNKNOWN
    }

    // EWMA decay-factor voor de SLOW lane: α=0.70 betekent dat het meest
    // recente segment ~70% gewicht heeft, het op-één-na-recentste ~21%, etc.
    // — MITS de cyclus/CGM-interval regelmatig 5 minuten is. Zie EWMA_TAU_MINUTES
    // hieronder voor waarom dat een verborgen aanname was die niet klopte bij
    // gaps (05/07/2026, Ecko — n.a.v. openAPSBoost's tijd-bewuste EMA-alpha).
    private const val EWMA_ALPHA = 0.70

    // ── Tijd-bewuste EWMA (05/07/2026, Ecko) ─────────────────────────────
    // PROBLEEM: ewmaAverage() gewicht was voorheen α^(stappen_geleden) — dus
    // gebaseerd op de POSITIE in de segmentenlijst, niet op werkelijk verstreken
    // tijd. Bij een regelmatig 5-min-interval is dat equivalent (stap 1 geleden
    // = 5 min geleden), maar bij een CGM-gap (bijv. 15 min i.p.v. 5 min tussen
    // twee punten — en dat gebeurt binnen de bestaande MAX_GAP-tolerantie,
    // met name op de FSL-2/Juggluco-opstelling) kreeg een segment van 15
    // minuten oud precies hetzelfde gewicht als een segment van 5 minuten oud.
    // Bij een korte cluster van korte gaps zou dat oude data juist te zwaar
    // laten meewegen; bij aaneengesloten korte intervallen te licht.
    //
    // OPLOSSING: gewicht = exp(-minutenGeleden / τ) i.p.v. α^stappenGeleden —
    // zelfde patroon als openAPSBoost's BoostIsfShadow (alpha = 1 - exp(-dt/tau)
    // voor een lopende EMA; hier voor een vers-herberekende gewogen som is
    // exp(-dt/τ) de directe tegenhanger). τ is zo afgeleid dat het gedrag bij
    // een perfect regelmatig 5-min-interval EXACT hetzelfde blijft als voorheen
    // (α^1 bij 5 min ⇔ exp(-5/τ) = α ⇒ τ = -5/ln(α) ≈ 14.0 minuten) — dit is
    // dus geen gedragswijziging voor de normale situatie, alleen een correctie
    // voor wanneer de aanname van regelmatige intervallen niet klopt.
    private val EWMA_TAU_MINUTES = -5.0 / ln(EWMA_ALPHA)

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
            return RobustTrendAnalysis(0.0, 0.0, 0.0, 0.0, 0.0, Phase.UNKNOWN, 0.0, 0.0, 0.0, 0.0)
        }

        // ── SLOW lane (EWMA-gewogen) ──────────────────────────────────────
        val timedSlopes = calculateSlopesEwma(filtered)
        val slopeValues = timedSlopes.map { it.value }
        val first  = if (timedSlopes.isNotEmpty()) ewmaAverage(timedSlopes) else 0.0
        val second = calculateSecondDerivative(slopeValues)

        val dirConsistency = calculateDirectionConsistency(slopeValues)
        val magConsistency = calculateMagnitudeConsistency(slopeValues)
        val consistency    = (0.6 * dirConsistency + 0.4 * magConsistency).coerceIn(0.0, 1.0)
        val phase          = determinePhase(first, second, consistency)

        // ── FAST lane (WLS) ───────────────────────────────────────────────
        val fast = calculateRecentWls(raw)

        // ── CURVE-FIT lane (kwadratische regressie, AutoISF-achtige aanpak) ─
        val curve = calculateParabolaFit(raw)

        return RobustTrendAnalysis(
            first, second, consistency,
            dirConsistency, magConsistency, phase,
            fast.recentSlope, fast.recentDelta5m,
            curve.r2, curve.accelerationPerHour2
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

    // ── CURVE-FIT lane: kwadratische regressie over ruwe BG ──────────────
    //
    // Toegevoegd 04/07/2026 (Ecko): geïnspireerd op de parabool-fit uit
    // AutoISF (GlucoseStatusCalculatorAutoIsf.kt), maar met eigen doel:
    // een fit-kwaliteitssignaal (R²) dat aangeeft hoe overtuigend één
    // vloeiende curve de laatste ~45 minuten ruwe BG beschrijft.
    //
    // Waarom niet de bestaande `consistency` hergebruiken?
    // - `consistency` meet richting/grootte-overeenstemming tussen LOSSE
    //   segment-slopes (discreet, robuust tegen 1 rare punt, maar "blind"
    //   voor een schone maar sterk kromme curve — een vloeiend versnellende
    //   stijging kan een matige segment-consistency geven omdat elke slope
    //   net wat groter is dan de vorige).
    // - `curveFitR2` meet hoe goed ÉÉN curve door de hele reeks past — hoog
    //   bij een schone, vloeiende curve (op- of neergaand, versnellend of
    //   vertragend), laag bij ruis, sensor-hikjes of een echte koerswending
    //   halverwege het venster.
    // Beide signalen zijn dus complementair, niet redundant: dit veld wordt
    // uitsluitend gebruikt om twee bestaande mechanismen (peakPressureBonus
    // en de late-commit-decay) EERDER te laten reageren bij een bevestigd
    // schoon signaal — het vervangt geen bestaande gate en verlaagt nooit
    // de dosering tijdens een actieve stijging (zie FCLvNext.kt).
    //
    // Venster: max 45 minuten terug, fit stopt bij een CGM-gap > 13 minuten
    // (zelfde grens als de bestaande MAX_GAP-tolerantie elders in FCLvNext).
    // Minimaal 4 punten nodig voor een zinnige kwadratische fit.
    private const val CURVE_FIT_WINDOW_MIN = 45.0
    private const val CURVE_FIT_GAP_MIN = 13.0
    private const val CURVE_FIT_MIN_POINTS = 4

    private data class ParabolaFit(
        val r2: Double,                  // 0..1
        val accelerationPerHour2: Double // mmol/L per uur² (= 2 * a, omgerekend)
    )

    private fun calculateParabolaFit(rawChronological: List<BGPoint>): ParabolaFit {
        // Nieuw → oud, zodat we van het huidige punt teruglopen en op tijd
        // kunnen stoppen bij het venster of een gap (zelfde patroon als AutoISF).
        val data = rawChronological.sortedByDescending { it.time.millis }
        if (data.size < CURVE_FIT_MIN_POINTS) return ParabolaFit(0.0, 0.0)

        val t0 = data[0].time.millis
        var sx = 0.0; var sx2 = 0.0; var sx3 = 0.0; var sx4 = 0.0
        var sy = 0.0; var sxy = 0.0; var sx2y = 0.0
        var n = 0
        var prevTMin = 0.0

        for ((i, pt) in data.withIndex()) {
            val tMin = (pt.time.millis - t0) / 60000.0   // <= 0, ouder = negatiever
            if (-tMin > CURVE_FIT_WINDOW_MIN) break
            if (i > 0 && (prevTMin - tMin) > CURVE_FIT_GAP_MIN) break   // CGM-gap: stop de fit
            prevTMin = tMin
            n += 1
            sx += tMin; sx2 += tMin * tMin; sx3 += tMin * tMin * tMin; sx4 += tMin * tMin * tMin * tMin
            sy += pt.bg; sxy += tMin * pt.bg; sx2y += tMin * tMin * pt.bg
        }
        if (n < CURVE_FIT_MIN_POINTS) return ParabolaFit(0.0, 0.0)

        // y = a*t² + b*t + c — normaalvergelijkingen via determinanten
        // (zelfde opzet als GlucoseStatusCalculatorAutoIsf.kt, hier met
        // BG in mmol/L en tijd in minuten i.p.v. geschaalde eenheden).
        val detH = sx4 * (sx2 * n - sx * sx) - sx3 * (sx3 * n - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
        if (abs(detH) < 1e-9) return ParabolaFit(0.0, 0.0)

        val detA = sx2y * (sx2 * n - sx * sx) - sxy * (sx3 * n - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)
        val detB = sx4 * (sxy * n - sy * sx) - sx3 * (sx2y * n - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)
        val detC = sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)

        val a = detA / detH
        val b = detB / detH
        val c = detC / detH

        // R² over dezelfde n punten die in de fit zaten
        val yMean = sy / n
        var ssTot = 0.0
        var ssRes = 0.0
        for (j in 0 until n) {
            val pt = data[j]
            val tMin = (pt.time.millis - t0) / 60000.0
            val yFit = a * tMin * tMin + b * tMin + c
            ssTot += (pt.bg - yMean) * (pt.bg - yMean)
            ssRes += (pt.bg - yFit) * (pt.bg - yFit)
        }
        val r2 = if (ssTot > 1e-9) (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0

        // d²y/dt² = 2a (per minuut²) → omrekenen naar per uur²: × 60²
        val accelerationPerHour2 = 2.0 * a * 3600.0

        return ParabolaFit(r2, accelerationPerHour2)
    }

    // ── SLOW lane: EWMA-gewogen slopes ───────────────────────────────────

    /**
     * Eén segment-slope met het tijdstip van het NIEUWSTE punt in dat segment
     * (nodig om straks, t.o.v. het allerlaatste segment, de werkelijk verstreken
     * tijd te kunnen bepalen — niet de positie in de lijst).
     */
    private data class TimedSlope(val value: Double, val endTime: DateTime)

    /**
     * Bereken segment-slopes en geef ze terug in chronologische volgorde
     * (oudste slope eerst), elk met het tijdstip van het segment-eindpunt.
     */
    private fun calculateSlopesEwma(dataChronological: List<BGPoint>): List<TimedSlope> {
        val slopes = mutableListOf<TimedSlope>()
        for (i in 1 until dataChronological.size) {
            val prev  = dataChronological[i - 1]
            val curr  = dataChronological[i]
            val dtMin = Minutes.minutesBetween(prev.time, curr.time).minutes
            if (dtMin <= 0) continue
            slopes.add(TimedSlope((curr.bg - prev.bg) / (dtMin / 60.0), curr.time))
        }
        return slopes
    }

    /**
     * EWMA-gemiddelde met tijd-bewuste recency-weging: gewicht = exp(-Δt/τ),
     * waarbij Δt de werkelijk verstreken tijd (in minuten) is tussen dit
     * segment en het meest recente segment — niet de positie in de lijst.
     * Zie EWMA_TAU_MINUTES hierboven voor de afleiding.
     */
    private fun ewmaAverage(slopes: List<TimedSlope>): Double {
        if (slopes.isEmpty()) return 0.0
        val newestTime = slopes.last().endTime
        var sum = 0.0
        var wTotal = 0.0
        for (s in slopes) {
            val minutesAgo = Minutes.minutesBetween(s.endTime, newestTime).minutes.toDouble()
            val weight = exp(-minutesAgo / EWMA_TAU_MINUTES)
            sum += weight * s.value
            wTotal += weight
        }
        return if (wTotal > 0.0) sum / wTotal else 0.0
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
