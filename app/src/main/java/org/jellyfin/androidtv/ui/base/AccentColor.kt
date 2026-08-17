package org.jellyfin.androidtv.ui.base

import androidx.compose.ui.graphics.Color

/**
 * The app's brand accent, used sparingly for focus/selection highlights on the pieces of UI that
 * are effectively always on screen - the sidebar's focused item, the home hero's focus outline -
 * so they read as the app's identity rather than a generic highlight. Deliberately not
 * [JellyfinTheme.colorScheme]'s neutral grey `buttonFocused`, which stays the default everywhere
 * else. The value is the same red Netflix uses for the equivalent purpose.
 */
val AccentColor = Color(0xFFE50914)
