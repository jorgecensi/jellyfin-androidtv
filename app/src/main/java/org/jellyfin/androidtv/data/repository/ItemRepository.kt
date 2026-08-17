package org.jellyfin.androidtv.data.repository

import org.jellyfin.sdk.model.api.ItemFields

object ItemRepository {
	val itemFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHANNEL_INFO,
		ItemFields.CHAPTERS,
		ItemFields.CHILD_COUNT,
		ItemFields.CUMULATIVE_RUN_TIME_TICKS,
		ItemFields.DATE_CREATED,
		ItemFields.DISPLAY_PREFERENCES_ID,
		ItemFields.GENRES,
		ItemFields.ITEM_COUNTS,
		ItemFields.MEDIA_SOURCE_COUNT,
		ItemFields.MEDIA_SOURCES,
		ItemFields.MEDIA_STREAMS,
		ItemFields.OVERVIEW,
		ItemFields.PATH,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.TAGLINES,
		ItemFields.TRICKPLAY,
	)

	// Lighter field set for home screen rows - excludes heavy fields like
	// MediaSources, MediaStreams, Chapters, Trickplay that aren't needed for display.
	// Full item data is fetched when user selects an item.
	val browseFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHILD_COUNT,
		ItemFields.DATE_CREATED,
		ItemFields.GENRES,
		ItemFields.OVERVIEW,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
	)

	// Field set for the home screen hero banner. Deliberately not browseFields, which omits the
	// taglines the banner shows, and deliberately not itemFields, whose media sources, streams,
	// chapters and trickplay data are pure waste for a banner that never plays in place. PEOPLE is
	// included so the director can be shown; community/critic/official rating and production year
	// need no field flag - they are returned unconditionally.
	val featuredFields = setOf(
		ItemFields.DATE_CREATED,
		ItemFields.GENRES,
		ItemFields.OVERVIEW,
		ItemFields.PEOPLE,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.TAGLINES,
	)
}
