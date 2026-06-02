package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.utils.DateUtil
import org.joda.time.DateTime
import kotlin.math.max

/**
 * Centrale BG-history provider voor FCL vNext.
 *
 * Single source of truth voor:
 * - determineBasal
 * - FCLvNext input
 * - observatie-learning
 *
 * Alle waarden worden geleverd in mmol/L en gesorteerd (oudste → nieuwste).
 *
 * Gebruikt iobCobCalculator.ads.getBucketedDataTableCopy() zodat de
 * gecalibreerde en gesmoothe BG-waarden (.recalculated) worden gebruikt —
 * identiek aan hoe OpenAPSSMB en OpenAPSAutoISF dat doen.
 *
 * PersistenceLayer BgReading.value bevat de RUWE sensorwaarde vóór
 * calibratie en smoothing. Het gebruik daarvan veroorzaakte een verschil
 * tussen de BG op het mainscreen (gecalibreerd) en de waarde die FCLvNext
 * ontving (ongecalibreerd), met name zichtbaar als de LinearCalibrationPlugin
 * actief is.
 */
class FCLvNextBgHistoryProvider(
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil
) {

    data class BgPoint(
        val time: DateTime,
        val bgMmol: Double
    )

    /**
     * Haal BG-data op: de laatste [hoursBack] uren uit de bucketed data.
     * Gefilterde/gevulde gaten (filledGap=true) worden overgeslagen.
     * Resultaat gesorteerd oudste → nieuwste, in mmol/L.
     */
    fun getLastHours(hoursBack: Int): List<BgPoint> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
            ?: return emptyList()

        val cutoffMs = dateUtil.now() - max(1, hoursBack) * 60L * 60L * 1000L

        return data
            .filter { it.timestamp >= cutoffMs && !it.filledGap && it.recalculated > 39.0 }
            .sortedBy { it.timestamp }
            .map { BgPoint(time = DateTime(it.timestamp), bgMmol = it.recalculated / 18.0) }
    }

    /**
     * Convenience: BG-data tussen twee tijdstippen.
     */
    fun getBetween(start: DateTime, end: DateTime): List<BgPoint> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
            ?: return emptyList()

        return data
            .filter {
                it.timestamp >= start.millis &&
                it.timestamp <= end.millis &&
                !it.filledGap &&
                it.recalculated > 39.0
            }
            .sortedBy { it.timestamp }
            .map { BgPoint(time = DateTime(it.timestamp), bgMmol = it.recalculated / 18.0) }
    }
}
