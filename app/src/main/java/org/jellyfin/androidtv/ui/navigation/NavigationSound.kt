package org.jellyfin.androidtv.ui.navigation

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.getSystemService

/**
 * Plays the system D-pad navigation click for moves the platform doesn't click for by itself.
 *
 * Android only clicks when its own focus navigation moves focus, which happens only for key events
 * nothing in the app consumed. Compose, the Leanback interop and MainActivity's sidebar handoffs all
 * consume the key and move focus themselves, silently - so without this, only some directions on
 * the home screen made a sound.
 *
 * MainActivity [arm]s this for every D-pad press and [disarm]s it again when the press goes
 * unconsumed, leaving those to the platform so nothing clicks twice. Anything that moves focus or
 * selection in response to a consumed press then calls [notifyMoved]. Several of those can fire for
 * a single press (e.g. a View-level focus change and the Compose button gaining focus), so the first
 * one disarms it. Focus moves that aren't caused by a D-pad press, like initial focus or the hero
 * banner's auto-rotation, find it disarmed and stay silent.
 */
class NavigationSound(context: Context) {
	private companion object {
		// Long enough to cover moves deferred by a few frames, like the hero reclaiming focus from
		// the rows through a recomposition, and short enough not to click for anything unrelated.
		const val ARMED_WINDOW_MS = 500L
	}

	private val audioManager = context.getSystemService<AudioManager>()
	private var armedEffect: Int? = null
	private var armedAt = 0L

	/** Arms for [keyCode]. Returns false when it isn't a D-pad direction. */
	fun arm(keyCode: Int): Boolean {
		armedEffect = when (keyCode) {
			KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
			KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
			else -> null
		}
		armedAt = SystemClock.uptimeMillis()
		return armedEffect != null
	}

	fun disarm() {
		armedEffect = null
	}

	fun notifyMoved() {
		val effect = armedEffect ?: return
		armedEffect = null
		if (SystemClock.uptimeMillis() - armedAt > ARMED_WINDOW_MS) return

		// Honors the system "sound effects" setting by itself, the same as View.playSoundEffect
		audioManager?.playSoundEffect(effect)
	}
}
