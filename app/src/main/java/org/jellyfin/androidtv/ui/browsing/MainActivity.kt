package org.jellyfin.androidtv.ui.browsing

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideLocalInteractionTracker
import org.jellyfin.androidtv.ui.composable.compat.AppNavigationHost
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.shared.sidebar.Sidebar
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
 * Tracks the currently visible [Destination.Fragment] by mirroring [NavigationRepository]'s
 * push/replace/clear/back-navigation behavior, so the sidebar can hide itself while a video
 * player fragment is on screen.
 */
@Composable
private fun rememberIsPlaybackScreen(navigationRepository: NavigationRepository): Boolean {
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

	return screenStack.lastOrNull() in PlaybackFragmentClasses
}

class MainActivity : FragmentActivity() {
	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	private val workManager by inject<WorkManager>()

	// Updated synchronously by Sidebar's onFocusChanged callback (not Compose state) so
	// dispatchKeyEvent can read a reliably up-to-date value within the same key event.
	private var sidebarHasFocus = false

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
					val isPlaybackScreen = rememberIsPlaybackScreen(navigationRepository)
					Row(modifier = Modifier.fillMaxSize()) {
						if (!isPlaybackScreen) {
							val parentCompositionContext = rememberCompositionContext()
							AndroidView(
								modifier = Modifier.fillMaxHeight(),
								factory = { context ->
									ComposeView(context).apply {
										setParentCompositionContext(parentCompositionContext)
										setContent {
											Sidebar(onFocusChanged = { sidebarHasFocus = it })
										}
										sidebarView = this
									}
								},
							)
						}
						AppNavigationHost(
							modifier = Modifier.weight(1f),
							navigationRepository = navigationRepository,
						)
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
	 * respects) unless we're already at the content's left edge, letting the search naturally
	 * fall back to the correct card within the row.
	 */
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !sidebarHasFocus) {
			val focusedBefore = currentFocus
			val rect = Rect()
			focusedBefore?.getGlobalVisibleRect(rect)
			val thresholdPx = SidebarLeftEscapeThresholdDp * resources.displayMetrics.density
			val wasAtLeftEdge = focusedBefore == null || rect.left <= thresholdPx

			if (!wasAtLeftEdge) sidebarView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

			val consumed = super.dispatchKeyEvent(event)

			sidebarView?.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

			return consumed
		}

		return super.dispatchKeyEvent(event)
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
