package org.jellyfin.androidtv.ui.browsing

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideLocalInteractionTracker
import org.jellyfin.androidtv.ui.composable.compat.AppNavigationHost
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.home.hero.HomeHeroViewModel
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationSound
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.shared.sidebar.Sidebar
import org.jellyfin.androidtv.ui.shared.sidebar.SidebarCollapsedWidth
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.applyTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import kotlin.reflect.KClass

private val PlaybackFragmentClasses: Set<KClass<out Fragment>> = setOf(
	VideoPlayerFragment::class,
	CustomPlaybackOverlayFragment::class,
)

/**
 * How close (in dp) a focused view's left edge must be to the collapsed sidebar's right edge to
 * be considered "the first column" for the purposes of [MainActivity.dispatchKeyEvent]'s D-pad
 * Left guard. Comfortably larger than the sidebar's own collapsed width, but well short of a
 * typical card width so second-column items aren't mistaken for the first.
 */
private const val SidebarLeftEscapeThresholdDp = 152

/**
 * Tracks the currently visible [Destination.Fragment]'s class by mirroring [NavigationRepository]'s
 * push/replace/clear/back-navigation behavior. Used to derive both whether the sidebar should hide
 * itself (a video player fragment is on screen) and whether the home screen's content should skip
 * the usual sidebar-width inset (see isHomeScreen below).
 */
@Composable
private fun rememberCurrentFragmentClass(navigationRepository: NavigationRepository): KClass<out Fragment>? {
	val screenStack = remember { mutableStateListOf<KClass<out Fragment>>() }

	LaunchedEffect(navigationRepository) {
		navigationRepository.currentAction.collect { action ->
			when (action) {
				is NavigationAction.NavigateFragment -> when {
					action.clear -> {
						screenStack.clear()
						screenStack.add(action.destination.fragment)
					}
					action.replace && screenStack.isNotEmpty() -> screenStack[screenStack.lastIndex] = action.destination.fragment
					else -> screenStack.add(action.destination.fragment)
				}
				NavigationAction.GoBack -> if (screenStack.size > 1) screenStack.removeAt(screenStack.lastIndex)
				NavigationAction.Nothing -> Unit
			}
		}
	}

	return screenStack.lastOrNull()
}

class MainActivity : FragmentActivity() {
	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	// Same activity-scoped instance HomeFragment and HomeRowsFragment already share - see
	// HomeHeroViewModel.focusRequests for why dispatchKeyEvent needs it.
	private val homeHeroViewModel by viewModel<HomeHeroViewModel>()
	private val workManager by inject<WorkManager>()
	private val navigationSound by inject<NavigationSound>()

	// Updated synchronously by Sidebar's onFocusChanged callback (not Compose state) so
	// dispatchKeyEvent can read a reliably up-to-date value within the same key event.
	private var sidebarHasFocus = false

	// Mirrors the Compose-local isHomeScreen via SideEffect (see setContent) so dispatchKeyEvent,
	// a plain Activity method outside the composition, can read it synchronously.
	private var isHomeScreenActive = false

	// A distinct native View boundary around Sidebar (rather than a plain composable sharing
	// MainActivity's own AndroidComposeView) so dispatchKeyEvent can synchronously exclude it
	// from a single D-pad Left focus search via descendantFocusability — see dispatchKeyEvent.
	private var sidebarView: ComposeView? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		if (!validateAuthentication()) return

		interactionTrackerViewModel.keepScreenOn.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { keepScreenOn ->
				if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}.launchIn(lifecycleScope)

		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset(clearHistory = true)

		// Catches every View-level focus move, including into and out of the sidebar and between the
		// Leanback cards; moves inside a single Compose hierarchy notify NavigationSound themselves.
		window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> navigationSound.notifyMoved() }

		navigationRepository.currentAction
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach {
				interactionTrackerViewModel.notifyInteraction(canCancel = false, userInitiated = false)
			}.launchIn(lifecycleScope)

		setContent {
			JellyfinTheme {
				ProvideLocalInteractionTracker(
					interactionTracker = { interactionTrackerViewModel.notifyInteraction(false, userInitiated = true) }
				) {
					AppBackground()
					val currentFragmentClass = rememberCurrentFragmentClass(navigationRepository)
					val isPlaybackScreen = currentFragmentClass in PlaybackFragmentClasses
					// Home renders its own hero art full-bleed behind the sidebar instead of being
					// inset by it - every other screen keeps the inset since their content isn't
					// drawn to expect the rail on top of it.
					//
					// rememberUpdatedState because Sidebar is mounted once inside AndroidView's
					// factory (see below) and stays mounted across ordinary navigation - only a
					// plain State read inside that already-created composition, like this one or
					// userViews below, keeps reflecting the current screen as the user moves between
					// Home and everything else; a captured plain val would freeze at whatever screen
					// was active the first time the sidebar was created.
					val isHomeScreen by rememberUpdatedState(currentFragmentClass == HomeFragment::class)
					SideEffect { isHomeScreenActive = isHomeScreen }
					// Collected at the composition root (not inside Sidebar) because the repository
					// exposes a cold flow that hits the network per collection, and Sidebar is
					// unmounted during playback — collecting there refetched the library list and
					// re-laid-out the rail on every player exit.
					val userViews by remember { userViewsRepository.views }.collectAsState(initial = emptyList())
					// The content is inset by the sidebar's fixed collapsed width and the expanded
					// sidebar overlays it, instead of both sharing a Row. Animating the Row slot
					// width forced the entire Leanback view hierarchy to re-measure on every frame
					// of the expand/collapse animation, which TV hardware can't do smoothly.
					Box(modifier = Modifier.fillMaxSize()) {
						AppNavigationHost(
							modifier = Modifier
								.fillMaxSize()
								.padding(start = if (isPlaybackScreen || isHomeScreen) 0.dp else SidebarCollapsedWidth),
							navigationRepository = navigationRepository,
						)
						if (!isPlaybackScreen) {
							val parentCompositionContext = rememberCompositionContext()
							AndroidView(
								modifier = Modifier.fillMaxHeight(),
								factory = { context ->
									ComposeView(context).apply {
										setParentCompositionContext(parentCompositionContext)
										setContent {
											Sidebar(
												views = userViews,
												onFocusChanged = { sidebarHasFocus = it },
											)
										}
										sidebarView = this
									}
								},
							)
						}
					}
					InAppScreensaver()
					MainActivitySettings()
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()

		if (!validateAuthentication()) return

		applyTheme()

		interactionTrackerViewModel.activityPaused = false
	}

	private fun validateAuthentication(): Boolean {
		if (sessionRepository.currentSession.value == null || userRepository.currentUser.value == null) {
			Timber.w("Activity ${this::class.qualifiedName} started without a session, bouncing to StartupActivity")
			startActivity(Intent(this, StartupActivity::class.java))
			finish()
			return false
		}

		return true
	}

	override fun onPause() {
		super.onPause()

		interactionTrackerViewModel.activityPaused = true
	}

	override fun onStop() {
		super.onStop()

		workManager.enqueue(OneTimeWorkRequestBuilder<LeanbackChannelWorker>().build())

		lifecycleScope.launch(Dispatchers.IO) {
			Timber.i("MainActivity stopped")
			sessionRepository.restoreSession(destroyOnly = true)
		}
	}

	/**
	 * Android's default D-pad focus search treats the sidebar as a valid candidate for Left
	 * presses from anywhere in a Leanback row, not just its first column — the sidebar spans the
	 * full screen height, so it geometrically out-competes the actual previous card in the same
	 * row. Reverting focus after the fact isn't enough because it also throws away whatever
	 * legitimate move Leanback made as part of that same search; instead we exclude the sidebar
	 * from the search entirely (via descendantFocusability, which the platform's FocusFinder
	 * respects) unless we're already at the content's left edge.
	 *
	 * At the left edge we request focus on the sidebar directly rather than letting the search
	 * find it on its own. On Home, whose content deliberately shares the sidebar's own left edge
	 * so the hero's backdrop can bleed under the rail (see Sidebar's overlay param), the focused
	 * view's rect and the sidebar's rect now start at the identical x - and the platform
	 * FocusFinder requires a candidate's edge to be *beyond* the source's in the requested
	 * direction, so two views tied on that edge can't be ordered and the search silently finds
	 * nothing. An explicit request sidesteps that geometry entirely, and behaves identically to
	 * the old search-based handoff on every screen where the search still worked fine.
	 *
	 * The return trip (Right, sidebar back to content) has the identical problem on Home and needs
	 * the identical treatment, but can't be solved the same way: there's no single correct View to
	 * request focus on, since it depends on whether the hero or the rows was actually active, and
	 * that state is private to HomeFragment's own composition. HomeHeroViewModel.focusRequests is
	 * the bridge - see there for why. Gated on isHomeScreenActive because that bridge only reaches
	 * HomeFragment; consuming Right unconditionally would silently break the return trip on every
	 * other screen, where the platform search already lands correctly on its own.
	 */
	private fun dispatchFocusKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !sidebarHasFocus) {
			val focusedBefore = currentFocus
			val rect = Rect()
			focusedBefore?.getGlobalVisibleRect(rect)
			val thresholdPx = SidebarLeftEscapeThresholdDp * resources.displayMetrics.density
			val wasAtLeftEdge = focusedBefore == null || rect.left <= thresholdPx
			// The hero card starts inside the threshold, but Left on it pages back through the
			// carousel until the first slide - it is only truly at the left edge from there.
			val heroCanPageLeft = isHomeScreenActive && homeHeroViewModel.expanded.value && homeHeroViewModel.selectedIndex.value > 0

			if (wasAtLeftEdge && !heroCanPageLeft) return sidebarView?.requestFocus() ?: super.dispatchKeyEvent(event)

			sidebarView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			val consumed = super.dispatchKeyEvent(event)
			sidebarView?.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

			return consumed
		}

		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && sidebarHasFocus && isHomeScreenActive) {
			homeHeroViewModel.requestContentFocus()
			return true
		}

		return super.dispatchKeyEvent(event)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		// Armed before dispatching, not after: focus changes inside the dispatch itself (e.g. between
		// Leanback cards) notify NavigationSound synchronously, before the result is known.
		val isNavigation = event.action == KeyEvent.ACTION_DOWN && navigationSound.arm(event.keyCode)
		val consumed = dispatchFocusKeyEvent(event)
		// Unconsumed presses fall through to the platform's own focus navigation, which clicks itself
		if (isNavigation && !consumed) navigationSound.disarm()
		return consumed
	}

	// Forward key events to fragments

	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean = supportFragmentManager.fragments
		.any { it.onKeyEvent(keyCode, event) }

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)
}
