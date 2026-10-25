package com.example.gamespace

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Feature ids that really do something (everything else is saved to the profile only). */
object LiveIds {
    const val CROSSHAIR = "live_overlays_crosshair"
    const val CROSS_SIZE = "live_overlays_crosshair_size"
    const val CROSS_COLOR = "live_overlays_crosshair_color"
    const val GRID = "live_overlays_auxiliary_line"
    const val COUNTER = "live_overlays_counter"
    const val STOPWATCH = "live_overlays_stopwatch"
    const val DATA = "live_overlays_data_panel"
    const val CLOCK = "live_overlays_clock"
    const val TRIGGERS = "live_overlays_shoulder_triggers"
    const val EDIT = "live_overlays_edit_trigger_markers"
    const val FPS = "performance_fps_counter_overlay"
    const val PING = "network_ping_overlay"
    const val TINT = "display_night_tint"
    const val DIM = "display_screen_dim_overlay"
    const val TEMP = "battery_thermal_temp_alert"
    const val DND = "interface_do_not_disturb"
    const val MUTE = "audio_mute_notifications"
    const val RAM = "performance_ram_clean_on_launch"
    const val HAPTICS = "touch_triggers_trigger_haptics"
    const val SWAP = "touch_triggers_trigger_swap_l_r"
    const val DEBOUNCE = "touch_triggers_trigger_debounce"
    const val VOL = "touch_triggers_volume_keys_as_triggers"
    const val L_ACT = "touch_triggers_trigger_l_action"
    const val R_ACT = "touch_triggers_trigger_r_action"
    const val L2_ACT = "touch_triggers_trigger_l2_action"
    const val R2_ACT = "touch_triggers_trigger_r2_action"

    val ALL = setOf(
        CROSSHAIR, CROSS_SIZE, CROSS_COLOR, GRID, COUNTER, STOPWATCH, DATA, CLOCK, TRIGGERS, EDIT,
        FPS, PING, TINT, DIM, TEMP, DND, MUTE, RAM, HAPTICS, SWAP, DEBOUNCE, VOL,
        L_ACT, R_ACT, L2_ACT, R2_ACT
    )

    /** Saved top-left position of the L or R marker (screen pixels). */
    fun pos(ctx: Context, prefs: Prefs, right: Boolean): Pair<Int, Int> {
        val d = ctx.resources.displayMetrics.density
        val b = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        val kx = if (right) "trg_rx" else "trg_lx"
        val ky = if (right) "trg_ry" else "trg_ly"
        val defX = if (right) b.width() - (96 * d).toInt() else (40 * d).toInt()
        val x = prefs.int(kx, -1).let { if (it < 0) defX else it }
        val y = prefs.int(ky, -1).let { if (it < 0) b.height() / 2 else it }
        return Pair(x, y)
    }
}

private class CrosshairView(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        p.strokeWidth = 2f * d
        p.color = color
        val cx = width / 2f
        val cy = height / 2f
        val gap = width * 0.1f
        canvas.drawLine(0f, cy, cx - gap, cy, p)
        canvas.drawLine(cx + gap, cy, width.toFloat(), cy, p)
        canvas.drawLine(cx, 0f, cx, cy - gap, p)
        canvas.drawLine(cx, cy + gap, cx, height.toFloat(), p)
        canvas.drawCircle(cx, cy, 1.5f * d, p)
    }
}

private class GridView(ctx: Context) : View(ctx) {
    private val p = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, p)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, p)
        }
        canvas.drawLine(w / 2f, 0f, w / 2f, h, p)
        canvas.drawLine(0f, h / 2f, w, h / 2f, p)
    }
}

private class FilterView(ctx: Context, private val tint: Boolean, private val dim: Int) : View(ctx) {
    override fun onDraw(canvas: Canvas) {
        if (tint) canvas.drawColor(Color.argb(60, 255, 140, 0))
        if (dim > 0) canvas.drawColor(Color.argb(dim, 0, 0, 0))
    }
}

class LiveEngine(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val scope: CoroutineScope
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val views = HashMap<String, View>()
    private var hudView: TextView? = null
    private var loop: Job? = null
    private var pingMs = -1
    private var frames = 0
    private var fpsRunning = false
    private var swOn = false
    private var swStart = 0L
    private var count = 0
    private var lastAlert = 0L
    private var savedFilter = -1
    private var savedNotifVol = -1

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            if (fpsRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun lp(w: Int, h: Int, touch: Boolean, grav: Int): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        f = if (touch) f or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        else f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        ).apply { gravity = grav }
    }

    private fun show(key: String, v: View, p: WindowManager.LayoutParams) {
        hide(key)
        try {
            wm.addView(v, p)
            views[key] = v
        } catch (e: Exception) {
        }
    }

    private fun hide(key: String) {
        val v = views.remove(key) ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {
        }
    }

    /** Re-reads the saved profile and shows / hides every live overlay. */
    fun sync() {
        val b = prefs
        val full = WindowManager.LayoutParams.MATCH_PARENT
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        val topStart = Gravity.TOP or Gravity.START

        if (b.bool(LiveIds.CROSSHAIR)) {
            val size = dp(16 + b.int(LiveIds.CROSS_SIZE, 50) * 50 / 100)
            val color = when (b.int(LiveIds.CROSS_COLOR, 0)) {
                0 -> 0xFF00FF66.toInt()
                1 -> 0xFFFF3B52.toInt()
                2 -> Color.WHITE
                else -> 0xFF00E5FF.toInt()
            }
            show("cross", CrosshairView(ctx, color), lp(size * 2, size * 2, false, Gravity.CENTER))
        } else hide("cross")

        if (b.bool(LiveIds.GRID)) show("grid", GridView(ctx), lp(full, full, false, topStart))
        else hide("grid")

        val tint = b.bool(LiveIds.TINT)
        val dim = if (b.has(LiveIds.DIM)) b.int(LiveIds.DIM, 0) * 16 / 10 else 0
        if (tint || dim > 0) show("filter", FilterView(ctx, tint, dim), lp(full, full, false, topStart))
        else hide("filter")

        if (b.bool(LiveIds.COUNTER)) {
            if (views["counter"] == null) {
                count = 0
                val tv = TextView(ctx).apply {
                    text = "0"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xCCE01E37.toInt())
                    }
                }
                tv.setOnClickListener { count++; tv.text = count.toString() }
                tv.setOnLongClickListener { count = 0; tv.text = "0"; true }
                show("counter", tv, lp(dp(56), dp(56), true, Gravity.END or Gravity.BOTTOM).apply {
                    x = dp(12); y = dp(96)
                })
            }
        } else hide("counter")

        if (b.bool(LiveIds.EDIT)) {
            if (views["mL"] == null) showMarker(false)
            if (views["mR"] == null) showMarker(true)
        } else {
            hide("mL"); hide("mR")
        }

        val hudNeeded = b.bool(LiveIds.CLOCK) || b.bool(LiveIds.STOPWATCH) || b.bool(LiveIds.DATA) ||
                b.bool(LiveIds.FPS) || b.bool(LiveIds.PING) || b.bool(LiveIds.TEMP)
        if (hudNeeded) {
            if (hudView == null) {
                val t = TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setBackgroundColor(0x88000000.toInt())
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }
                hudView = t
                show("hud", t, lp(wrap, wrap, false, topStart).apply { x = dp(8); y = dp(8) })
            }
            val sw = b.bool(LiveIds.STOPWATCH)
            if (sw && !swOn) swStart = SystemClock.elapsedRealtime()
            swOn = sw
            val wantFps = b.bool(LiveIds.FPS)
            if (wantFps && !fpsRunning) {
                fpsRunning = true
                frames = 0
                Choreographer.getInstance().postFrameCallback(frameCb)
            } else if (!wantFps) fpsRunning = false
            if (loop?.isActive != true) {
                loop = scope.launch {
                    while (isActive) {
                        tick()
                        delay(1000)
                    }
                }
            }
        } else {
            loop?.cancel(); loop = null
            hide("hud"); hudView = null
            fpsRunning = false; swOn = false
        }
    }

    private fun showMarker(right: Boolean) {
        val (px, py) = LiveIds.pos(ctx, prefs, right)
        val kx = if (right) "trg_rx" else "trg_lx"
        val ky = if (right) "trg_ry" else "trg_ly"
        val tv = TextView(ctx).apply {
            text = if (right) "R" else "L"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (right) 0xCC1E5BE0.toInt() else 0xCCE01E37.toInt())
            }
        }
        val p = lp(dp(56), dp(56), true, Gravity.TOP or Gravity.START).apply { x = px; y = py }
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt()
                    p.y = sy + (e.rawY - ty).toInt()
                    try {
                        wm.updateViewLayout(tv, p)
                    } catch (ex: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.setInt(kx, p.x.coerceAtLeast(0))
                    prefs.setInt(ky, p.y.coerceAtLeast(0))
                    true
                }
                else -> false
            }
        }
        show(if (right) "mR" else "mL", tv, p)
    }

    private suspend fun tick() {
        val b = prefs
        val sb = StringBuilder()
        if (b.bool(LiveIds.CLOCK)) sb.append(SimpleDateFormat("HH:mm", Locale.US).format(Date())).append("\n")
        if (swOn) {
            val s = (SystemClock.elapsedRealtime() - swStart) / 1000
            sb.append(String.format(Locale.US, "%02d:%02d", s / 60, s % 60)).append("\n")
        }
        if (b.bool(LiveIds.FPS)) sb.append("Display ").append(frames).append(" FPS\n")
        frames = 0

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100

        if (b.bool(LiveIds.DATA)) {
            val ghz = withContext(Dispatchers.IO) { cpuGhz() }
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append(String.format(Locale.US, "CPU %s GHz\n", if (ghz != null) String.format(Locale.US, "%.2f", ghz) else "--"))
            sb.append(String.format(Locale.US, "RAM %.1f GB free\n", mi.availMem / 1073741824f))
            sb.append(String.format(Locale.US, "Bat %d%%  %.1f°C\n", if (scale > 0 && level >= 0) level * 100 / scale else 0, temp))
        }
        if (b.bool(LiveIds.PING)) {
            scope.launch(Dispatchers.IO) { pingMs = measurePing() }
            sb.append("Ping ").append(if (pingMs >= 0) "$pingMs ms" else "--").append("\n")
        }
        if (b.bool(LiveIds.TEMP) && temp >= 43f) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlert > 60000) {
                lastAlert = now
                Toast.makeText(ctx, String.format(Locale.US, "Phone is hot: %.1f°C", temp), Toast.LENGTH_SHORT).show()
            }
        }
        val text = sb.toString().trim()
        hudView?.let {
            it.text = text
            it.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun measurePing(): Int {
        return try {
            Socket().use { s ->
                val t = SystemClock.elapsedRealtime()
                s.connect(InetSocketAddress("1.1.1.1", 53), 1000)
                (SystemClock.elapsedRealtime() - t).toInt()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun cpuGhz(): Float? {
        var maxKhz = -1L
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (f.canRead()) {
                    val v = f.readText().trim().toLongOrNull()
                    if (v != null && v > maxKhz) maxKhz = v
                }
            } catch (e: Exception) {
            }
        }
        return if (maxKhz > 0) maxKhz / 1_000_000f else null
    }

    /** Called when a game is started from Game Space. */
    fun applyLaunchSettings() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (prefs.bool(LiveIds.DND)) {
            if (nm.isNotificationPolicyAccessGranted) {
                if (savedFilter < 0) savedFilter = nm.currentInterruptionFilter
                try {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } catch (e: SecurityException) {
                }
            } else {
                Toast.makeText(ctx, "Allow Do Not Disturb access for Game Space", Toast.LENGTH_LONG).show()
            }
        }
        if (prefs.bool(LiveIds.MUTE)) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                if (savedNotifVol < 0) savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            } catch (e: SecurityException) {
            }
        }
    }

    fun shutdown() {
        loop?.cancel(); loop = null
        fpsRunning = false
        for (k in views.keys.toList()) hide(k)
        hudView = null
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (savedFilter >= 0 && nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedFilter)
            if (savedNotifVol >= 0) {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0)
            }
        } catch (e: Exception) {
        }
    }
}
