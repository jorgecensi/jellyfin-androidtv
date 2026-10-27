package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.home.hero.HomeHero
import org.jellyfin.androidtv.ui.home.hero.HomeHeroState
import org.jellyfin.androidtv.ui.home.hero.HomeHeroViewModel
import org.jellyfin.androidtv.ui.shared.sidebar.SidebarCollapsedWidth
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

private const val HomeHeroExpandedHeightFraction = 0.70f
private const val HomeHeroCollapseDurationMs = 280

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val heroViewModel by activityViewModel<HomeHeroViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val heroState by heroViewModel.state.collectAsState()
		val heroSelectedIndex by heroViewModel.selectedIndex.collectAsState()
		val heroEnabled = remember { userPreferences[UserPreferences.homeHeroEnabled] }

		val heroItems = (heroState as? HomeHeroState.Ready)?.items.orEmpty()
		val heroVisible = heroEnabled && heroItems.isNotEmpty()

		val rowsFocusRequester = remember { FocusRequester() }
		val heroFocusRequester = remember { FocusRequester() }

		// Two observers latching a single boolean rather than one `expanded = heroHasFocus`: during
		// a D-pad handoff there is a frame where neither side holds focus, and tracking that
		// directly would collapse and re-expand the banner with a visible bounce.
		var heroExpanded by remember { mutableStateOf(false) }
		var requestHeroFocus by remember { mutableStateOf(false) }

		// Hoisted above the effects below because the focusRequests collector needs the Leanback
		// grid View to hand focus back to; assigned from AndroidFragment's onUpdate further down.
		var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }

		// Keyed on both, not just heroExpanded: heroVisible flips independently when the featured
		// query answers, and this must re-publish then too or HomeRowsFragment keeps suppressing the
		// global backdrop based on a stale value.
		LaunchedEffect(heroExpanded, heroVisible) { heroViewModel.setExpanded(heroExpanded && heroVisible) }

		// Invisible, pure-Compose focus target claimed immediately, before the featured query has
		// any chance to resolve - this is what keeps the sidebar collapsed on cold start. Leaving
		// nothing focused in HomeFragment's own tree during that gap let Android's platform-level
		// "auto-focus the first eligible view" default win the race and land on the sidebar instead
		// (visible as it popping open, expanded, right as the app launches).
		//
		// Deliberately not the rows: claiming the rows (a wrapped native Leanback View) first and
		// then stealing focus back to the hero moments later broke the hero's own focus border -
		// keyboard input still correctly reached it, but Compose's focus-change notifications don't
		// reliably fire when focus moves off a native View back onto a plain Compose node. This
		// placeholder is pure Compose on both ends of every handoff below, so it never crosses that
		// boundary at all.
		//
		// Only focusable until the hero or rows actually take focus. Left focusable after that, it
		// sits geometrically above the banner, so D-pad Up from the hero moved focus onto this
		// invisible node and the user lost track of focus entirely. Not tied to initialFocusApplied
		// below: that flips a frame before the deferred hero request lands, and disabling the
		// placeholder in that gap would recreate the exact empty-focus race it exists to prevent.
		val placeholderFocusRequester = remember { FocusRequester() }
		var placeholderFocusable by remember { mutableStateOf(true) }
		LaunchedEffect(Unit) {
			placeholderFocusRequester.requestFocus()
		}

		// Decided once the featured query has actually answered. Acting while it is still Loading
		// would focus the rows and leave the banner unreachable for the rest of the session; the
		// user navigating down in the meantime must also win over a late-arriving banner.
		var initialFocusApplied by remember { mutableStateOf(false) }
		LaunchedEffect(heroState, heroEnabled) {
			if (initialFocusApplied || heroState is HomeHeroState.Loading) return@LaunchedEffect

			if (heroVisible) {
				heroExpanded = true
				// Not a direct heroFocusRequester.requestFocus() call: heroVisible just flipped
				// true in this same effect, and HomeHero (below, gated on heroVisible) may not have
				// finished composing and attaching its FocusRequester yet in this pass. Bouncing
				// through requestHeroFocus - the same mechanism onExit below already uses to escape
				// its own re-entrant-focus-transaction problem - defers the request to a later
				// recomposition where the node reliably exists.
				requestHeroFocus = true
			} else {
				rowsFocusRequester.requestFocus()
			}
			initialFocusApplied = true
		}

		// FocusRequester.requestFocus() must not be called from inside onExit below - Compose's
		// focus transaction is already open there and a re-entrant request is dropped. Bounce it
		// through state so the request lands outside the traversal instead.
		LaunchedEffect(requestHeroFocus) {
			if (requestHeroFocus) {
				heroFocusRequester.requestFocus()
				requestHeroFocus = false
			}
		}

		// MainActivity asks for this explicitly (see HomeHeroViewModel.focusRequests) when the
		// sidebar hands focus back to Home on Right - Compose's own directional search from an
		// external view lands on whichever composable it finds first in declaration order (the
		// rows), regardless of which side the user actually came from.
		//
		// heroVisible is read through rememberUpdatedState, not captured directly: this collector is
		// keyed on Unit so it is created exactly once, while heroVisible is a plain Boolean derived
		// from heroState during composition. Capturing it directly pinned it to its value at first
		// composition - false, because the featured query was still Loading - so every later request
		// routed to the rows even with the banner on screen and focused. (heroExpanded is safe to
		// read directly only because it is a MutableState delegate, which reads live.)
		val currentHeroVisible by rememberUpdatedState(heroVisible)
		LaunchedEffect(Unit) {
			heroViewModel.focusRequests.collect {
				if (heroExpanded && currentHeroVisible) {
					heroFocusRequester.requestFocus()
				} else {
					// The rows branch goes at the Leanback View directly rather than through
					// rowsFocusRequester. The request originates while the sidebar's own separate
					// ComposeView holds platform focus; a Compose FocusRequester aimed at the
					// focusGroup wrapping the fragment doesn't reliably pull platform focus across
					// that boundary into the Leanback hierarchy, so focus just stayed in the sidebar.
					// A plain View.requestFocus() on the grid does, and is what Leanback expects.
					rowsSupportFragment?.verticalGridView?.requestFocus() ?: rowsFocusRequester.requestFocus()
				}
			}
		}

		// Deliberately a plain Box with the expanded height derived from the screen, not a
		// BoxWithConstraints. BoxWithConstraints subcomposes its content during the layout phase,
		// so the FocusRequesters below would still be unattached when the effects above run - both
		// the banner and the rows would silently fail to take focus and the sidebar would keep it.
		val expandedHeight = LocalConfiguration.current.screenHeightDp.dp * HomeHeroExpandedHeightFraction

		Box(modifier = Modifier.fillMaxSize()) {
			// See placeholderFocusRequester above for why this exists. 0dp and non-drawing: it must
			// still be a real, measured layout node for focusRequester()/focusable() to attach to,
			// but has nothing to show and is never meant to be visible even for a single frame.
			Spacer(
				modifier = Modifier
					.size(0.dp)
					.focusRequester(placeholderFocusRequester)
					.focusable(enabled = placeholderFocusable)
			)

			val collapseProgress by animateFloatAsState(
				targetValue = if (heroExpanded && heroVisible) 0f else 1f,
				animationSpec = tween(durationMillis = HomeHeroCollapseDurationMs, easing = FastOutSlowInEasing),
				label = "heroCollapse",
			)

			// The leanback code has its own awful focus handling that doesn't work properly with Compose view inteop to workaround this
			// issue we add custom behavior that only allows focus exit when the current selected row is the first one. Additionally when
			// we do switch the focus, we reset the leanback state so it won't cause weird behavior when focus is regained
			AndroidFragment<HomeRowsFragment>(
				modifier = Modifier
					.fillMaxSize()
					// Home is no longer inset by MainActivity - its content extends full-bleed so
					// the hero's backdrop can bleed under the sidebar (see Sidebar's overlay param).
					// The rows themselves still need to clear the collapsed rail though, so that
					// inset is restored locally here. Static, not animated, so it's a one-time layout
					// pass rather than the per-frame re-measure the comment below is avoiding.
					.padding(start = SidebarCollapsedWidth)
					// Always measured at the full screen height and pushed down only *visually* by
					// this draw-phase translation, so the rows own the whole screen the moment the
					// banner is out of the way. Animating height or padding instead would re-measure
					// the entire Leanback hierarchy every frame - the same trap documented in
					// MainActivity for the sidebar's width animation. The lambda form keeps the
					// offset read in the draw phase so it never even recomposes.
					.graphicsLayer {
						translationY = if (heroVisible) expandedHeight.toPx() * (1f - collapseProgress) else 0f
					}
					.focusGroup()
					.focusRequester(rowsFocusRequester)
					.onFocusChanged {
						if (it.hasFocus) {
							heroExpanded = false
							placeholderFocusable = false
						}
					}
					.focusProperties {
						onExit = {
							val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
							if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
								cancelFocusChange()
							} else {
								rowsSupportFragment?.selectedPosition = 0
								rowsSupportFragment?.verticalGridView?.clearFocus()
								if (heroVisible) requestHeroFocus = true
							}
						}
					},
				onUpdate = { fragment ->
					rowsSupportFragment = fragment
				}
			)

			if (heroVisible) {
				HomeHero(
					items = heroItems,
					selectedIndex = heroSelectedIndex,
					expanded = heroExpanded,
					height = expandedHeight,
					onNext = heroViewModel::next,
					onSelectIndex = heroViewModel::select,
					onFocusChanged = { hasFocus ->
						if (hasFocus) {
							heroExpanded = true
							placeholderFocusable = false
						}
					},
					focusRequester = heroFocusRequester,
					downFocusRequester = rowsFocusRequester,
					modifier = Modifier
						.fillMaxWidth()
						.align(Alignment.TopStart)
						// Slides straight up out of view rather than shrinking, keeping the card at
						// full size and its focus node intact the whole way, so Up from the first
						// row can still hand focus back to it.
						.graphicsLayer {
							translationY = -expandedHeight.toPx() * collapseProgress
							alpha = 1f - collapseProgress
						}
						.focusRestorer(heroFocusRequester),
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}

	override fun onResume() {
		super.onResume()

		// The banner is refreshed on resume rather than via websocket subscriptions - HomeRowsFragment
		// already subscribes to library changes, and a featured banner doesn't need that immediacy.
		heroViewModel.refreshIfStale()
	}
}
