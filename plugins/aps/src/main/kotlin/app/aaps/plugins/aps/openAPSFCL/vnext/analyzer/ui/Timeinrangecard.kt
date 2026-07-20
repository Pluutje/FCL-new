package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.LogRow
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.TimeFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val AmsterdamZone: ZoneId = ZoneId.of("Europe/Amsterdam")

private const val LOW_THRESHOLD = 4.0
private const val HIGH_THRESHOLD = 10.0
private const val DEFAULT_SAMPLE_MINUTES = 5L
private const val MAX_STEP_MINUTES = 10L

private val TbrColor = Color(0xFFFF5A5F)
private val TbtColor = Color(0xFF2E7D32)
private val TirColor = Color(0xFF00C853)
private val TarColor = Color(0xFFFFB300)

private data class MutableRangeBucket(
    var tbrMs: Long = 0L,
    var tbtMs: Long = 0L,
    var tirUpperMs: Long = 0L,
    var tarMs: Long = 0L
)

private data class DailyRangeStats(
    val date: LocalDate,
    val tbrMs: Long,
    val tbtMs: Long,
    val tirUpperMs: Long,
    val tarMs: Long
) {
    val tirTotalMs: Long get() = tbtMs + tirUpperMs
    val totalMs: Long get() = tbrMs + tbtMs + tirUpperMs + tarMs

    val tirPct: Int
        get() = if (totalMs <= 0L) 0 else ((tirTotalMs * 100.0) / totalMs).roundToInt()
}

private data class RangeSummary(
    val tbrPct: Int,
    val tirPct: Int,
    val tarPct: Int
)

private enum class RangeBand {
    TBR,
    TBT,
    TIR_UPPER,
    TAR
}

@Composable
fun TimeInRangeCard(
    rows: List<LogRow>,
    lastSyncTs: Instant?,
    modifier: Modifier = Modifier
) {
    var dayWindow by rememberSaveable { mutableStateOf(7) }
    // Cyclisch: 7 → 14 → 30 → 7

    val dailyStats = remember(rows, dayWindow) {
        buildDailyRangeStats(rows, dayWindow)
    }

    val summary = remember(dailyStats) {
        summarize(dailyStats)
    }

    // 16/07/2026 (Ecko) — geschat HbA1c o.b.v. het gemiddelde van de ruwe
    // bg-waarden in HETZELFDE venster (dayWindow) als de TBR/TIR/TAR-balk
    // hierboven, zodat "7/14/30 dagen" overal in deze kaart hetzelfde
    // betekent. Zie computeAverageBg/estimateHba1cPct hieronder voor de
    // (bewust simpele) formule.
    val avgBgMmol = remember(rows, dayWindow) {
        computeAverageBg(rows, dayWindow)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "Glucose bereiken",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Laatste $dayWindow dagen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                TextButton(
                    onClick = {
                        dayWindow = when (dayWindow) {
                            7    -> 14
                            14   -> 30
                            else -> 7
                        }
                    }
                ) {
                    Text(
                        when (dayWindow) {
                            7    -> "7 dagen ▼"
                            14   -> "14 dagen ▼"
                            else -> "30 dagen ▲"
                        }
                    )
                }
            }

            // 16/07/2026 (Ecko) — geschat HbA1c, direct boven de TBR/TIR/TAR-balk,
            // zelfde "ⓘ tap for info"-patroon als de CGP-Scorekaart hieronder
            // in het scherm. Alleen tonen als er data in het venster is —
            // anders geen zinvol gemiddelde om op te baseren.
            if (avgBgMmol != null) {
                val hba1cPct = estimateHba1cPct(avgBgMmol)
                val hba1cMmolMol = pctToMmolMol(hba1cPct)
                var toonHba1cInfo by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toonHba1cInfo = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Geschat HbA1c: ${"%.0f".format(hba1cMmolMol)} mmol/mol " +
                            "(${"%.1f".format(hba1cPct)}%)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "ⓘ tap for info",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }

                if (toonHba1cInfo) {
                    Hba1cInfoDialog(
                        pct = hba1cPct,
                        mmolMol = hba1cMmolMol,
                        avgBgMmol = avgBgMmol,
                        dayWindow = dayWindow,
                        onDismiss = { toonHba1cInfo = false }
                    )
                }
            }

            SummaryStrip(
                tbrPct = summary.tbrPct,
                tirPct = summary.tirPct,
                tarPct = summary.tarPct
            )

            if (dailyStats.any { it.totalMs > 0L }) {
                DailyRangeChart(
                    stats = dailyStats,
                    dayWindow = dayWindow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nog geen bruikbare glucose-data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LegendItem("TBR", TbrColor)
                LegendItem("TBT", TbtColor)
                LegendItem("TIR", TirColor)
                LegendItem("TAR", TarColor)
            }

            Text(
                text = "Laatste sync: ${TimeFormat.formatLocalAmsterdam(lastSyncTs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SummaryStrip(
    tbrPct: Int,
    tirPct: Int,
    tarPct: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SummaryMetric(
            label = "TBR",
            value = tbrPct,
            color = TbrColor,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "|",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            style = MaterialTheme.typography.titleMedium
        )

        SummaryMetric(
            label = "TIR",
            value = tirPct,
            color = TirColor,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "|",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            style = MaterialTheme.typography.titleMedium
        )

        SummaryMetric(
            label = "TAR",
            value = tarPct,
            color = TarColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = "$value%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 8.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun DailyRangeChart(
    stats: List<DailyRangeStats>,
    dayWindow: Int,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        if (stats.isEmpty()) return@Canvas

        val topValuePadding = 22.dp.toPx()
        val bottomLabelPadding = 24.dp.toPx()
        val topChartPadding = 8.dp.toPx()

        val chartTop = topValuePadding + topChartPadding
        val chartBottom = size.height - bottomLabelPadding
        val chartHeight = chartBottom - chartTop

        val slotWidth = size.width / stats.size.toFloat()
        val barWidth = (slotWidth * 0.56f).coerceAtMost(22.dp.toPx())

        val gridColor = onSurface.copy(alpha = 0.08f)
        val emptyBarColor = onSurface.copy(alpha = 0.07f)

        // Adaptive tekst: donker in light mode, licht in dark mode
        val onSurfaceArgb = onSurface.copy(alpha = 0.85f).toArgb()
        val onSurfaceLightArgb = onSurface.copy(alpha = 0.65f).toArgb()

        val topValuePaint = android.graphics.Paint().apply {
            color = onSurfaceArgb
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }

        val bottomLabelPaint = android.graphics.Paint().apply {
            color = onSurfaceLightArgb
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
            val y = chartBottom - chartHeight * frac
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        val denseMode = dayWindow > 7
        val shortDayFmt = DateTimeFormatter.ofPattern("EE", Locale("nl"))
        val dayFmt = DateTimeFormatter.ofPattern("d/M", Locale("nl"))

        stats.forEachIndexed { index, day ->
            val xCenter = slotWidth * index + slotWidth / 2f
            val left = xCenter - barWidth / 2f

            val showBottomLabel = if (denseMode) {
                index % 2 == 0 || index == stats.lastIndex
            } else {
                true
            }

            if (day.totalMs <= 0L) {
                drawRect(
                    color = emptyBarColor,
                    topLeft = Offset(left, chartTop),
                    size = Size(barWidth, chartHeight)
                )

                if (showBottomLabel) {
                    val label = if (index == stats.lastIndex) {
                        "nu"
                    } else if (denseMode) {
                        day.date.format(dayFmt)
                    } else {
                        day.date.format(shortDayFmt)
                            .lowercase(Locale("nl"))
                    }

                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        xCenter,
                        size.height - 2.dp.toPx(),
                        bottomLabelPaint
                    )
                }

                return@forEachIndexed
            }

            val total = day.totalMs.toFloat()
            var bottom = chartBottom

            fun drawSegment(valueMs: Long, color: Color) {
                if (valueMs <= 0L) return
                val segmentHeight = chartHeight * (valueMs / total)
                drawRect(
                    color = color,
                    topLeft = Offset(left, bottom - segmentHeight),
                    size = Size(barWidth, segmentHeight)
                )
                bottom -= segmentHeight
            }

            drawSegment(day.tbrMs, TbrColor)
            drawSegment(day.tbtMs, TbtColor)
            drawSegment(day.tirUpperMs, TirColor)
            drawSegment(day.tarMs, TarColor)

            drawContext.canvas.nativeCanvas.drawText(
                "${day.tirPct}%",
                xCenter,
                topValuePadding - 2.dp.toPx(),
                topValuePaint
            )

            if (showBottomLabel) {
                val label = if (index == stats.lastIndex) {
                    "nu"
                } else if (denseMode) {
                    day.date.format(dayFmt)
                } else {
                    day.date.format(shortDayFmt)
                        .lowercase(Locale("nl"))
                }

                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    xCenter,
                    size.height - 2.dp.toPx(),
                    bottomLabelPaint
                )
            }
        }
    }
}

private fun buildDailyRangeStats(
    rows: List<LogRow>,
    dayWindow: Int
): List<DailyRangeStats> {
    val today = LocalDate.now(AmsterdamZone)
    val startDate = today.minusDays(dayWindow.toLong() - 1)
    val rangeStart = startDate.atStartOfDay(AmsterdamZone).toInstant()
    val rangeEnd = today.plusDays(1).atStartOfDay(AmsterdamZone).toInstant()

    val buckets = linkedMapOf<LocalDate, MutableRangeBucket>()
    repeat(dayWindow) { offset ->
        val date = startDate.plusDays(offset.toLong())
        buckets[date] = MutableRangeBucket()
    }

    if (rows.isEmpty()) {
        return buckets.map { (date, bucket) ->
            DailyRangeStats(
                date = date,
                tbrMs = bucket.tbrMs,
                tbtMs = bucket.tbtMs,
                tirUpperMs = bucket.tirUpperMs,
                tarMs = bucket.tarMs
            )
        }
    }

    val sorted = rows.sortedBy { it.timestamp }

    sorted.forEachIndexed { index, row ->
        val nextTs = sorted.getOrNull(index + 1)?.timestamp
            ?: row.timestamp.plus(Duration.ofMinutes(DEFAULT_SAMPLE_MINUTES))

        val rawDurationMs = Duration.between(row.timestamp, nextTs).toMillis()
        if (rawDurationMs <= 0L) return@forEachIndexed

        val clampedDurationMs = rawDurationMs.coerceAtMost(MAX_STEP_MINUTES * 60_000L)
        val intervalEnd = row.timestamp.plusMillis(clampedDurationMs)

        val effectiveStart = maxInstant(row.timestamp, rangeStart)
        val effectiveEnd = minInstant(intervalEnd, rangeEnd)

        if (!effectiveEnd.isAfter(effectiveStart)) return@forEachIndexed

        allocateInterval(
            buckets = buckets,
            start = effectiveStart,
            end = effectiveEnd,
            band = classifyRange(row)
        )
    }

    return buckets.map { (date, bucket) ->
        DailyRangeStats(
            date = date,
            tbrMs = bucket.tbrMs,
            tbtMs = bucket.tbtMs,
            tirUpperMs = bucket.tirUpperMs,
            tarMs = bucket.tarMs
        )
    }
}

private fun classifyRange(row: LogRow): RangeBand {
    val target = row.target.coerceIn(LOW_THRESHOLD, HIGH_THRESHOLD)

    return when {
        row.bg < LOW_THRESHOLD -> RangeBand.TBR
        row.bg < target -> RangeBand.TBT
        row.bg <= HIGH_THRESHOLD -> RangeBand.TIR_UPPER
        else -> RangeBand.TAR
    }
}

private fun allocateInterval(
    buckets: Map<LocalDate, MutableRangeBucket>,
    start: Instant,
    end: Instant,
    band: RangeBand
) {
    var cursor = start

    while (cursor.isBefore(end)) {
        val date = cursor.atZone(AmsterdamZone).toLocalDate()
        val dayEnd = date.plusDays(1).atStartOfDay(AmsterdamZone).toInstant()
        val segmentEnd = minInstant(dayEnd, end)
        val segmentMs = Duration.between(cursor, segmentEnd).toMillis()

        val bucket = buckets[date]
        if (bucket != null && segmentMs > 0L) {
            when (band) {
                RangeBand.TBR -> bucket.tbrMs += segmentMs
                RangeBand.TBT -> bucket.tbtMs += segmentMs
                RangeBand.TIR_UPPER -> bucket.tirUpperMs += segmentMs
                RangeBand.TAR -> bucket.tarMs += segmentMs
            }
        }

        cursor = segmentEnd
    }
}

private fun summarize(stats: List<DailyRangeStats>): RangeSummary {
    val tbr = stats.sumOf { it.tbrMs }
    val tbt = stats.sumOf { it.tbtMs }
    val tirUpper = stats.sumOf { it.tirUpperMs }
    val tar = stats.sumOf { it.tarMs }
    val total = (tbr + tbt + tirUpper + tar).coerceAtLeast(1L)

    fun pct(value: Long): Int = ((value * 100.0) / total).roundToInt()

    return RangeSummary(
        tbrPct = pct(tbr),
        tirPct = pct(tbt + tirUpper),
        tarPct = pct(tar)
    )
}

private fun minInstant(a: Instant, b: Instant): Instant =
    if (a.isBefore(b)) a else b

private fun maxInstant(a: Instant, b: Instant): Instant =
    if (a.isAfter(b)) a else b

// ── Geschat HbA1c (16/07/2026, Ecko) ────────────────────────────────────────
// BEWUST simpele, veelgebruikte formules (geen personalisatie, geen
// labwaarde) — zie Hba1cInfoDialog hieronder voor de toelichting die de
// gebruiker ook te zien krijgt.
//
// 1. Gemiddelde bg over hetzelfde venster als de TBR/TIR/TAR-balk hierboven
//    (eenvoudig, ongewogen gemiddelde van de losse metingen — geen
//    tijdsgewogen interpolatie zoals bij allocateInterval; voor een schatting
//    is dat bewust niet nodig).
// 2. eA1c (ADAG-formule, Nathan e.a. 2008 — zelfde formule die Nightscout en
//    de meeste CGM-apps gebruiken): (gemiddelde_mg/dL + 46.7) / 28.7.
// 3. Omrekening % (NGSP) → mmol/mol (IFCC): (% - 2.15) × 10.929 — de
//    internationale standaardconversie.
private const val MMOL_TO_MGDL = 18.0182

private fun computeAverageBg(rows: List<LogRow>, dayWindow: Int): Double? {
    val today = LocalDate.now(AmsterdamZone)
    val startDate = today.minusDays(dayWindow.toLong() - 1)
    val rangeStart = startDate.atStartOfDay(AmsterdamZone).toInstant()
    val rangeEnd = today.plusDays(1).atStartOfDay(AmsterdamZone).toInstant()

    val inWindow = rows.filter {
        it.bg > 0.0 && !it.timestamp.isBefore(rangeStart) && it.timestamp.isBefore(rangeEnd)
    }
    if (inWindow.isEmpty()) return null
    return inWindow.sumOf { it.bg } / inWindow.size
}

// 20/07/2026 (Ecko): internal i.p.v. private — de nieuwe HbA1c-trendlijn
// in CgpScoreKaart.kt hergebruikt deze exacte formule (zelfde package,
// zelfde module), zodat beide plekken in de app altijd dezelfde uitkomst
// tonen. Geen gedragswijziging.
internal fun estimateHba1cPct(avgBgMmol: Double): Double {
    val avgMgdl = avgBgMmol * MMOL_TO_MGDL
    return (avgMgdl + 46.7) / 28.7
}

internal fun pctToMmolMol(pct: Double): Double = (pct - 2.15) * 10.929

/**
 * Informatieve popup over de eA1c-schatting. Bewust in het Engels en op
 * hetzelfde wetenschappelijke niveau als PgrInfoDialog hierboven (16/07/2026,
 * Ecko, na feedback dat de eerdere NL-versie te informeel was en onnodig naar
 * Nightscout/CGM-apps verwees i.p.v. naar de onderliggende methode/bron).
 */
@Composable
private fun Hba1cInfoDialog(
    pct: Double,
    mmolMol: Double,
    avgBgMmol: Double,
    dayWindow: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Estimated HbA1c",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your estimated HbA1c over the last $dayWindow days is " +
                        "${"%.0f".format(mmolMol)} mmol/mol (${"%.1f".format(pct)}%), based on " +
                        "a mean glucose of ${"%.1f".format(avgBgMmol)} mmol/L.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "What is HbA1c?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Glycated haemoglobin (HbA1c) reflects the proportion of circulating " +
                        "haemoglobin that has become non-enzymatically bound to glucose. Because " +
                        "this binding accumulates over the roughly 8\u201312 week lifespan of a " +
                        "red blood cell, HbA1c is the clinical gold standard for average " +
                        "glycaemic control over that period, and is normally obtained from a " +
                        "laboratory blood sample.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "How is this estimate calculated?",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "This app does not measure HbA1c. Instead it derives an estimate (eA1c) " +
                        "from your mean glucose using the linear regression established by the " +
                        "A1c-Derived Average Glucose (ADAG) study (Nathan et al., Diabetes Care, " +
                        "2008):",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "HbA1c (%) = (mean glucose [mg/dL] + 46.7) / 28.7",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "The result is converted from the NGSP percentage to the IFCC mmol/mol " +
                        "unit using the standard international conversion adopted in the 2007 " +
                        "IFCC-ADA-EASD-IDF consensus statement:",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "HbA1c (mmol/mol) = (HbA1c% \u2212 2.15) \u00d7 10.929",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "Reference ranges (ADA diagnostic criteria)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "< 42 mmol/mol   (< 6.0%)   Normal\n" +
                        "42\u201347 mmol/mol  (6.0\u20136.4%) Prediabetes\n" +
                        "\u2265 48 mmol/mol   (\u2265 6.5%)   Diabetes (diagnostic threshold)\n" +
                        "< 53 mmol/mol   (< 7.0%)   Common treatment target",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "This is a population-derived estimate, not a laboratory measurement, and " +
                        "can diverge from a measured HbA1c \u2014 for example due to individual " +
                        "variation in red blood cell turnover, anaemia, or haemoglobin variants. " +
                        "Treatment targets are individualised, particularly in type 1 diabetes, " +
                        "and should be agreed with your care team.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}