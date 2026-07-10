package app.aaps.plugins.calibration.di

import app.aaps.core.interfaces.di.AllConfigs
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.plugins.calibration.LinearCalibrationPlugin
import app.aaps.plugins.calibration.NoCalibrationPlugin
import app.aaps.plugins.calibration.SplineCalibrationPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap

/**
 * Self-registration of :plugins:calibration plugins into the global @AllConfigs plugin map
 * (@IntKey block 700–720, step 10). Including :plugins:calibration in settings.gradle is enough — no central
 * list edit needed. See PluginsListModule for the overall @IntKey ordering overview.
 *
 * 09/07/2026 (Ecko): SplineCalibrationPlugin toegevoegd na de dev-update-merge — ontbrak hier
 * (stond in de oude, inmiddels vervangen PluginsListModule.kt op @IntKey(630)). Dit bindt 'm aan
 * PluginBase/@AllConfigs zodat hij zichtbaar/selecteerbaar is in AAPS' pluginlijst — een aparte,
 * blijvend noodzakelijke binding aan de Calibration-interface zelf (voor dependency injection)
 * staat los hiervan al in SplineCalibrationPluginModule.kt.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class CalibrationPluginsListModule {

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(700)
    abstract fun bindNoCalibrationPlugin(plugin: NoCalibrationPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(710)
    abstract fun bindLinearCalibrationPlugin(plugin: LinearCalibrationPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(720)
    abstract fun bindSplineCalibrationPlugin(plugin: SplineCalibrationPlugin): PluginBase
}
