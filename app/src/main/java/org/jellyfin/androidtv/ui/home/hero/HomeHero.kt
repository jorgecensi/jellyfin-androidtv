package org.jellyfin.androidtv.ui.home.hero

import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.AccentColor
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.modifier.overscanPaddingValues
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.NavigationSound
import org.jellyfin.androidtv.ui.shared.sidebar.SidebarCollapsedWidth
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.sdk.getFirstPerson
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PersonKind
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

private val RotationInterval = 10.seconds
private val MaxLogoHeight = 96.dp
private val HomeHeroCardMargin = 24.dp
private val HomeHeroFocusBorderWidth = 3.dp

/**
 * Netflix-style featured banner shown above the home screen rows, framed as a single rounded card
 * inset from the screen edges rather than a full-bleed background.
 *
 * Always composed at its full height; the caller slides it off the top of the screen when focus
 * moves into the rows. Shrinking it instead would put the card's focus node at risk, and would
 * leave a band of dead space with nothing to put in it.
 *
 * The whole card is a single focusable/clickable surface rather than separate Play/More Info
 * buttons: clicking it (or pressing D-pad center) opens the details page for whichever item is
 * currently showing. Left/Right page through the carousel instead of relying purely on the 10
 * second auto-rotation - except Left at the first item, which is deliberately left unconsumed so
 * MainActivity's sidebar escape guard still gets it, exactly like the old leftmost Play button did.
 *
 * Renders its own backdrop rather than driving [org.jellyfin.androidtv.data.service.BackgroundService]:
 * that service tints and optionally blurs every image, paints behind the sidebar too, and runs its
 * own 30 second slideshow that would desync from this carousel. While the banner is expanded the
 * global backdrop is suppressed by HomeRowsFragment instead, so the two never overlap.
 */
@Composable
fun HomeHero(
	items: List<BaseItemDto>,
	selectedIndex: Int,
	expanded: Boolean,
	height: Dp,
	modifier: Modifier = Modifier,
	onNext: () -> Unit = {},
	onSelectIndex: (Int) -> Unit = {},
	onFocusChanged: (Boolean) -> Unit = {},
	focusRequester: FocusRequester? = null,
	downFocusRequester: FocusRequester? = null,
) {
	val item = items.getOrNull(selectedIndex) ?: return
	val api = koinInject<ApiClient>()
	val navigationRepository = koinInject<NavigationRepository>()
	val navigationSound = koinInject<NavigationSound>()

	val interactionSource = remember { MutableInteractionSource() }
	// Driven directly by onFocusChanged below rather than interactionSource.collectIsFocusedAsState():
	// clickable()'s own interaction source doesn't reliably emit a focus interaction when focus is
	// handed to this card programmatically (HomeFragment's initial-focus and hand-off-from-sidebar
	// requestFocus() calls) rather than gained through natural D-pad traversal - onFocusChanged is the
	// lower-level, more direct Compose API and reports both cases correctly.
	var focused by remember { mutableStateOf(false) }
	val borderColor by animateColorAsState(if (focused) AccentColor else Color.Transparent, label = "heroFocusBorder")

	// Bumped on every key press so the rotation effect below restarts its delay. This is the whole
	// pause mechanism: rotating while the user is actively navigating would move the click target
	// out from under them mid-decision.
	var lastInteraction by remember { mutableLongStateOf(0L) }

	HomeHeroRotation(
		itemCount = items.size,
		enabled = expanded,
		lastInteraction = lastInteraction,
		onNext = onNext,
	)

	Box(
		modifier = modifier
			.height(height)
			// Rounded card with a margin on every side rather than full-bleed art - the backdrop no
			// longer needs to bleed under the sidebar now that it's not the only thing on screen;
			// the sidebar's own gradient overlay (see Sidebar.kt) still applies everywhere. Extra
			// clearance on the start side keeps all four corners clear of the collapsed rail.
			.padding(
				start = SidebarCollapsedWidth + HomeHeroCardMargin,
				top = HomeHeroCardMargin,
				end = HomeHeroCardMargin,
				bottom = HomeHeroCardMargin,
			)
			.clip(JellyfinTheme.shapes.large)
			.border(HomeHeroFocusBorderWidth, borderColor, JellyfinTheme.shapes.large)
			// All focus-related modifiers precede clickable() below, matching the order the app's
			// own Button/ButtonBase already establish as working: a FocusRequester (and the other
			// focus modifiers here) must sit outside the modifier that actually creates the
			// focusable/clickable target, or requestFocus() silently no-ops and initial focus falls
			// through to whatever the platform defaults to next - observed as focus landing on the
			// sidebar instead of the hero on cold start when this was ordered the other way around.
			// Nothing focusable sits above the banner. Left to the default search, Up escapes the
			// Compose tree and the platform picks an arbitrary view (typically the sidebar).
			.focusProperties {
				up = FocusRequester.Cancel
				down = downFocusRequester ?: FocusRequester.Default
			}
			.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
			.onFocusChanged {
				focused = it.hasFocus
				onFocusChanged(it.hasFocus)
			}
			.onKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
				lastInteraction = System.currentTimeMillis()

				when (event.key) {
					Key.DirectionRight -> {
						if (selectedIndex < items.lastIndex) {
							onSelectIndex(selectedIndex + 1)
							// Paging doesn't move focus, so nothing else would click for it
							navigationSound.notifyMoved()
						}
						// Consumed either way - there is nothing to the right of the hero to hand off to.
						true
					}

					Key.DirectionLeft -> {
						if (selectedIndex > 0) {
							onSelectIndex(selectedIndex - 1)
							navigationSound.notifyMoved()
							true
						} else {
							// Not consumed: lets MainActivity's sidebar escape guard take over, exactly
							// like the old leftmost Play button did.
							false
						}
					}

					else -> false
				}
			}
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				role = Role.Button,
				onClick = { navigationRepository.navigate(Destinations.itemDetails(item.id)) },
			),
	) {
		HomeHeroBackdrop(item = item, api = api)

		HomeHeroScrim()

		HomeHeroDetails(
			item = item,
			api = api,
			modifier = Modifier.align(Alignment.BottomStart),
		)

		HomeHeroPageIndicator(
			count = items.size,
			selectedIndex = selectedIndex,
			modifier = Modifier
				.align(Alignment.BottomEnd)
				.padding(overscanPaddingValues),
		)

		// Migrated from MainToolbar, which the sidebar redesign made redundant on this screen.
		ToolbarClock(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(overscanPaddingValues),
		)
	}
}

/**
 * Advances the carousel every [RotationInterval] while [enabled], restarting whenever
 * [lastInteraction] changes.
 */
@Composable
private fun HomeHeroRotation(
	itemCount: Int,
	enabled: Boolean,
	lastInteraction: Long,
	onNext: () -> Unit,
) {
	val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

	LaunchedEffect(itemCount, enabled, lastInteraction, lifecycleOwner) {
		if (itemCount <= 1 || !enabled) return@LaunchedEffect

		// repeatOnLifecycle so the carousel stops churning through full-screen image loads while
		// the app is backgrounded or a player fragment is on top - the composition survives both.
		lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			while (true) {
				delay(RotationInterval)
				onNext()
			}
		}
	}
}

@Composable
private fun HomeHeroBackdrop(
	item: BaseItemDto,
	api: ApiClient,
	modifier: Modifier = Modifier,
) {
	val metrics = LocalContext.current.resources.displayMetrics
	val backdrop = remember(item) { (item.itemBackdropImages + item.parentBackdropImages).firstOrNull() }
	// Bounded to the display size. Source backdrops are routinely 4K and decoding those at full
	// resolution is the single biggest source of jank and OOM on TV hardware.
	val backdropUrl = remember(backdrop, metrics) {
		backdrop?.getUrl(api, maxWidth = metrics.widthPixels, maxHeight = metrics.heightPixels)
	}

	Crossfade(
		targetState = backdropUrl to backdrop?.blurHash,
		animationSpec = tween(durationMillis = 600),
		label = "heroBackdrop",
		modifier = modifier.fillMaxSize(),
	) { (url, blurHash) ->
		AsyncImage(
			url = url,
			blurHash = blurHash,
			aspectRatio = 16f / 9f,
			scaleType = ImageView.ScaleType.CENTER_CROP,
			modifier = Modifier.fillMaxSize(),
		)
	}
}

/**
 * Darkens the left portion so the text column stays legible over any backdrop. Unlike the previous
 * full-bleed version, there is no bottom fade to black: the card now has a real edge of its own
 * (rounded corners, a margin, a border on focus) rather than needing to blend into whatever was
 * below it.
 */
@Composable
private fun HomeHeroScrim() {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(
				// Holds most of its weight across the full width of the text column rather than
				// fading out inside it. A single 0.85-to-transparent ramp left white text sitting
				// on near-white artwork at the right edge of the overview on light backdrops.
				Brush.horizontalGradient(
					0f to Color.Black.copy(alpha = 0.92f),
					0.45f to Color.Black.copy(alpha = 0.65f),
					0.8f to Color.Transparent,
				)
			),
	)
}

@Composable
private fun HomeHeroDetails(
	item: BaseItemDto,
	api: ApiClient,
	modifier: Modifier = Modifier,
) {
	Box(modifier = modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier
				.fillMaxWidth(0.55f)
				.padding(overscanPaddingValues),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			// The whole text block crossfades between items - there is no focusable content inside
			// it anymore to worry about losing focus, unlike when this held the action buttons.
			Crossfade(
				targetState = item,
				animationSpec = tween(durationMillis = 300),
				label = "heroText",
			) { crossfadedItem ->
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					HomeHeroTitle(item = crossfadedItem, api = api)

					// Community/critic rating, runtime, and the parental rating badge. Height is
					// bounded explicitly: BaseItemInfoRow's chips use fillMaxHeight() internally,
					// which is normally tamed by the fixed-height legacy View it's wrapped in
					// elsewhere in the app - inside this unconstrained Column they would otherwise
					// stretch to the Column's full available height. Runtime is left off for series:
					// item.runTimeTicks is frequently 0 for a series (it's a per-episode concept),
					// and the app's own detail-page presenter for this same component skips runtime
					// for exactly that reason (MyDetailsOverviewRowPresenter.kt).
					Box(modifier = Modifier.height(28.dp)) {
						BaseItemInfoRow(
							item = crossfadedItem,
							mediaSource = null,
							includeRuntime = crossfadedItem.type != BaseItemKind.SERIES,
						)
					}

					HomeHeroMetaLine(item = crossfadedItem)

					crossfadedItem.taglines?.firstOrNull()?.let { tagline ->
						Text(
							text = tagline,
							fontSize = 14.sp,
							color = Color.White.copy(alpha = 0.85f),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}

					crossfadedItem.overview?.let { overview ->
						Text(
							text = overview,
							fontSize = 15.sp,
							color = Color.White.copy(alpha = 0.85f),
							maxLines = 3,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

/** The title logo when the server has one, falling back to plain text. */
@Composable
private fun HomeHeroTitle(
	item: BaseItemDto,
	api: ApiClient,
) {
	val logo = remember(item) { item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO] }
	val logoUrl = remember(logo) { logo?.getUrl(api, maxHeight = 200) }

	if (logoUrl != null) {
		// Capped because some servers carry very tall logo artwork that would otherwise push the
		// rest of the text column off the banner.
		AsyncImage(
			url = logoUrl,
			blurHash = logo?.blurHash,
			aspectRatio = 16f / 9f,
			modifier = Modifier.heightIn(max = MaxLogoHeight),
		)
	} else {
		Text(
			text = item.name.orEmpty(),
			fontSize = 34.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

private const val MaxGenresShown = 3

/**
 * "Genre, Genre, Genre · Directed by Name" - omits either half when the server has no data for it,
 * and disappears entirely rather than leaving a blank line when neither is present.
 */
@Composable
private fun HomeHeroMetaLine(item: BaseItemDto) {
	val genres = remember(item) { item.genres?.take(MaxGenresShown)?.joinToString(", ") }
	val director = remember(item) { item.getFirstPerson(PersonKind.DIRECTOR)?.name }
	val directedByLabel = stringResource(R.string.lbl_directed_by)

	val text = remember(genres, director, directedByLabel) {
		listOfNotNull(
			genres,
			director?.let { "$directedByLabel $it" },
		).joinToString(" · ")
	}

	if (text.isNotEmpty()) {
		Text(
			text = text,
			fontSize = 14.sp,
			color = Color.White.copy(alpha = 0.75f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
