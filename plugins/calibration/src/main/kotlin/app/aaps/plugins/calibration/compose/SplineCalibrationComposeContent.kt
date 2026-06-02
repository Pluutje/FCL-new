package app.aaps.plugins.calibration.compose

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig

internal class SplineCalibrationComposeContent : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val viewModel: SplineCalibrationViewModel = hiltViewModel()
        SplineCalibrationScreen(
            viewModel        = viewModel,
            setToolbarConfig = setToolbarConfig,
            onNavigateBack   = onNavigateBack
        )
    }
}
