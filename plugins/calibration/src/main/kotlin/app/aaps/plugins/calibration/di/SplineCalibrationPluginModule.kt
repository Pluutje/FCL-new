package app.aaps.plugins.calibration.di

import app.aaps.core.interfaces.calibration.Calibration
import app.aaps.plugins.calibration.SplineCalibrationPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for [SplineCalibrationPlugin].
 *
 * NOTE: AAPS allows only one active [Calibration] plugin at a time — the user
 * selects it in the plugin manager.  Both [LinearCalibrationPlugin] and
 * [SplineCalibrationPlugin] are registered as [Calibration] implementations;
 * AAPS resolves the active one at runtime via its PluginStore / active-plugin
 * mechanism.  No qualifier annotation is needed here.
 *
 * If your AAPS build uses a separate PluginsModule that explicitly lists all
 * calibration plugins (e.g. via a @IntoSet multibinding), add
 * [SplineCalibrationPlugin] there as well.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SplineCalibrationPluginModule {

    // Exposes SplineCalibrationPlugin under the Calibration interface so that
    // any injection point that asks for Calibration gets the currently-selected
    // plugin via AAPS's active-plugin resolver.
    @Binds
    @Singleton
    internal abstract fun bindSplineCalibration(plugin: SplineCalibrationPlugin): Calibration
}
