package org.jellyfin.androidtv.ui.base.modifier

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * Scale this composable up slightly while it has D-pad focus, mirroring the zoom-on-focus effect
 * Leanback's [androidx.leanback.widget.FocusHighlightHelper] used to apply to grid/row items.
 */
@Composable
fun Modifier.focusScale(
	interactionSource: MutableInteractionSource,
	focusedScale: Float = 1.1f,
): Modifier {
	val focused by interactionSource.collectIsFocusedAsState()
	val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")

	return scale(scale)
}
