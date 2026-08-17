package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber

/**
 * Supplies the candidates for the home screen's featured hero banner: recently added movies and
 * series the user hasn't started watching yet.
 */
interface HomeFeaturedRepository {
	suspend fun getFeaturedItems(limit: Int = DEFAULT_FEATURED_LIMIT): List<BaseItemDto>

	companion object {
		const val DEFAULT_FEATURED_LIMIT = 8
	}
}

class HomeFeaturedRepositoryImpl(
	private val api: ApiClient,
) : HomeFeaturedRepository {
	private companion object {
		// Over-fetched because the backdrop requirement below discards a meaningful share of any
		// real library. Gives the filters headroom without needing a second round-trip.
		const val CANDIDATE_LIMIT = 30
		const val RESUME_LOOKUP_LIMIT = 50
	}

	override suspend fun getFeaturedItems(limit: Int): List<BaseItemDto> = withContext(Dispatchers.IO) {
		runCatching {
			coroutineScope {
				// "Recently added" is a server-side filter but "not started yet" is not: isPlayed=false
				// still returns half-watched movies and series with a single in-progress episode, so the
				// resume list is subtracted from the candidates in selectFeaturedItems below.
				val resumeItems = async {
					val response by api.itemsApi.getResumeItems(
						limit = RESUME_LOOKUP_LIMIT,
						mediaTypes = listOf(MediaType.VIDEO),
						enableTotalRecordCount = false,
						enableImages = false,
					)
					response.items
				}

				val candidates = async {
					val response by api.itemsApi.getItems(
						includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
						recursive = true,
						isPlayed = false,
						isMissing = false,
						sortBy = listOf(ItemSortBy.DATE_CREATED),
						sortOrder = listOf(SortOrder.DESCENDING),
						limit = CANDIDATE_LIMIT,
						fields = ItemRepository.featuredFields,
						imageTypeLimit = 1,
						enableImageTypes = listOf(ImageType.BACKDROP, ImageType.LOGO, ImageType.PRIMARY),
						enableTotalRecordCount = false,
					)
					response.items
				}

				selectFeaturedItems(
					candidates = candidates.await(),
					resumeItems = resumeItems.await(),
					limit = limit,
				)
			}
		}.getOrElse { error ->
			// runCatching swallows CancellationException too, which would break structured
			// concurrency: a cancelled load would "succeed" with an empty list and publish that as
			// a real result. Cancellation has to propagate.
			if (error is CancellationException) throw error

			// Any genuine failure degrades to no banner. The home screen must still load.
			Timber.w(error, "Unable to retrieve featured items")
			emptyList()
		}
	}
}

/**
 * Narrows the raw "recently added and unplayed" candidates down to what the banner can actually
 * show, by dropping anything the user has already started and anything without a backdrop.
 *
 * Note [resumeItems] holds *episodes* rather than their series, so they are mapped through
 * `seriesId` first - otherwise a series with one in-progress episode would still read as "not
 * started". This remains an approximation: there is no server-side "never started" filter, so a
 * series whose only watched episode was manually marked played still surfaces here.
 */
internal fun selectFeaturedItems(
	candidates: List<BaseItemDto>,
	resumeItems: List<BaseItemDto>,
	limit: Int,
): List<BaseItemDto> {
	val excluded = resumeItems.map { it.seriesId ?: it.id }.toSet()

	return candidates
		.filter { it.id !in excluded }
		// A banner without a backdrop is not a banner. There is no server-side filter for
		// "has an image of type X", so this is necessarily client-side.
		.filter { (it.itemBackdropImages + it.parentBackdropImages).isNotEmpty() }
		.take(limit)
}
