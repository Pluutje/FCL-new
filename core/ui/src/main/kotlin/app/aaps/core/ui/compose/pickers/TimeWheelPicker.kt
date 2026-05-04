package app.aaps.core.ui.compose.pickers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

private const val TIME_VISIBLE_ITEMS = 5
private const val TIME_ITEM_HEIGHT_DP = 44

@Composable
fun TimeWheelPicker(
    selectedHour: Int,
    selectedMinute: Int,
    onTimeSelected: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    minHour: Int = 0,
    maxHour: Int = 23,
    minuteStep: Int = 15
) {
    val hours = (minHour..maxHour).toList()
    val minutes = (0..59 step minuteStep).toList()

    var currentHour by remember { mutableIntStateOf(selectedHour.coerceIn(minHour, maxHour)) }
    var currentMinute by remember { mutableIntStateOf(minutes.minByOrNull { abs(it - selectedMinute) } ?: 0) }

    Popup(
        onDismissRequest = {
            onTimeSelected(currentHour, currentMinute)
            onDismiss()
        },
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(220.dp),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tijd instellen",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Uur wiel
                    TimeWheel(
                        items = hours,
                        selectedItem = currentHour,
                        onItemSelected = { currentHour = it },
                        label = { "%02d".format(it) }
                    )

                    Text(
                        text = ":",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // Minuut wiel
                    TimeWheel(
                        items = minutes,
                        selectedItem = currentMinute,
                        onItemSelected = { currentMinute = it },
                        label = { "%02d".format(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        onTimeSelected(currentHour, currentMinute)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("OK")
                }
            }
        }
    }
}

@Composable
private fun TimeWheel(
    items: List<Int>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit,
    label: (Int) -> String
) {
    val halfVisible = TIME_VISIBLE_ITEMS / 2
    val listState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val initialIndex = items.indexOf(selectedItem).coerceAtLeast(0)

    LaunchedEffect(selectedItem, items) {
        listState.scrollToItem(initialIndex)
    }

    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf initialIndex
            val center = info.viewportStartOffset + info.viewportSize.height / 2
            val centered = visible.minByOrNull { abs(it.offset + it.size / 2 - center) }
            ((centered?.index ?: initialIndex) - halfVisible).coerceIn(0, items.lastIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (!isScrolling && centeredIndex in items.indices) {
                    onItemSelected(items[centeredIndex])
                }
            }
    }

    Box(
        modifier = Modifier.height((TIME_ITEM_HEIGHT_DP * TIME_VISIBLE_ITEMS).dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(70.dp)
                .height(TIME_ITEM_HEIGHT_DP.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        ) {}

        LazyColumn(
            state = listState,
            modifier = Modifier
                .width(70.dp)
                .height((TIME_ITEM_HEIGHT_DP * TIME_VISIBLE_ITEMS).dp),
            flingBehavior = snapFlingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(halfVisible) {
                Box(modifier = Modifier.height(TIME_ITEM_HEIGHT_DP.dp))
            }
            itemsIndexed(items) { index, item ->
                val isSelected = index == centeredIndex
                Box(
                    modifier = Modifier
                        .height(TIME_ITEM_HEIGHT_DP.dp)
                        .width(70.dp)
                        .alpha(if (isSelected) 1f else 0.4f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(item),
                        fontSize = 20.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
            items(halfVisible) {
                Box(modifier = Modifier.height(TIME_ITEM_HEIGHT_DP.dp))
            }
        }
    }
}