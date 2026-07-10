package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.di.APS
import app.aaps.core.interfaces.di.AllConfigs
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.plugins.aps.autotune.AutotunePlugin
import app.aaps.plugins.aps.loop.LoopPlugin
import app.aaps.plugins.aps.openAPSAMA.OpenAPSAMAPlugin
import app.aaps.plugins.aps.openAPSAutoISF.OpenAPSAutoISFPlugin
import app.aaps.plugins.aps.openAPSFCL.OpenAPSFCLPlugin
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap

/**
 * Self-registration of :plugins:aps plugins into the plugin maps (@IntKey block 200–240, step 10).
 * Loop is @APS (loop-enabled builds only); the OpenAPS engines and Autotune are @AllConfigs.
 * Including :plugins:aps in settings.gradle is enough — no central list edit needed.
 * See PluginsListModule for the overall @IntKey ordering overview.
 *
 * 09/07/2026 (Ecko): OpenAPSFCLPlugin toegevoegd na de dev-update-merge — ontbrak in de
 * nieuwe zelfregistratie-structuur (stond in de oude, inmiddels vervangen PluginsListModule.kt
 * op @IntKey(226)). @IntKey(235) gekozen i.p.v. de oude 226: alle vijf ronde tientallen in dit
 * blok (200/210/220/230/240) waren al bezet in de nieuwe structuur, en 235 groepeert FCL bij de
 * andere OpenAPS-doseermotoren (AMA/SMB/AutoISF) i.p.v. na Autotune, wat qua indeling logischer is.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class ApsPluginsListModule {

    @Binds
    @APS
    @IntoMap
    @IntKey(200)
    abstract fun bindLoopPlugin(plugin: LoopPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(210)
    abstract fun bindOpenAPSAMAPlugin(plugin: OpenAPSAMAPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(220)
    abstract fun bindOpenAPSSMBPlugin(plugin: OpenAPSSMBPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(230)
    abstract fun bindOpenAPSAutoISFPlugin(plugin: OpenAPSAutoISFPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(235)
    abstract fun bindOpenAPSFCLPlugin(plugin: OpenAPSFCLPlugin): PluginBase

    @Binds
    @AllConfigs
    @IntoMap
    @IntKey(240)
    abstract fun bindAutotunePlugin(plugin: AutotunePlugin): PluginBase
}
