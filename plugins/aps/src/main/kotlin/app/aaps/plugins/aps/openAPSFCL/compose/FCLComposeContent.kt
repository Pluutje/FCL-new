package app.aaps.plugins.aps.openAPSFCL.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.plugins.aps.compose.OpenAPSScreen
import app.aaps.plugins.aps.compose.OpenAPSViewModel
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSFCL.vnext.analyzer.ui.FclAnalyzerScreen

class FCLComposeContent(
    private val apsPlugin: APS,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val sp: SP
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val viewModel = remember {
            OpenAPSViewModel(
                apsPlugin = apsPlugin,
                rxBus = rxBus,
                rh = rh,
                dateUtil = dateUtil,
                scope = scope
            )
        }
        val state = viewModel.uiState.collectAsStateWithLifecycle()

        Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Status") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📊 Analyzer") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("⚙️ Instellingen") }
                )
            }

            when (selectedTab) {
                0 -> OpenAPSScreen(
                    state = state.value,
                    onRefresh = viewModel::onRefresh
                )
                1 -> FclAnalyzerScreen(onDismiss = { selectedTab = 0 })
                2 -> FCLSettingsScreen(preferences = preferences, sp = sp)
            }
        }
    }

}