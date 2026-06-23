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
     * Gefilterde/gevulde gaten worden alleen overgeslagen als het gat
     * meer dan MAX_GAP_MINUTES minuten omvat (lange interpolatie = onbetrouwbaar).
     * Kortere gaten (≤ MAX_GAP_MINUTES, zoals één gemiste Libre-scan of een
     * kleine NFC/BLE-overlap die AAPS als filledGap markeert) worden
     * meegenomen — de trendberekening gebruikt actuele tijdstempels en
     * schaalt er correct mee.
     *
     * Achtergrond (23/06/2026, Ecko): bij FSL-2 via Juggluco→xDrip+ ontvangt
     * AAPS soms twee metingen binnen 1 minuut (BLE + NFC-scan tegelijk). AAPS
     * buckette de tweede als filledGap. De !filledGap-filter gooide die er
     * dan uit, waardoor het puntenaantal in het 2-uursvenster kunstmatig laag
     * bleef en FCLvNext ten onrechte skipte. Het echte timing-interval
     * (5m ± 6s zoals zichtbaar in xDrip) is geen probleem.
     */
    private val MAX_GAP_MINUTES = 10L   // één gemiste Libre-scan = ~5 min, marge naar 10

    fun getLastHours(hoursBack: Int): List<BgPoint> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
            ?: return emptyList()

        val cutoffMs = dateUtil.now() - max(1, hoursBack) * 60L * 60L * 1000L

        val sorted = data
            .filter { it.timestamp >= cutoffMs && it.recalculated > 39.0 }
            .sortedBy { it.timestamp }

        // Bepaal per punt of het een lang gat dekt (dan weggooien) of een
        // kort gat / NFC-overlap (dan gewoon meenemen).
        val result = mutableListOf<BgPoint>()
        for (i in sorted.indices) {
            val pt = sorted[i]
            if (!pt.filledGap) {
                result += BgPoint(DateTime(pt.timestamp), pt.recalculated / 18.0)
                continue
            }
            // filledGap = true: kijk hoe groot het gat is dat dit punt overbrugt.
            // Vergelijk met het vorige échte punt; als dat te ver terug ligt,
            // is dit een lange interpolatie → overslaan.
            val prevReal = (i - 1 downTo 0)
                .map { sorted[it] }
                .firstOrNull { !it.filledGap }
            val gapMinutes = if (prevReal != null)
                (pt.timestamp - prevReal.timestamp) / 60_000L
            else
                Long.MAX_VALUE   // geen vorig echt punt → conservatief overslaan
            if (gapMinutes <= MAX_GAP_MINUTES) {
                result += BgPoint(DateTime(pt.timestamp), pt.recalculated / 18.0)
            }
            // gapMinutes > MAX_GAP_MINUTES → niet meenemen (lange onbetrouwbare interpolatie)
        }
        return result
    }

    /**
     * Convenience: BG-data tussen twee tijdstippen.
     * Zelfde filledGap-logica als getLastHours(): korte gaten (≤ MAX_GAP_MINUTES)
     * worden meegenomen, lange interpolaties niet.
     */
    fun getBetween(start: DateTime, end: DateTime): List<BgPoint> {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy()
            ?: return emptyList()

        val sorted = data
            .filter {
                it.timestamp >= start.millis &&
                    it.timestamp <= end.millis &&
                    it.recalculated > 39.0
            }
            .sortedBy { it.timestamp }

        val result = mutableListOf<BgPoint>()
        for (i in sorted.indices) {
            val pt = sorted[i]
            if (!pt.filledGap) {
                result += BgPoint(DateTime(pt.timestamp), pt.recalculated / 18.0)
                continue
            }
            val prevReal = (i - 1 downTo 0)
                .map { sorted[it] }
                .firstOrNull { !it.filledGap }
            val gapMinutes = if (prevReal != null)
                (pt.timestamp - prevReal.timestamp) / 60_000L
            else Long.MAX_VALUE
            if (gapMinutes <= MAX_GAP_MINUTES) {
                result += BgPoint(DateTime(pt.timestamp), pt.recalculated / 18.0)
            }
        }
        return result
    }
}