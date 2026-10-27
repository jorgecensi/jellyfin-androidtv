package org.jellyfin.androidtv.ui.shared.sidebar

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.AccentColor
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.ProfilePicture
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonBase
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.IconButtonDefaults
import org.jellyfin.androidtv.ui.base.modifier.focusScale
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationSound
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

/** Width of the collapsed icon rail; content is inset by exactly this amount. */
val SidebarCollapsedWidth = 72.dp

private val SidebarExpandedWidth = 240.dp

/**
 * Persistent icon rail shown alongside the current screen, replacing per-screen top navigation.
 * Houses the same destinations [org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar] exposes
 * today (Home, Search, Settings, user switch) plus a direct link per user library. Collapses to
 * an icon-only rail once focus leaves it, and expands to show labels for every item while any
 * part of it has D-pad focus.
 *
 * Rendered as a Netflix-style overlay rather than an opaque panel: instead of a solid fill, the
 * rail is backed by a gradient running black at the screen edge to fully transparent at its own
 * right edge, so it grounds the icons without cutting a hard band across whatever sits beneath -
 * the home screen's hero art directly, and [org.jellyfin.androidtv.ui.background.AppBackground]'s
 * persistent backdrop on every other screen, whose content stays inset clear of the rail as before.
 */
@Composable
fun Sidebar(
	views: Collection<BaseItemDto>,
	onFocusChanged: (Boolean) -> Unit = {},
) {
	val navigationRepository = koinInject<NavigationRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val userRepository = koinInject<UserRepository>()
	val api = koinInject<ApiClient>()
	val mediaManager = koinInject<MediaManager>()
	val sessionRepository = koinInject<SessionRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val activity = LocalActivity.current
	val coroutineScope = rememberCoroutineScope()

	val currentUser by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val userImage = remember(currentUser) { currentUser?.primaryImage?.getUrl(api) }

	val focusRequester = remember { FocusRequester() }
	var expanded by remember { mutableStateOf(false) }
	val width by animateDpAsState(if (expanded) SidebarExpandedWidth else SidebarCollapsedWidth, label = "sidebarWidth")

	Column(
		modifier = Modifier
			.fillMaxHeight()
			.width(width)
			// Spans the rail's own width, so it stretches with the expand animation and never
			// darkens content beyond the rail itself.
			.background(
				Brush.horizontalGradient(
					0f to Color.Black.copy(alpha = 0.9f),
					1f to Color.Transparent,
				)
			)
			.focusRestorer(focusRequester)
			.onFocusChanged {
				expanded = it.hasFocus
				onFocusChanged(it.hasFocus)
			}
			.focusGroup()
			.padding(horizontal = 12.dp, vertical = 16.dp),
		horizontalAlignment = Alignment.Start,
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		SidebarButton(
			icon = R.drawable.ic_house,
			label = stringResource(R.string.lbl_home),
			expanded = expanded,
			onClick = { navigationRepository.navigate(Destinations.home, replace = true) },
		)

		SidebarButton(
			icon = R.drawable.ic_search,
			label = stringResource(R.string.lbl_search),
			expanded = expanded,
			modifier = Modifier.focusRequester(focusRequester),
			onClick = { navigationRepository.navigate(Destinations.search()) },
		)

		SidebarButton(
			icon = R.drawable.ic_shuffle,
			label = stringResource(R.string.lbl_shuffle),
			expanded = expanded,
			onClick = {
				activity?.let { currentActivity ->
					coroutineScope.launch { shuffleAndPlay(currentActivity, api, playbackHelper) }
				}
			},
		)

		// Library views can outnumber the space left between the fixed top and bottom buttons -
		// scrolling just this section (instead of relying on the old weighted Spacer to eat the
		// slack) keeps Settings and the user switcher reachable, and visible, no matter how many
		// libraries the server has, instead of them being pushed off the bottom of the rail.
		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			for (view in views) {
				SidebarButton(
					icon = view.collectionType.sidebarIcon(),
					label = view.name.orEmpty(),
					expanded = expanded,
					onClick = { itemLauncher.launchUserView(view) },
				)
			}
		}

		SidebarButton(
			icon = R.drawable.ic_settings,
			label = stringResource(R.string.lbl_settings),
			expanded = expanded,
			onClick = { settingsViewModel.show() },
		)

		SidebarButton(
			label = stringResource(R.string.lbl_switch_user),
			expanded = expanded,
			onClick = {
				mediaManager.clearAudioQueue()
				sessionRepository.destroyCurrentSession()

				activity?.startActivity(ActivityDestinations.startup(activity))
				activity?.finishAfterTransition()
			},
			leadingIcon = {
				ProfilePicture(
					url = userImage,
					contentDescription = null,
					modifier = Modifier
						.size(20.dp)
						.aspectRatio(1f)
						.clip(IconButtonDefaults.Shape)
				)
			},
		)
	}
}

@Composable
private fun SidebarButton(
	icon: Int,
	label: String,
	expanded: Boolean,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
) = SidebarButton(
	label = label,
	expanded = expanded,
	modifier = modifier,
	onClick = onClick,
	leadingIcon = {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = null,
		)
	},
)

@Composable
private fun SidebarButton(
	label: String,
	expanded: Boolean,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
	leadingIcon: @Composable () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val navigationSound = koinInject<NavigationSound>()

	ButtonBase(
		onClick = onClick,
		interactionSource = interactionSource,
		shape = IconButtonDefaults.Shape,
		colors = ButtonDefaults.colors(
			containerColor = Color.Transparent,
			disabledContainerColor = Color.Transparent,
			// The app-wide focus color is overridden here specifically - see AccentColor.
			focusedContainerColor = AccentColor,
			focusedContentColor = Color.White,
		),
		modifier = modifier
			.fillMaxWidth()
			.focusScale(interactionSource)
			// Moving between buttons stays inside the sidebar's own ComposeView, invisible to
			// MainActivity's View-level focus listener
			.onFocusChanged { if (it.isFocused) navigationSound.notifyMoved() },
	) {
		Row(
			modifier = Modifier.padding(IconButtonDefaults.ContentPadding),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			leadingIcon()

			if (expanded) {
				Text(
					text = label,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/**
 * Picks a random movie or series from the whole library and starts playback. For a series this
 * resumes (or starts) at whichever episode [org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment]'s
 * own Play button would land on, via the same next-up lookup.
 */
private suspend fun shuffleAndPlay(
	context: Context,
	api: ApiClient,
	playbackHelper: PlaybackHelper,
) {
	val item = withContext(Dispatchers.IO) {
		val response by api.itemsApi.getItems(
			includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			recursive = true,
			isMissing = false,
			sortBy = listOf(ItemSortBy.RANDOM),
			limit = 1,
		)
		response.items.firstOrNull()
	} ?: return

	val playableId = when (item.type) {
		BaseItemKind.SERIES -> withContext(Dispatchers.IO) {
			val response by api.tvShowsApi.getNextUp(seriesId = item.id, limit = 1)
			response.items.firstOrNull()?.id
		}
		else -> item.id
	}

	if (playableId == null) Toast.makeText(context, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
	else playbackHelper.retrieveAndPlay(playableId, false, context)
}

private fun CollectionType?.sidebarIcon(): Int = when (this) {
	CollectionType.MOVIES -> R.drawable.ic_movie
	CollectionType.TVSHOWS -> R.drawable.ic_tv
	CollectionType.MUSIC -> R.drawable.ic_music_album
	CollectionType.PHOTOS -> R.drawable.ic_photo
	CollectionType.LIVETV -> R.drawable.ic_tv_play
	else -> R.drawable.ic_folder
}
