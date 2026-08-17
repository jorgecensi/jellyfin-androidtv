package org.jellyfin.androidtv.ui.home.hero

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SelectedDotSize = 10.dp
private val UnselectedDotSize = 7.dp

/**
 * Progress dots for the featured banner's carousel. Deliberately not focusable: the banner has no
 * manual paging (see [HomeHero]) so there is nothing here for the D-pad to land on, and an extra
 * focus stop between the action buttons and the rows below would only get in the user's way.
 */
@Composable
fun HomeHeroPageIndicator(
	count: Int,
	selectedIndex: Int,
	modifier: Modifier = Modifier,
) {
	if (count <= 1) return

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		repeat(count) { index ->
			val selected = index == selectedIndex
			val color by animateColorAsState(
				targetValue = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
				label = "heroDotColor",
			)
			val size by animateDpAsState(
				targetValue = if (selected) SelectedDotSize else UnselectedDotSize,
				label = "heroDotSize",
			)

			Box(
				modifier = Modifier
					.size(size)
					.clip(CircleShape)
					.background(color),
			)
		}
	}
}
