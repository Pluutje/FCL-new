package app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InfoTabPage(
    val title: String,
    val content: @Composable ColumnScope.() -> Unit
)

@Composable
fun InfoTabPager(
    pages: List<InfoTabPage>,
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) return

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val swipeEnabled = pages.size > 3

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (swipeEnabled) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                pages.forEachIndexed { index, page ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(page.title) }
                    )
                }
            }
        } else {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                pages.forEachIndexed { index, page ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(page.title) }
                    )
                }
            }
        }

        // Inhoud van de geselecteerde tab — gewoon als Column, geen HorizontalPager
        // Werkt correct in een scrollbare parent zonder height-conflict
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_content"
        ) { tabIndex ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                pages.getOrNull(tabIndex)?.content?.invoke(this)
            }
        }
    }
}