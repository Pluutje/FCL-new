package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import kotlin.math.abs
import kotlin.math.sign

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const val MIN_ENTRIES_FOR_SPLINE = 4

/**
 * Splitspunt laag/hoog segment: 6 mmol/L = 108 mg/dL.
 * Punten met sensor <= SPLIT vallen in het lage segment,
 * punten met sensor > SPLIT in het hoge segment.
 */
const val SPLINE_SPLIT_MGDL = 108.0

/**
 * Minimaal aantal punten per segment voor een betrouwbare regressie.
 * Met minder punten is de segment-slope onbetrouwbaar.
 */
const val MIN_POINTS_PER_SEGMENT = 2

/**
 * Minimale spreiding van sensorwaarden per segment (mg/dL).
 * Onder dit niveau is de slope niet te onderscheiden van ruis
 * en wordt een vaste slope van 1.0 gebruikt voor dat segment.
 */
const val MIN_SEGMENT_RANGE_MGDL = 18.0  // 1 mmol

// ── Zachte segment-grens rond het knooppunt (26/07/2026, Ecko) ──────────
// AANLEIDING: één nieuw fingerstick-punt vlak bij SPLINE_SPLIT_MGDL (sensor
// 6,7 mmol, vlak boven de 6,0-mmol-grens) veroorzaakte een onwenselijke,
// scherpe knik in de curve. Oorzaak: de segment-toewijzing was een harde
// grens (sensor<=SPLIT → laag, anders → hoog) — een punt vlak bij de grens
// stemt met zijn volle gewicht op ÉÉN kant, terwijl dat segment soms maar
// 2-3 punten telt (MIN_POINTS_PER_SEGMENT) en het nieuwste punt door de
// recency-weging (weightFor) toch al zwaar meetelt. Eén punt kon zo in zijn
// eentje de centroid/slope van een heel segment omgooien.
//
// FIX: binnen SPLIT_BLEND_HALF_WIDTH_MGDL aan weerszijden van de grens telt
// een punt met een glooiend gewicht mee voor BEIDE segmenten (bijv. een punt
// precies op de grens telt 50/50), in plaats van 100% voor het ene en 0%
// voor het andere. Dit temeprt hoeveel een enkel grenspunt één segment kan
// domineren, zonder de bestaande "genoeg punten/spreiding"-gates
// (MIN_POINTS_PER_SEGMENT, MIN_SEGMENT_RANGE_MGDL — zie fitSplineCalibration
// / fitSegmentSpline) te wijzigen: die blijven op de harde laag/hoog-split
// gebaseerd, alleen de curve-wiskunde zelf (centroid + slope) is verzacht.
const val SPLIT_BLEND_HALF_WIDTH_MGDL = 18.0  // 1 mmol elke kant, zelfde marge als MIN_SEGMENT_RANGE_MGDL

/**
 * Lidmaatschapsgewicht "laag segment" voor een punt met sensorwaarde
 * [sensorMgdl]: 1.0 ruim onder de grens, 0.0 ruim erboven, lineair glooiend
 * in de band [SPLINE_SPLIT_MGDL] ± [SPLIT_BLEND_HALF_WIDTH_MGDL] — dus 0,5
 * precies op de grens zelf. "Hoog segment"-lidmaatschap is 1.0 minus dit.
 */
internal fun lowSegmentMembership(sensorMgdl: Double): Double {
    val lo = SPLINE_SPLIT_MGDL - SPLIT_BLEND_HALF_WIDTH_MGDL
    val hi = SPLINE_SPLIT_MGDL + SPLIT_BLEND_HALF_WIDTH_MGDL
    return when {
        sensorMgdl <= lo -> 1.0
        sensorMgdl >= hi -> 0.0
        else -> (hi - sensorMgdl) / (hi - lo)
    }
}

// ---------------------------------------------------------------------------
// Public data types
// ---------------------------------------------------------------------------

/**
 * Enkelvoudige monotone cubic Hermite spline tussen het lage en hoge segment.
 *
 * Architectuur:
 *   - Laag segment  (sensor <= cx_low):  lineaire extrapolatie met slope_low
 *   - Overgangszone (cx_low..cx_high):   Hermite-curve met tangenten slope_low en slope_high
 *   - Hoog segment  (sensor >= cx_high): lineaire extrapolatie met slope_high
 *
 * slope_low en slope_high komen elk uit een onafhankelijke gewogen lineaire
 * regressie op de punten in het respectieve segment. Hierdoor weerspiegelen
 * de tangenten direct de sensor-karakteristiek in dat bereik, onafhankelijk
 * van de globale fit of een willekeurig knooppuntniveau.
 *
 * knotX / knotY zijn het punt op de curve bij SPLINE_SPLIT_MGDL —
 * uitsluitend voor informatieve weergave in de UI (knot correction).
 */
data class SplineFit(
    val knotX: Double,
    val knotY: Double,

    val cx_low:  Double, val cy_low:  Double, val slope_low:  Double,
    val cx_high: Double, val cy_high: Double, val slope_high: Double,

    val linearFallback: CalibrationFit
) {
    fun apply(sensorMgdl: Double, manualOffsetMgdl: Double = 0.0): Double {
        val fitted = when {
            sensorMgdl <= cx_low  -> cy_low  + slope_low * (sensorMgdl - cx_low)
            sensorMgdl >= cx_high -> extrapolateAboveHigh(sensorMgdl)
            else -> hermite(sensorMgdl, cx_low, cy_low, slope_low,
                            cx_high, cy_high, slope_high)
        }
        return fitted + manualOffsetMgdl
    }

    /**
     * Extrapolatie boven cx_high, met een veiligheidsvloer van slope 1.0 —
     * de gecalibreerde waarde mag nooit afvlakken bij verder stijgende
     * sensorwaarden (slope_high < 1.0 reflecteert sensor-compressie in de
     * data, maar mag de correctie niet laten afbuigen).
     *
     * BUGFIX (21/06/2026, Ecko): de vloer werd voorheen instant toegepast
     * (`slope_high.coerceAtLeast(1.0)`) — bij elke fit met slope_high < 1.0
     * (toegestaan, SLOPE_MIN=0.55) sprong de afgeleide dan abrupt van
     * slope_high naar 1.0 precies op cx_high: de waarde liep door, maar de
     * helling kneep — een zichtbare knik in de grafiek ("rare afbuigingen").
     * Nu vloeit de slope, als slope_high < 1.0, geleidelijk op naar 1.0 over
     * BLEND_WIDTH_MGDL — waarde én afgeleide sluiten bij cx_high naadloos
     * aan op de Hermite-curve (geen knik meer), en pas voorbij de
     * overgangszone geldt de volledige veiligheidsvloer.
     */
    private fun extrapolateAboveHigh(sensorMgdl: Double): Double {
        val dx = sensorMgdl - cx_high
        if (slope_high >= 1.0) {
            // Geen vloer nodig — eigen slope kan nooit een knik geven.
            return cy_high + slope_high * dx
        }
        val blend = BLEND_WIDTH_MGDL
        return if (dx <= blend) {
            // Lineair oplopende slope van slope_high (bij dx=0) naar 1.0
            // (bij dx=blend), geïntegreerd → kwadratisch stuk. Waarde en
            // afgeleide sluiten bij dx=0 exact aan op de Hermite-tak.
            cy_high + slope_high * dx + 0.5 * (1.0 - slope_high) / blend * dx * dx
        } else {
            // Voorbij de overgangszone: rechte lijn met slope 1.0, aansluitend
            // op het eindpunt van de overgangszone (waarde én afgeleide).
            val blendEndY = cy_high + slope_high * blend + 0.5 * (1.0 - slope_high) * blend
            blendEndY + 1.0 * (dx - blend)
        }
    }

    val correctionAtKnot: Double get() = knotY - knotX
    val hasTwoKnots: Boolean get() = false
}

/** Breedte (mg/dL) van de overgangszone waarin de veiligheidsvloer-slope
 *  geleidelijk wordt bereikt — zie kdoc bij [SplineFit.extrapolateAboveHigh].
 *  36 mg/dL = 2 mmol/L. */
private const val BLEND_WIDTH_MGDL = 36.0

// ---------------------------------------------------------------------------
// Public fit function
// ---------------------------------------------------------------------------

/**
 * Reden waarom [fitSplineCalibration] is teruggevallen op de lineaire fit.
 * Toegevoegd 21/06/2026 (Ecko): de UI toonde voorheen altijd "need 4 entries
 * for spline" zodra de spline niet lukte — ook als er allang ≥4 punten waren
 * maar bijvoorbeeld alle punten in hetzelfde segment (laag óf hoog t.o.v.
 * SPLINE_SPLIT_MGDL) vielen. Met een verse sensorsessie waarbij vooral in
 * het begin rond een vergelijkbare BG wordt gekalibreerd, is dat juist de
 * meest voorkomende reden — niet "te weinig punten in totaal".
 */
enum class SplineFailureReason {
    /** < MIN_ENTRIES_FOR_SPLINE punten in totaal. */
    TOO_FEW_ENTRIES,
    /** ≥ MIN_ENTRIES_FOR_SPLINE in totaal, maar < MIN_POINTS_PER_SEGMENT in het
     *  segment sensor ≤ SPLINE_SPLIT_MGDL. */
    TOO_FEW_LOW_SEGMENT,
    /** ≥ MIN_ENTRIES_FOR_SPLINE in totaal, maar < MIN_POINTS_PER_SEGMENT in het
     *  segment sensor > SPLINE_SPLIT_MGDL. */
    TOO_FEW_HIGH_SEGMENT,
    /** Eén van beide segment-slopes valt buiten [SLOPE_MIN, SLOPE_MAX]. */
    SLOPE_OUT_OF_RANGE,
    /** De segment-centroïden liggen te dicht bij elkaar (< MIN_SEGMENT_RANGE_MGDL
     *  span) voor een numeriek stabiele overgangszone. */
    SEGMENTS_TOO_CLOSE,
    /** De Hermite-curve zou tussen de segmenten niet monotoon stijgend zijn. */
    NOT_MONOTONE
}

/**
 * @return het succesvolle [SplineFit], of `null` met de reden in [SplineFitResult.reason]
 *         wanneer is teruggevallen op lineair.
 */
data class SplineFitResult(
    val fit: SplineFit?,
    val reason: SplineFailureReason?
)

fun fitSplineCalibration(entries: List<CAL>, now: Long): SplineFitResult {
    if (entries.size < MIN_ENTRIES_FOR_SPLINE)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_ENTRIES)

    val linear = fitLinearCalibration(entries, now)
        ?: return SplineFitResult(null, SplineFailureReason.TOO_FEW_ENTRIES)

    val low  = entries.filter { it.sensorMgdlAtPairing <= SPLINE_SPLIT_MGDL }
    val high = entries.filter { it.sensorMgdlAtPairing >  SPLINE_SPLIT_MGDL }

    // Beide segmenten moeten voldoende punten hebben voor een betrouwbare fit.
    // Dit, niet "te weinig punten in totaal", is in de praktijk de meest
    // voorkomende reden voor fallback vroeg in een sensorsessie.
    if (low.size  < MIN_POINTS_PER_SEGMENT)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_LOW_SEGMENT)
    if (high.size < MIN_POINTS_PER_SEGMENT)
        return SplineFitResult(null, SplineFailureReason.TOO_FEW_HIGH_SEGMENT)

    return fitSegmentSpline(entries, low, high, linear, now)
}

// ---------------------------------------------------------------------------
// Segment-spline fit
// ---------------------------------------------------------------------------

private fun fitSegmentSpline(
    entries: List<CAL>,
    low:    List<CAL>,
    high:   List<CAL>,
    linear: CalibrationFit,
    now:    Long
): SplineFitResult {
    // Gewogen centroiden per segment — zachte lidmaatschapsgrens (zie kdoc
    // bij SPLIT_BLEND_HALF_WIDTH_MGDL): rekent over ALLE entries, met
    // gewicht 0 voor punten ruim buiten dat segment, dus effectief
    // gelijkwaardig aan de oude harde split behalve vlak bij de grens.
    val (cx_low,  cy_low)  = weightedCentroid(entries, now) { lowSegmentMembership(it.sensorMgdlAtPairing) }
    val (cx_high, cy_high) = weightedCentroid(entries, now) { 1.0 - lowSegmentMembership(it.sensorMgdlAtPairing) }

    // Segment-specifieke gewogen lineaire fits voor de tangenten, zelfde
    // zachte lidmaatschap. De spreidingscheck (MIN_SEGMENT_RANGE_MGDL) blijft
    // op de harde laag/hoog-lijst gebaseerd (low/high) — die beoordeelt "is
    // er genoeg spreiding om deze segment-slope te vertrouwen", niet de
    // curve-wiskunde zelf, en moet dus niet meebewegen met de zachte grens.
    val slope_low  = segmentSlope(entries, low,  now) { lowSegmentMembership(it.sensorMgdlAtPairing) } ?: linear.slope
    val slope_high = segmentSlope(entries, high, now) { 1.0 - lowSegmentMembership(it.sensorMgdlAtPairing) } ?: linear.slope

    // Beide slopes moeten fysiek plausibel zijn.
    if (slope_low  < SLOPE_MIN || slope_low  > SLOPE_MAX)
        return SplineFitResult(null, SplineFailureReason.SLOPE_OUT_OF_RANGE)
    if (slope_high < SLOPE_MIN || slope_high > SLOPE_MAX)
        return SplineFitResult(null, SplineFailureReason.SLOPE_OUT_OF_RANGE)

    // De overgangszone moet breed genoeg zijn voor numerieke stabiliteit.
    val span = cx_high - cx_low
    if (span < MIN_SEGMENT_RANGE_MGDL)
        return SplineFitResult(null, SplineFailureReason.SEGMENTS_TOO_CLOSE)

    // Monotonie: de Hermite-curve mag niet dalen tussen cx_low en cx_high.
    if (!isMonotoneIncreasing(cx_low, cy_low, slope_low, cx_high, cy_high, slope_high))
        return SplineFitResult(null, SplineFailureReason.NOT_MONOTONE)

    // Knooppuntwaarde voor display (evalueer Hermite bij SPLINE_SPLIT_MGDL).
    val knotX = SPLINE_SPLIT_MGDL
    val knotY = when {
        knotX <= cx_low  -> cy_low  + slope_low  * (knotX - cx_low)
        knotX >= cx_high -> cy_high + slope_high * (knotX - cx_high)
        else -> hermite(knotX, cx_low, cy_low, slope_low, cx_high, cy_high, slope_high)
    }

    return SplineFitResult(
        SplineFit(
            knotX      = knotX,
            knotY      = knotY,
            cx_low     = cx_low,  cy_low  = cy_low,  slope_low  = slope_low,
            cx_high    = cx_high, cy_high = cy_high, slope_high = slope_high,
            linearFallback = linear
        ),
        reason = null
    )
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Gewogen lineaire regressie voor één segment → slope.
 * Geeft null als de sensorwaarden te dicht bij elkaar liggen (< MIN_SEGMENT_RANGE_MGDL).
 *
 * [rangeCheckEntries] is de harde laag/hoog-lijst — alleen voor de "genoeg
 * spreiding"-check. [allEntries] is de volledige lijst waarover daadwerkelijk
 * wordt gesommeerd, gewogen met [membershipFor] (zachte grens, zie kdoc bij
 * SPLIT_BLEND_HALF_WIDTH_MGDL) — punten met lidmaatschap 0 tellen niet mee.
 */
private fun segmentSlope(
    allEntries: List<CAL>,
    rangeCheckEntries: List<CAL>,
    now: Long,
    membershipFor: (CAL) -> Double
): Double? {
    val sensorRange = rangeCheckEntries.maxOf { it.sensorMgdlAtPairing } -
        rangeCheckEntries.minOf { it.sensorMgdlAtPairing }
    if (sensorRange < MIN_SEGMENT_RANGE_MGDL) return null

    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    var sumWXX = 0.0; var sumWXY = 0.0
    for (e in allEntries) {
        val m = membershipFor(e)
        if (m <= 0.0) continue
        val w = weightFor(e.timestamp, now) * m
        val x = e.sensorMgdlAtPairing
        val y = e.fingerstickMgdl
        sumW += w; sumWX += w*x; sumWY += w*y; sumWXX += w*x*x; sumWXY += w*x*y
    }
    val denom = sumW * sumWXX - sumWX * sumWX
    if (abs(denom) < 1e-9) return null
    return (sumW * sumWXY - sumWX * sumWY) / denom
}

private fun hermite(
    x: Double,
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Double {
    val h = x1 - x0
    if (h == 0.0) return y0
    val t  = (x - x0) / h
    val t2 = t * t
    val t3 = t2 * t
    val h00 =  2*t3 - 3*t2 + 1
    val h10 =    t3 - 2*t2 + t
    val h01 = -2*t3 + 3*t2
    val h11 =    t3 -   t2
    return h00*y0 + h10*h*m0 + h01*y1 + h11*h*m1
}

/**
 * Gewogen centroïde van een segment. [membershipFor] is 1.0 (het oude,
 * ongewijzigde gedrag) tenzij een zachte grens is opgegeven — zie kdoc bij
 * SPLIT_BLEND_HALF_WIDTH_MGDL. sumW==0 kan in de praktijk niet voorkomen
 * zolang [entries] minstens de MIN_POINTS_PER_SEGMENT harde-split-punten
 * bevat (die hebben altijd lidmaatschap >= 0,5 voor hun eigen kant), maar
 * de fallback op entries[0] blijft als veiligheidsnet staan.
 */
private fun weightedCentroid(
    entries: List<CAL>,
    now: Long,
    membershipFor: (CAL) -> Double = { 1.0 }
): Pair<Double, Double> {
    var sumW = 0.0; var sumWX = 0.0; var sumWY = 0.0
    for (e in entries) {
        val m = membershipFor(e)
        if (m <= 0.0) continue
        val w = weightFor(e.timestamp, now) * m
        sumW += w; sumWX += w * e.sensorMgdlAtPairing; sumWY += w * e.fingerstickMgdl
    }
    return if (sumW == 0.0) entries[0].sensorMgdlAtPairing to entries[0].fingerstickMgdl
    else (sumWX / sumW) to (sumWY / sumW)
}

private fun isMonotoneIncreasing(
    x0: Double, y0: Double, m0: Double,
    x1: Double, y1: Double, m1: Double
): Boolean {
    val h = x1 - x0
    if (h <= 0.0) return false
    val delta = (y1 - y0) / h
    if (delta < 0.0) return false
    if (delta == 0.0) return m0 == 0.0 && m1 == 0.0
    val alpha = m0 / delta
    val beta  = m1 / delta
    return alpha * alpha + beta * beta <= 9.0 + 1e-9
}

fun applyBestFit(sensorMgdl: Double, spline: SplineFit?, linear: CalibrationFit?, manualOffsetMgdl: Double = 0.0): Double? {
    if (spline != null) return spline.apply(sensorMgdl, manualOffsetMgdl)
    if (linear != null && linear.isApplicable) return linear.slope * sensorMgdl + linear.offset + manualOffsetMgdl
    return null
}