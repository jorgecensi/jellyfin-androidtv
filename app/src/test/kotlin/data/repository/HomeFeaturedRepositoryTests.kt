package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

private fun item(
	id: UUID = UUID.randomUUID(),
	seriesId: UUID? = null,
	backdropTags: List<String>? = listOf("backdrop-tag"),
	parentBackdropId: UUID? = null,
	parentBackdropTags: List<String>? = null,
) = mockk<BaseItemDto> {
	every { this@mockk.id } returns id
	every { this@mockk.seriesId } returns seriesId
	every { backdropImageTags } returns backdropTags
	every { parentBackdropItemId } returns parentBackdropId
	every { parentBackdropImageTags } returns parentBackdropTags
	every { imageBlurHashes } returns null
}

class HomeFeaturedRepositoryTests : FunSpec({
	test("selectFeaturedItems keeps candidates that have a backdrop and were never started") {
		val kept = item()

		selectFeaturedItems(candidates = listOf(kept), resumeItems = emptyList(), limit = 8) shouldBe listOf(kept)
	}

	test("selectFeaturedItems drops candidates without any backdrop") {
		val withBackdrop = item()
		val withoutBackdrop = item(backdropTags = null)
		val withEmptyBackdrops = item(backdropTags = emptyList())

		val result = selectFeaturedItems(
			candidates = listOf(withoutBackdrop, withBackdrop, withEmptyBackdrops),
			resumeItems = emptyList(),
			limit = 8,
		)

		result shouldBe listOf(withBackdrop)
	}

	test("selectFeaturedItems accepts a candidate whose backdrop comes from its parent") {
		val parentId = UUID.randomUUID()
		val inherited = item(backdropTags = null, parentBackdropId = parentId, parentBackdropTags = listOf("parent-tag"))

		selectFeaturedItems(candidates = listOf(inherited), resumeItems = emptyList(), limit = 8) shouldBe listOf(inherited)
	}

	test("selectFeaturedItems excludes a movie the user is part-way through") {
		val inProgressId = UUID.randomUUID()
		val inProgress = item(id = inProgressId)
		val untouched = item()

		val result = selectFeaturedItems(
			candidates = listOf(inProgress, untouched),
			resumeItems = listOf(item(id = inProgressId)),
			limit = 8,
		)

		result shouldBe listOf(untouched)
	}

	test("selectFeaturedItems excludes a series when the resume list holds one of its episodes") {
		val seriesId = UUID.randomUUID()
		val series = item(id = seriesId)
		val untouched = item()
		// getResumeItems returns the episode, not the series - the series id only appears via seriesId.
		val inProgressEpisode = item(id = UUID.randomUUID(), seriesId = seriesId)

		val result = selectFeaturedItems(
			candidates = listOf(series, untouched),
			resumeItems = listOf(inProgressEpisode),
			limit = 8,
		)

		result shouldBe listOf(untouched)
	}

	test("selectFeaturedItems applies the limit after filtering, not before") {
		val withoutBackdrop = item(backdropTags = null)
		val first = item()
		val second = item()

		val result = selectFeaturedItems(
			candidates = listOf(withoutBackdrop, first, second),
			resumeItems = emptyList(),
			limit = 2,
		)

		result shouldBe listOf(first, second)
	}

	test("selectFeaturedItems returns nothing when every candidate is filtered out") {
		val result = selectFeaturedItems(
			candidates = listOf(item(backdropTags = null)),
			resumeItems = emptyList(),
			limit = 8,
		)

		result shouldBe emptyList()
	}
})
