package app.aaps.plugins.calibration.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.navigation.ElementType
import app.aaps.core.ui.compose.navigation.LocalPluginNavigationRequest
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.plugins.calibration.MIN_ENTRIES_FOR_SPLINE
import app.aaps.plugins.calibration.R
import app.aaps.plugins.calibration.db.CalibrationEntry
import kotlin.math.roundToInt

@Composable
internal fun SplineCalibrationScreen(
    viewModel: SplineCalibrationViewModel,
    setToolbarConfig: (ToolbarConfig) -> Unit,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val title   = stringResource(R.string.spline_calibration_name)
    val backDesc = stringResource(app.aaps.core.ui.R.string.back)

    LaunchedEffect(Unit) {
        setToolbarConfig(
            ToolbarConfig(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                    }
                },
                actions = {}
            )
        )
    }

    val navigationRequest = LocalPluginNavigationRequest.current
    SplineCalibrationScreenContent(
        state              = state,
        formatDateTime     = viewModel.dateUtil::dateAndTimeString,
        formatTime         = viewModel.dateUtil::timeString,
        onMarkSensorChange = { navigationRequest(NavigationRequest.Element(ElementType.SENSOR_INSERT)) },
        onAddCalibration   = { navigationRequest(NavigationRequest.Element(ElementType.CALIBRATION)) },
        onSelectEntry      = viewModel::selectEntry,
        onDeleteEntry      = viewModel::deleteEntry,
        onManualOffsetChange = viewModel::setManualOffset,
        onManualOffsetReset  = viewModel::resetManualOffset
    )
}

@Composable
internal fun SplineCalibrationScreenContent(
    state: SplineCalibrationUiState,
    formatDateTime: (Long) -> String,
    formatTime: (Long) -> String,
    onMarkSensorChange: () -> Unit,
    onAddCalibration: () -> Unit,
    onSelectEntry: (Long) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onManualOffsetChange: (Float) -> Unit,
    onManualOffsetReset: () -> Unit
) {
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedEntryId, state.entries) {
        val id = state.selectedEntryId ?: return@LaunchedEffect
        val index = state.entries.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AapsSpacing.medium),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
    ) {
        SplineStatusCard(state = state, formatDateTime = formatDateTime, formatTime = formatTime)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)
        ) {
            Button(onClick = onMarkSensorChange, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cal_mark_sensor_change_now))
            }
            Button(onClick = onAddCalibration, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cal_add_calibration))
            }
        }

        SplineChartCard(
            state          = state,
            formatDateTime = formatDateTime,
            onSelectEntry  = onSelectEntry,
            onOffsetChange = onManualOffsetChange,
            onOffsetReset  = onManualOffsetReset
        )

        Text(
            text = stringResource(R.string.cal_entries_header, state.entries.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = AapsSpacing.small, top = AapsSpacing.small)
        )

        if (state.entries.isEmpty()) {
            SplineEmptyEntries()
        } else {
            SplineEntriesList(
                entries         = state.entries,
                state           = state,
                selectedEntryId = state.selectedEntryId,
                glucoseUnit     = state.glucoseUnit,
                listState       = listState,
                contentPadding  = PaddingValues(bottom = AapsSpacing.large),
                formatTime      = formatDateTime,
                onSelect        = onSelectEntry,
                onDelete        = { pendingDeleteId = it }
            )
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title   = { Text(stringResource(R.string.cal_remove_entry_title)) },
            text    = { Text(stringResource(R.string.cal_remove_entry_message)) },
            confirmButton = {
                TextButton(onClick = { onDeleteEntry(id); pendingDeleteId = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Status card
// ---------------------------------------------------------------------------

@Composable
private fun SplineStatusCard(
    state: SplineCalibrationUiState,
    formatDateTime: (Long) -> String,
    formatTime: (Long) -> String
) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.medium)) {

            val statusText = when {
                state.sessionStart == null ->
                    stringResource(R.string.cal_status_no_session)

                state.isInWarmUp ->
                    stringResource(R.string.cal_status_warmup, formatTime(state.warmUpEndsAt ?: 0L))

                state.splineFit != null ->
                    stringResource(R.string.spline_cal_status_spline_active)

                state.linearFit != null && state.linearFit.isApplicable ->
                    stringResource(R.string.spline_cal_status_linear_fallback, MIN_ENTRIES_FOR_SPLINE)

                state.entries.size < 2 ->
                    stringResource(R.string.cal_status_need_more_entries, state.entries.size)

                else ->
                    stringResource(R.string.cal_status_unsafe_fit)
            }

            Text(
                text       = statusText,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            // Spline detail line
            state.splineFit?.let { spline ->
                Spacer(Modifier.height(AapsSpacing.small))
                Text(
                    text  = stringResource(
                        R.string.spline_cal_knot_detail,
                        spline.correctionAtKnot.formatBgDisplay(state.glucoseUnit, signed = true)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Tweede knooppunt actief indicator
                if (spline.hasTwoKnots && spline.knot2X != null && spline.knot2Y != null) {
                    val corr2 = spline.knot2Y!! - spline.knot2X!!
                    val corr2Str = if (state.glucoseUnit == app.aaps.core.data.model.GlucoseUnit.MMOL)
                        "${"%.1f".format(corr2 / 18.0182)} mmol"
                    else "${"%.0f".format(corr2)} mg/dL"
                    Text(
                        text  = "Knot 2 bij 11 mmol: $corr2Str  ✓ 2-segment actief",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Linear fallback detail line (always shown when available)
            state.linearFit?.let { lin ->
                if (lin.isApplicable) {
                    Spacer(Modifier.height(AapsSpacing.extraSmall))
                    Text(
                        text  = stringResource(
                            R.string.cal_slope_offset,
                            lin.slope,
                            lin.correctionAtCenter.formatBgDisplay(state.glucoseUnit, signed = true)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.sessionStart?.let { start ->
                Spacer(Modifier.height(AapsSpacing.small))
                Text(
                    text  = stringResource(R.string.cal_session_started, formatDateTime(start)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chart card
// ---------------------------------------------------------------------------

@Composable
private fun SplineChartCard(
    state: SplineCalibrationUiState,
    formatDateTime: (Long) -> String,
    onSelectEntry: (Long) -> Unit,
    onOffsetChange: (Float) -> Unit,
    onOffsetReset: () -> Unit
) {
    var zoomSegment by remember { mutableStateOf(ZoomSegment.FULL) }
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.medium)) {
            // Grafiek + verticale offset-slider naast elkaar
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top
            ) {
                SplineScatterChart(
                    entries          = state.entries,
                    splineFit        = state.splineFit,
                    linearFit        = state.linearFit,
                    selectedEntryId  = state.selectedEntryId,
                    now              = state.now,
                    glucoseUnit      = state.glucoseUnit,
                    manualOffsetMmol = state.manualOffsetMmol,
                    zoomSegment      = zoomSegment,
                    onZoomChange     = { zoomSegment = it },
                    modifier         = Modifier.weight(1f).padding(end = 4.dp)
                )
                VerticalOffsetSlider(
                    state          = state,
                    onOffsetChange = onOffsetChange,
                    onReset        = onOffsetReset,
                    modifier       = Modifier.padding(start = AapsSpacing.small)
                )
            }
            if (state.entries.size >= 2) {
                Spacer(Modifier.height(AapsSpacing.small))
                SplineEntrySliderReadout(state = state, formatDateTime = formatDateTime)
                SplineEntrySlider(state = state, onSelectEntry = onSelectEntry)
            } else if (state.entries.size == 1) {
                Spacer(Modifier.height(AapsSpacing.small))
                SplineEntrySliderReadout(state = state, formatDateTime = formatDateTime)
                // Zoom-indicator: toon actief segment + instructie
                if (zoomSegment != ZoomSegment.FULL) {
                    Text(
                        text  = when (zoomSegment) {
                            ZoomSegment.LOW  -> "↙ Zoomed: low segment (< 6 mmol)  •  tap to cycle"
                            ZoomSegment.MID  -> "↔ Zoomed: mid segment (6–11 mmol)  •  tap to cycle"
                            ZoomSegment.HIGH -> "↗ Zoomed: high segment (> 11 mmol)  •  tap to cycle"
                            ZoomSegment.FULL -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SplineEntrySliderReadout(
    state: SplineCalibrationUiState,
    formatDateTime: (Long) -> String
) {
    val selectedIndex = state.entries.indexOfFirst { it.id == state.selectedEntryId }
    if (selectedIndex < 0) return
    val entry = state.entries[selectedIndex]
    val calibrated = state.calibratedValue(entry.sensorMgdlAtPairing)
    val calStr = if (calibrated != null)
        " → gecal ${calibrated.formatBgDisplay(state.glucoseUnit)}"
    else ""
    Text(
        text  = stringResource(
            R.string.cal_chart_entry_readout,
            selectedIndex + 1,
            state.entries.size,
            formatDateTime(entry.timestamp),
            entry.sensorMgdlAtPairing.formatBgDisplay(state.glucoseUnit),
            entry.fingerstickMgdl.formatBgDisplay(state.glucoseUnit)
        ) + calStr,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SplineEntrySlider(
    state: SplineCalibrationUiState,
    onSelectEntry: (Long) -> Unit
) {
    val selectedIndex = state.entries.indexOfFirst { it.id == state.selectedEntryId }.coerceAtLeast(0)
    val lastIndex = state.entries.lastIndex
    Slider(
        value      = selectedIndex.toFloat(),
        valueRange = 0f..lastIndex.toFloat(),
        steps      = (lastIndex - 1).coerceAtLeast(0),
        onValueChange = { v ->
            val newIndex = v.roundToInt().coerceIn(0, lastIndex)
            val id = state.entries[newIndex].id
            if (id != state.selectedEntryId) onSelectEntry(id)
        },
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------
// Entries list (identical structure to CalibrationScreen)
// ---------------------------------------------------------------------------

@Composable
private fun SplineEmptyEntries() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AapsSpacing.xxLarge),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text  = stringResource(R.string.cal_no_entries),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SplineEntriesList(
    entries: List<CalibrationEntry>,
    state: SplineCalibrationUiState,
    selectedEntryId: Long?,
    glucoseUnit: GlucoseUnit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    formatTime: (Long) -> String,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    LazyColumn(
        modifier       = Modifier.fillMaxWidth(),
        state          = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)
    ) {
        items(items = entries, key = { it.id }) { entry ->
            SplineEntryRow(
                entry       = entry,
                state       = state,
                selected    = entry.id == selectedEntryId,
                glucoseUnit = glucoseUnit,
                formatTime  = formatTime,
                onSelect    = onSelect,
                onDelete    = onDelete
            )
        }
    }
}

@Composable
private fun SplineEntryRow(
    entry: CalibrationEntry,
    state: SplineCalibrationUiState,
    selected: Boolean,
    glucoseUnit: GlucoseUnit,
    formatTime: (Long) -> String,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    AapsCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(entry.id) },
        selected = selected
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val calibrated = state.calibratedValue(entry.sensorMgdlAtPairing)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = formatTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EntryValueChip(
                        label = "stick",
                        value = entry.fingerstickMgdl.formatBgDisplay(glucoseUnit),
                        delta = null,
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                    EntryValueChip(
                        label = "sensor",
                        value = entry.sensorMgdlAtPairing.formatBgDisplay(glucoseUnit),
                        delta = (entry.fingerstickMgdl - entry.sensorMgdlAtPairing)
                                    .formatBgDisplay(glucoseUnit, signed = true),
                        accentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (calibrated != null) {
                        val calDelta = entry.fingerstickMgdl - calibrated
                        EntryValueChip(
                            label = "cal",
                            value = calibrated.formatBgDisplay(glucoseUnit),
                            delta = calDelta.formatBgDisplay(glucoseUnit, signed = true),
                            accentColor = when {
                                kotlin.math.abs(calDelta) <= (if (glucoseUnit == GlucoseUnit.MMOL) 0.5 else 9.0)
                                    -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
            IconButton(onClick = { onDelete(entry.id) }) {
                Icon(
                    imageVector     = Icons.Filled.Delete,
                    contentDescription = stringResource(app.aaps.core.ui.R.string.delete),
                    modifier        = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared formatting helper
// ---------------------------------------------------------------------------

private fun Double.formatBgDisplay(unit: GlucoseUnit, signed: Boolean = false): String {
    val converted = if (unit == GlucoseUnit.MMOL) this * GlucoseUnit.MGDL_TO_MMOLL else this
    val format = when {
        signed && unit == GlucoseUnit.MGDL -> "%+.0f"
        signed && unit == GlucoseUnit.MMOL -> "%+.1f"
        unit == GlucoseUnit.MGDL           -> "%.0f"
        else                               -> "%.1f"
    }
    return format.format(converted)
}

// ---------------------------------------------------------------------------
// Manual offset card
// ---------------------------------------------------------------------------

/**
 * Kaart met een schuif voor handmatige offset-correctie (±1,5 mmol/L).
 * De offset wordt bovenop de spline (of lineaire fallback) opgeteld.
 */




// ---------------------------------------------------------------------------
// Compacte waarde-chip voor de entry-lijst
// ---------------------------------------------------------------------------

@Composable
private fun EntryValueChip(
    label: String,
    value: String,
    delta: String?,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall,
            color = accentColor,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        if (delta != null) {
            Text(
                text  = "($delta)",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor.copy(alpha = 0.7f)
            )
        }
    }
}


// ---------------------------------------------------------------------------
// Verticale offset-slider (rechts naast de grafiek)
// ---------------------------------------------------------------------------

/**
 * Verticale slider voor de handmatige offset, rechts naast de grafiek.
 * Boven = +max, onder = -max. Waarde en reset-knop bovenaan.
 */
@Composable
private fun VerticalOffsetSlider(
    state: SplineCalibrationUiState,
    onOffsetChange: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor   = MaterialTheme.colorScheme.error
    val surfaceVar   = MaterialTheme.colorScheme.surfaceVariant
    val cardBg       = MaterialTheme.colorScheme.surfaceContainerHighest
    val density      = LocalDensity.current

    val thumbRadiusDp = 10.dp
    val trackWidthDp  = 4.dp
    val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
    val trackWidthPx  = with(density) { trackWidthDp.toPx() }
    var trackHeightPx by remember { mutableStateOf(1f) }

    val valueColor = when {
        state.manualOffsetMmol > 0.05f  -> primaryColor
        state.manualOffsetMmol < -0.05f -> errorColor
        else                            -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Buitenste Column: kader (Surface) + "Offset" label eronder
    Column(
        modifier            = modifier.width(52.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Kader met afwijkende achtergrond
        Surface(
            modifier = Modifier
                .width(52.dp)
                .weight(1f),
            shape = MaterialTheme.shapes.small,
            color = cardBg,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Waarde bovenaan in het kader
                Text(
                    text     = state.manualOffsetMmol.formatOffsetMmol(state.glucoseUnit),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = valueColor,
                    maxLines = 1
                )

                // Draggable track in het midden
                Box(
                    modifier = Modifier
                        .width(thumbRadiusDp * 2)
                        .weight(1f)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                val range  = MANUAL_OFFSET_MAX_MMOL * 2f
                                val dMmol  = (-delta / (trackHeightPx - thumbRadiusPx * 2)) * range
                                val newVal = (state.manualOffsetMmol + dMmol)
                                    .coerceIn(-MANUAL_OFFSET_MAX_MMOL, MANUAL_OFFSET_MAX_MMOL)
                                onOffsetChange(newVal)
                            }
                        )
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        trackHeightPx = size.height
                        val cx     = size.width / 2f
                        val topY   = thumbRadiusPx
                        val botY   = size.height - thumbRadiusPx
                        val trackH = botY - topY
                        val fraction = ((state.manualOffsetMmol + MANUAL_OFFSET_MAX_MMOL) /
                                        (MANUAL_OFFSET_MAX_MMOL * 2f)).coerceIn(0f, 1f)
                        val thumbY = botY - fraction * trackH
                        val midY   = botY - 0.5f * trackH

                        drawLine(color = surfaceVar, start = Offset(cx, topY), end = Offset(cx, botY),
                            strokeWidth = trackWidthPx, cap = StrokeCap.Round)
                        drawLine(color = primaryColor.copy(alpha = 0.7f),
                            start = Offset(cx, minOf(midY, thumbY)), end = Offset(cx, maxOf(midY, thumbY)),
                            strokeWidth = trackWidthPx, cap = StrokeCap.Round)
                        drawCircle(color = primaryColor, radius = thumbRadiusPx, center = Offset(cx, thumbY))
                        drawLine(color = primaryColor.copy(alpha = 0.4f),
                            start = Offset(cx - thumbRadiusPx, midY), end = Offset(cx + thumbRadiusPx, midY),
                            strokeWidth = 1.5f)
                    }
                }

                // Reset-knop onderaan in het kader
                if (state.manualOffsetMmol != 0f) {
                    TextButton(
                        onClick        = onReset,
                        contentPadding = PaddingValues(0.dp),
                        modifier       = Modifier.width(48.dp)
                    ) {
                        Text(text = "0", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        // "Offset" label onder het kader, op de plek van de rode cirkel
        Text(
            text  = "Offset",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}


private fun Float.formatOffsetMmol(unit: GlucoseUnit): String {
    return if (unit == GlucoseUnit.MMOL) {
        "%+.2f mmol/L".format(this)
    } else {
        "%+.0f mg/dL".format(this * 18.0182f)
    }
}
