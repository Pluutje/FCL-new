package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

        val topValuePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(190, 255, 255, 255)
            textSize = 24f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }

        val bottomLabelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(170, 255, 255, 255)
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