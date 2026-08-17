package org.jellyfin.androidtv.ui.home.hero

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.HomeFeaturedRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * State of the home screen's featured hero banner.
 *
 * [Loading] is distinct from [Hidden] so the home screen knows whether to wait before deciding
 * where initial focus lands: focusing the rows because the featured query hasn't answered yet
 * would leave the hero permanently unfocusable on load.
 */
sealed interface HomeHeroState {
	data object Loading : HomeHeroState
	data object Hidden : HomeHeroState
	data class Ready(val items: List<BaseItemDto>) : HomeHeroState
}

/**
 * Owns the featured items and the banner's expand/collapse state.
 *
 * Deliberately activity-scoped rather than fragment-scoped: [org.jellyfin.androidtv.ui.home.HomeRowsFragment]
 * is a *child* of the fragment hosting the banner and needs to read [expanded] to know who owns the
 * screen backdrop, and AppNavigationHost swaps fragments on navigation — a fragment-scoped instance
 * would refetch and lose the selected item every time the user returned from a details screen.
 */
class HomeHeroViewModel(
	private val homeFeaturedRepository: HomeFeaturedRepository,
) : ViewModel() {
	private companion object {
		val STALE_AFTER = 5.minutes
	}

	private val _state = MutableStateFlow<HomeHeroState>(HomeHeroState.Loading)
	val state: StateFlow<HomeHeroState> = _state.asStateFlow()

	private val _selectedIndex = MutableStateFlow(0)
	val selectedIndex: StateFlow<Int> = _selectedIndex.asStateFlow()

	// Read by HomeRowsFragment to decide whether the banner or row selection owns the backdrop.
	private val _expanded = MutableStateFlow(false)
	val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

	// Lets MainActivity ask HomeFragment to reclaim focus for whichever side (hero or rows) is
	// currently active, when the sidebar hands focus back to Home on the D-pad Right key. Compose's
	// own directional search can't be trusted for that handoff: Home's content deliberately shares
	// the sidebar's own left edge (see Sidebar's overlay param), which lands the platform's default
	// focus search on whichever composable it finds first in declaration order - the rows - instead
	// of wherever the user actually was. MainActivity has no reference to HomeFragment's own
	// FocusRequesters (they're local to that composition), so this activity-scoped ViewModel is the
	// only channel available to reach across; only HomeFragment listens, so this is a silent no-op
	// on every other screen.
	private val _focusRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	val focusRequests: SharedFlow<Unit> = _focusRequests.asSharedFlow()

	fun requestContentFocus() {
		_focusRequests.tryEmit(Unit)
	}

	private var loadedAtMillis: Long? = null
	private var loadJob: Job? = null

	init {
		load()
	}

	/**
	 * Reloads the featured items if the last successful load is older than [STALE_AFTER]. Called
	 * when the home screen resumes; the banner doesn't need websocket-level freshness.
	 */
	fun refreshIfStale() {
		// A load already running is not stale. Without this the initial load from init would be
		// cancelled and restarted by the host fragment's very first onResume, costing a duplicate
		// pair of API calls on every single launch.
		if (loadJob?.isActive == true) return

		val loadedAt = loadedAtMillis
		if (loadedAt == null || (System.currentTimeMillis() - loadedAt).milliseconds > STALE_AFTER) load()
	}

	fun setExpanded(expanded: Boolean) {
		_expanded.value = expanded
	}

	fun select(index: Int) {
		val items = (_state.value as? HomeHeroState.Ready)?.items ?: return
		if (items.isNotEmpty()) _selectedIndex.value = index.mod(items.size)
	}

	fun next() {
		val items = (_state.value as? HomeHeroState.Ready)?.items ?: return
		if (items.isNotEmpty()) _selectedIndex.value = (_selectedIndex.value + 1).mod(items.size)
	}

	private fun load() {
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			val items = homeFeaturedRepository.getFeaturedItems()
			loadedAtMillis = System.currentTimeMillis()
			_state.value = if (items.isEmpty()) HomeHeroState.Hidden else HomeHeroState.Ready(items)
			_selectedIndex.value = _selectedIndex.value.coerceIn(0, (items.size - 1).coerceAtLeast(0))
		}
	}
}
