package app.aaps.plugins.aps.openAPSFCL.vnext.model

/**
 * Berekent hoeveel extra IOB tijdelijk is toegestaan.
 *
 * @return factor in range [1.0 .. 1.10]
 */
fun computeIobOvershootFactor(
    smoothedSlope: Double,     // ctx.slope
    deltaToTarget: Double,     // ctx.deltaToTarget
    maxOvershootPct: Double    // bijv. 0.10 = 10%
): Double {

    // Geen stijging of nauwelijks boven target → geen overshoot
    if (smoothedSlope <= 0.0 || deltaToTarget <= 0.5) {
        return 1.0
    }

    // Normaliseer slope (typisch bereik ~0.3 .. 1.5 mmol/L/h)
    val slopeNorm =
        (smoothedSlope / 1.2).coerceIn(0.0, 1.0)

    // Normaliseer hoogte boven target (typisch 1 .. 5 mmol)
    val deltaNorm =
        (deltaToTarget / 5.0).coerceIn(0.0, 1.0)

    // Combineer: structurele stijging weegt zwaarder dan hoogte
    val strength =
        (0.7 * slopeNorm + 0.3 * deltaNorm)
            .coerceIn(0.0, 1.0)

    return 1.0 + strength * maxOvershootPct
}

/**
 * Clamp dose zodat IOB + dose niet boven toegestaan maximum komt.
 */
fun clampDoseByIob(
    commandedDose: Double,
    currentIob: Double,
    maxIob: Double,
    overshootFactor: Double
): Double {

    val allowedIob =
        maxIob * overshootFactor

    val headroom =
        (allowedIob - currentIob).coerceAtLeast(0.0)

    return minOf(commandedDose, headroom)
}
