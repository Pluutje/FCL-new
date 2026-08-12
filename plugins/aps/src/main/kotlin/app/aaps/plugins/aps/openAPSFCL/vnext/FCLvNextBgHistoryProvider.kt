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
 * - observatie-learning       random
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

    // ✅ NIEUW (07/08/2026, Ecko-diagnose "timing-gaten") — onafhankelijke,
    // kleine fallback-buffer bovenop AAPS's eigen bucketed-data-cache
    // (getLastHours() hierboven).
    //
    // ROOT CAUSE (bevestigd via AndroidAPS.log + AutosensDataStoreObject.kt
    // uit AAPS-core, 07/08/2026): AAPS's IobCobCalculator herbouwt zijn
    // bucketed BG-tabel via createBucketedData(), die eerst isAbout5minData()
    // aanroept. Zodra ÉÉN ENKEL meetinterval in de op dat moment geladen
    // bgReadings-set meer dan IRREGULAR_DATA_SEC=30 seconden van een
    // 5-minuten-veelvoud afwijkt, slaat isAbout5minData() om van true naar
    // false — en createBucketedData() reageert daarop met "Invalidating
    // cached data because of changed mode" (reset() + overschakelen naar een
    // andere bucketing-algoritme). Bevestigd in de AAPS-log van 07/08/2026
    // 04:39-05:05: "FCLvNext skipped: Need more BG data" liep daar keurig
    // 1/5 -> 2/5 -> 3/5 -> 4/5 -> 5/5 op, één punt per cyclus, TERWIJL
    // FCLGlucoLink in diezelfde 25 minuten gewoon elke 5 minuten een geldige
    // meting afleverde (geen BLE-hapering in dat venster). Dus: geen
    // probleem met te weinig metingen, wel een AAPS-kern-cache die zichzelf
    // leeggooit zodra een sensorbron (zoals FSL2-via-Juggluco, en nu
    // CareSens Air-via-FCLGlucoLink) een keer een net-niet-perfect-5-minuten
    // interval aflevert (bv. door een BLE-herverbind-vertraging) — iets wat
    // bij editors eerdere Dexcom G6-koppeling nooit voorkwam, vermoedelijk
    // dankzij Dexcom's strakkere, native meetcadans.
    //
    // Deze cache-reset zit in AAPS-kerncode (IobCobCalculatorPlugin /
    // AutosensDataStoreObject), niet in FCLvNext zelf, en wordt door de hele
    // Loop gedeeld — dus niet iets om vanuit FCLvNext te patchen. In plaats
    // daarvan: FCLvNext houdt hier zijn EIGEN kleine geheugen bij van de
    // laatste ~40 minuten aan metingen, gevuld op het moment dat
    // determine_basal() sowieso al de actuele meting binnenkrijgt (dus
    // volledig onafhankelijk van of AAPS's bucketed-cache net gereset is).
    // Bij een AAPS-cache-reset blijft deze buffer intact, en overbrugt zo
    // de ~25 minuten die de cache anders nodig zou hebben om zichzelf weer
    // op te bouwen.
    private val recentBufferLock = Any()
    private val recentBuffer = mutableListOf<BgPoint>()
    private val RECENT_BUFFER_RETENTION_MS = 40L * 60_000L // 40 min, ruim boven de 25 min die FCLvNext nodig heeft

    /**
     * Registreer de actuele meting (zoals determine_basal() die toch al
     * ontvangt via glucose_status) in de eigen fallback-buffer. Idempotent
     * per tijdstip (geen dubbele punten bij herhaalde aanroepen met dezelfde
     * timestamp), en trimt automatisch alles ouder dan
     * RECENT_BUFFER_RETENTION_MS.
     */
    fun recordCurrentReading(time: DateTime, bgMmol: Double) {
        synchronized(recentBufferLock) {
            if (recentBuffer.none { it.time.millis == time.millis }) {
                recentBuffer.add(BgPoint(time, bgMmol))
            }
            val cutoffMs = time.millis - RECENT_BUFFER_RETENTION_MS
            recentBuffer.removeAll { it.time.millis < cutoffMs }
            recentBuffer.sortBy { it.time.millis }
        }
    }

    /**
     * Zoals getLastHours(), maar valt terug op de eigen [recentBuffer] voor
     * het recente venster zodra die MEER punten bevat dan AAPS's bucketed
     * data teruggeeft — dat is precies het signaal dat AAPS's cache net
     * gereset is (zie kdoc hierboven). Combineert AAPS's data voor alles
     * ouder dan de buffer met de buffer zelf, zonder duplicaten.
     */
    fun getLastHoursResilient(hoursBack: Int): List<BgPoint> {
        val fromAaps = getLastHours(hoursBack)
        val bufferSnapshot = synchronized(recentBufferLock) { recentBuffer.toList() }
        if (bufferSnapshot.size <= fromAaps.size) return fromAaps
        val bufferOldestMs = bufferSnapshot.first().time.millis
        val merged = (fromAaps.filter { it.time.millis < bufferOldestMs } + bufferSnapshot)
            .distinctBy { it.time.millis }
            .sortedBy { it.time.millis }
        return merged
    }

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
    // ✅ VERHOOGD (02/07/2026, Ecko): Samsung A36 + FSL-2 + Juggluco→xDrip+→AAPS
    // geeft soms aaneengesloten reeksen van filledGap-punten wanneer de BLE-verbinding
    // kort wegvalt (typisch 10-20 minuten). Met MAX_GAP_MINUTES=10 werd één zo'n
    // reeks al afgewezen terwijl de punten er wel gewoon inzaten als interpolatie.
    // Bij Libre2-sensoren via Juggluco is interpolatie over ≤15 minuten acceptabel
    // voor trendberekening (de slope verandert niet explosief in 15 min).
    // De teller van opeenvolgende filledGap-punten (cumulativeGapMinutes) zorgt dat
    // een aaneengesloten reeks van >15 min wél wordt afgewezen.
    private val MAX_GAP_MINUTES = 15L   // één Libre-scan-cyclus + marge

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