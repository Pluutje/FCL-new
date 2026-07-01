package app.aaps.ui.search

import app.aaps.core.interfaces.sync.NsClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchViewModelTest {

    @Mock private lateinit var searchIndexBuilder: SearchIndexBuilder
    @Mock private lateinit var wikiSearchRepository: WikiSearchRepository
    @Mock private lateinit var nsClient: NsClient

    private lateinit var sut: SearchViewModel

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // StandardTestDispatcher defers the init{} launchIn coroutines (debounced query observer + the
        // masterOrPairedClientFlow observer) so construction stays clean and we exercise the synchronous
        // setter methods against the default uiState. The observed flow must still be non-null.
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(nsClient.masterOrPairedClientFlow).thenReturn(MutableStateFlow(false))
        sut = SearchViewModel(searchIndexBuilder, wikiSearchRepository, nsClient)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState is inactive and empty`() {
        val state = sut.uiState.value
        assertThat(state.isSearchActive).isFalse()
        assertThat(state.query).isEqualTo("")
        assertThat(state.results).isEmpty()
        assertThat(state.wikiResults).isEmpty()
        assertThat(state.isSearching).isFalse()
    }

    @Test
    fun `onQueryChanged updates the query`() {
        sut.onQueryChanged("insulin")

        assertThat(sut.uiState.value.query).isEqualTo("insulin")
    }

    @Test
    fun `onSearchModeActivated activates search and clears state`() {
        sut.onQueryChanged("stale")
        sut.onSearchModeActivated()

        val state = sut.uiState.value
        assertThat(state.isSearchActive).isTrue()
        assertThat(state.query).isEqualTo("")
        assertThat(state.results).isEmpty()
        assertThat(state.wikiResults).isEmpty()
    }

    @Test
    fun `onSearchModeDeactivated deactivates search and clears state`() {
        sut.onSearchModeActivated()
        sut.onQueryChanged("something")
        sut.onSearchModeDeactivated()

        val state = sut.uiState.value
        assertThat(state.isSearchActive).isFalse()
        assertThat(state.query).isEqualTo("")
        assertThat(state.results).isEmpty()
    }

    @Test
    fun `clearQuery empties the query but keeps search active`() {
        sut.onSearchModeActivated()
        sut.onQueryChanged("temp target")
        sut.clearQuery()

        val state = sut.uiState.value
        assertThat(state.query).isEqualTo("")
        assertThat(state.results).isEmpty()
        assertThat(state.isSearchActive).isTrue()
    }
}
