package app.aaps.ui.compose.configuration

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConfigurationViewModelTest {

    @Mock private lateinit var activePlugin: ActivePlugin
    @Mock private lateinit var configBuilder: ConfigBuilder
    @Mock private lateinit var config: Config
    @Mock private lateinit var preferences: Preferences

    private lateinit var sut: ConfigurationViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // init's two viewModelScope.launch bodies (loadCategories + activeSelectionChanges.collect) are deferred
        // by StandardTestDispatcher, so construction touches none of the mocks.
        Dispatchers.setMain(StandardTestDispatcher())
        sut = ConfigurationViewModel(activePlugin, configBuilder, config, preferences)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState is simple mode with no categories and no confirmation`() {
        val state = sut.uiState.value
        assertThat(state.isSimpleMode).isTrue()
        assertThat(state.categories).isEmpty()
        assertThat(state.hardwarePumpConfirmation).isNull()
    }

    @Test
    fun `dismissHardwarePumpDialog clears confirmation and refreshes categories synchronously`() {
        // refreshCategories() reads preferences.simpleMode (false by default) and builds categories from
        // activePlugin; no visible plugins -> empty categories.
        whenever(activePlugin.getSpecificPluginsVisibleInList(any())).thenReturn(ArrayList<PluginBase>())

        sut.dismissHardwarePumpDialog()

        val state = sut.uiState.value
        assertThat(state.hardwarePumpConfirmation).isNull()
        assertThat(state.categories).isEmpty()
        assertThat(state.isSimpleMode).isFalse()
    }
}
