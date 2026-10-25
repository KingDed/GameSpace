package com.example.gamespace

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Turns gamepad shoulder keys (L1/R1/L2/R2) and, optionally, the volume keys into screen taps
 * at the L and R marker positions. Needs to be switched on in Settings > Accessibility.
 */
class TriggerAccessibilityService : AccessibilityService() {

    private var prefs: Prefs? = null
    private val last = longArrayOf(0L, 0L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val p = prefs ?: return false
        if (!p.bool(LiveIds.TRIGGERS)) return false

        var side = -1
        var second = false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> side = 0
            KeyEvent.KEYCODE_BUTTON_R1 -> side = 1
            KeyEvent.KEYCODE_BUTTON_L2 -> { side = 0; second = true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { side = 1; second = true }
            KeyEvent.KEYCODE_VOLUME_UP -> if (p.bool(LiveIds.VOL)) side = 0
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (p.bool(LiveIds.VOL)) side = 1
        }
        if (side < 0) return false

        val mode: Int = if (second) {
            val idx = p.int(if (side == 0) LiveIds.L2_ACT else LiveIds.R2_ACT, 0)
            if (idx == 0) return false
            idx - 1
        } else {
            p.int(if (side == 0) LiveIds.L_ACT else LiveIds.R_ACT, 0)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (p.bool(LiveIds.DEBOUNCE) && now - last[side] < 120) return true
            last[side] = now
            val target = if (p.bool(LiveIds.SWAP)) 1 - side else side
            val (x, y) = LiveIds.pos(this, p, target == 1)
            val half = 28 * resources.displayMetrics.density
            if (p.bool(LiveIds.HAPTICS)) buzz()
            tap(x + half, y + half, mode)
        }
        return true
    }

    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    private fun stroke(x: Float, y: Float, start: Long, dur: Long): GestureDescription.StrokeDescription {
        val path = Path()
        path.moveTo(x, y)
        return GestureDescription.StrokeDescription(path, start, dur)
    }

    /** mode: 0 single tap, 1 double tap, 2 long press, 3 triple tap */
    private fun tap(x: Float, y: Float, mode: Int) {
        val b = GestureDescription.Builder()
        when (mode) {
            0 -> b.addStroke(stroke(x, y, 0, 40))
            1 -> {
                b.addStroke(stroke(x, y, 0, 40))
                b.addStroke(stroke(x, y, 110, 40))
            }
            2 -> b.addStroke(stroke(x, y, 0, 650))
            else -> {
                b.addStroke(stroke(x, y, 0, 40))
                b.addStroke(stroke(x, y, 110, 40))
                b.addStroke(stroke(x, y, 220, 40))
            }
        }
        dispatchGesture(b.build(), null, null)
    }
}
