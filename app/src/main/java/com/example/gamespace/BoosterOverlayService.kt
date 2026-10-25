package com.example.gamespace

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContextWrapper
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import com.example.gamespace.databinding.OverlayHandleBinding
import com.example.gamespace.databinding.OverlayPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class BoosterOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gamespace.STOP"
        const val ACTION_LAUNCH = "com.example.gamespace.LAUNCH"
        private const val CHANNEL_ID = "game_space_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var wm: WindowManager
    private lateinit var activityManager: ActivityManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var themedContext: Context

    private var handleBinding: OverlayHandleBinding? = null
    private var panelBinding: OverlayPanelBinding? = null
    private lateinit var handleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var handleAttached = false
    private var panelAttached = false
    private var overlayCreated = false

    private var keepScreenOn = false
    private var searchFocus = false
    private lateinit var prefs: Prefs
    private var live: LiveEngine? = null
    private var featuresView: android.view.View? = null
    private var maxRefresh = false
    private var previousFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statsJob: Job? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        themedContext = ContextThemeWrapper(this, R.style.Theme_GameSpace)
        createChannel()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!overlayCreated) {
            createOverlay()
            startStatsLoop()
        }
        if (intent?.action == ACTION_LAUNCH) runLaunchProfile()
        return START_STICKY
    }

    // ---------- Notification ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Game Space", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Keeps the Game Space panel running" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, BoosterOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Game Space is active")
            .setContentText("Tap the edge handle over your game to open the panel")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    // ---------- Overlay creation ----------

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (keepScreenOn) f = f or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (searchFocus) f = f and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        return f
    }

    private fun createOverlay() {
        val inflater = LayoutInflater.from(themedContext)
        handleBinding = OverlayHandleBinding.inflate(inflater)
        panelBinding = OverlayPanelBinding.inflate(inflater)

        handleParams = WindowManager.LayoutParams(
            dp(18), dp(76),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = wm.currentWindowMetrics.bounds.height() / 3
        }

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        sizePanelForScreen()

        setupHandle()
        setupPanel()

        val maxHz = getMaxRefreshRate()
        panelBinding?.txtRefreshTitle?.text =
            String.format(Locale.US, "Max Refresh Rate (%.0fHz)", maxHz)

        showHandle()
        live = LiveEngine(this, wm, prefs, scope)
        live?.sync()
        overlayCreated = true
    }

    private fun sizePanelForScreen() {
        val bounds = wm.currentWindowMetrics.bounds
        val landscape = bounds.width() > bounds.height()
        panelParams.width = min((bounds.width() * 0.92f).toInt(), dp(420))
        panelParams.height = if (landscape) (bounds.height() * 0.9f).toInt()
        else (bounds.height() * 0.82f).toInt()
    }

    private fun clampY(y: Int): Int {
        val maxY = wm.currentWindowMetrics.bounds.height() - dp(76)
        return y.coerceIn(0, maxOf(0, maxY))
    }

    // ---------- State A / State B ----------

    private fun showHandle() {
        val v = handleBinding?.root ?: return
        if (handleAttached) return
        try {
            wm.addView(v, handleParams)
            handleAttached = true
        } catch (e: Exception) {
            handleAttached = false
        }
    }

    private fun hideHandle() {
        val v = handleBinding?.root ?: return
        if (!handleAttached) return
        try {
            wm.removeViewImmediate(v)
        } catch (e: Exception) {
        }
        handleAttached = false
    }

    private fun expand() {
        hideHandle()
        val v = panelBinding?.root ?: return
        if (panelAttached) return
        sizePanelForScreen()
        try {
            wm.addView(v, panelParams)
            panelAttached = true
        } catch (e: Exception) {
            panelAttached = false
            showHandle()
        }
    }

    private fun collapse() {
        if (searchFocus) { searchFocus = false; panelParams.flags = baseFlags() }
        val v = panelBinding?.root
        if (v != null && panelAttached) {
            try {
                wm.removeViewImmediate(v)
            } catch (e: Exception) {
            }
            panelAttached = false
        }
        showHandle()
    }

    // ---------- Handle (drag + click) ----------

    private fun setupHandle() {
        val root = handleBinding?.root ?: return
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0
        var touchStartY = 0f
        var moved = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = handleParams.y
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dy) > slop) moved = true
                    if (moved) {
                        handleParams.y = clampY(startY + dy)
                        if (handleAttached) {
                            try {
                                wm.updateViewLayout(root, handleParams)
                            } catch (e: Exception) {
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) expand()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Panel ----------

    private fun setupPanel() {
        val b = panelBinding ?: return

        b.panelRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                collapse()
                true
            } else false
        }
        b.btnClose.setOnClickListener { collapse() }
        b.tabHome.setOnClickListener { showTab(false) }
        b.tabFeatures.setOnClickListener { showTab(true) }
        b.btnBoost.setOnClickListener { runBoost() }

        b.switchDnd.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            setFilter(checked, NotificationManager.INTERRUPTION_FILTER_NONE, b.switchDnd)
        }
        b.switchBlockNotif.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            setFilter(checked, NotificationManager.INTERRUPTION_FILTER_PRIORITY, b.switchBlockNotif)
        }
        b.switchKeepOn.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            keepScreenOn = checked
            applyWindowFlagsAndRate()
        }
        b.switchRefresh.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            maxRefresh = checked
            applyWindowFlagsAndRate()
        }
    }

    private fun showTab(features: Boolean) {
        val b = panelBinding ?: return
        if (features && featuresView == null) {
            featuresView = FeaturesTab(
                themedContext, prefs,
                { enableSearchFocus() },
                { id -> if (LiveIds.ALL.contains(id)) live?.sync() }
            ).build()
            b.tabHost.addView(featuresView, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        b.homeScroll.visibility = if (features) View.GONE else View.VISIBLE
        b.tabHost.visibility = if (features) View.VISIBLE else View.GONE
        b.tabHome.setTextColor(if (features) 0xFFB0B0B8.toInt() else 0xFFE01E37.toInt())
        b.tabFeatures.setTextColor(if (features) 0xFFE01E37.toInt() else 0xFFB0B0B8.toInt())
    }

    private fun enableSearchFocus() {
        if (searchFocus) return
        searchFocus = true
        panelParams.flags = baseFlags()
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        try { if (panelAttached) wm.updateViewLayout(panelBinding!!.root, panelParams) } catch (e: Exception) {}
    }

    private fun applyWindowFlagsAndRate() {
        val rate = if (maxRefresh) getMaxRefreshRate() else 0f
        for (p in listOf(handleParams, panelParams)) {
            p.flags = baseFlags()
            p.preferredRefreshRate = rate
        }
        try {
            if (handleAttached) wm.updateViewLayout(handleBinding!!.root, handleParams)
            if (panelAttached) wm.updateViewLayout(panelBinding!!.root, panelParams)
        } catch (e: Exception) {
        }
    }

    private fun getMaxRefreshRate(): Float {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 60f
        return display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
    }

    private fun setFilter(enable: Boolean, filter: Int, sw: android.widget.CompoundButton) {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            sw.isChecked = false
            Toast.makeText(this, "Allow Do Not Disturb access, then try again", Toast.LENGTH_LONG).show()
            collapse()
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        try {
            if (enable) {
                val other = if (sw === panelBinding?.switchDnd) panelBinding?.switchBlockNotif else panelBinding?.switchDnd
                if (other?.isChecked == true) other.isChecked = false else previousFilter = notificationManager.currentInterruptionFilter
                notificationManager.setInterruptionFilter(filter)
            } else {
                notificationManager.setInterruptionFilter(previousFilter)
            }
        } catch (e: SecurityException) {
            sw.isChecked = false
        }
    }

    // ---------- Game launch profile ----------

    private fun runLaunchProfile() {
        scope.launch {
            if (prefs.bool(LiveIds.RAM)) withContext(Dispatchers.IO) { killBackgroundApps() }
            live?.applyLaunchSettings()
        }
    }

    // ---------- Boost ----------

    private fun runBoost() {
        val b = panelBinding ?: return
        b.btnBoost.isEnabled = false
        b.btnBoost.text = "BOOSTING..."
        scope.launch {
            val before = readAvailMb()
            withContext(Dispatchers.IO) { killBackgroundApps() }
            delay(800)
            val freed = (readAvailMb() - before).coerceAtLeast(0)
            b.btnBoost.text = "FREED $freed MB"
            updateRam()
            delay(1800)
            b.btnBoost.text = "BOOST NOW"
            b.btnBoost.isEnabled = true
        }
    }

    private fun killBackgroundApps() {
        val common = listOf(
            "com.facebook.katana", "com.facebook.orca", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.snapchat.android", "com.twitter.android",
            "com.whatsapp", "com.google.android.youtube", "com.netflix.mediaclient",
            "com.spotify.music", "com.android.chrome"
        )
        val targets = HashSet<String>(common)
        try {
            packageManager.getInstalledApplications(0).forEach { info ->
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!isSystem) targets.add(info.packageName)
            }
        } catch (e: Exception) {
        }
        targets.remove(packageName)
        for (pkg in targets) {
            try {
                activityManager.killBackgroundProcesses(pkg)
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }
        }
    }

    // ---------- Stats loop ----------

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                val ghz = withContext(Dispatchers.IO) { readCpuGhz() }
                panelBinding?.txtCpu?.text =
                    if (ghz != null) String.format(Locale.US, "%.2f", ghz) else "--"
                updateRam()
                updateBattery()
                delay(2000)
            }
        }
    }

    private fun readCpuGhz(): Float? {
        var maxKhz = -1L
        val cores = Runtime.getRuntime().availableProcessors()
        for (i in 0 until cores) {
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

    private fun readAvailMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun updateRam() {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val b = panelBinding ?: return
        val freeGb = info.availMem / 1024f / 1024f / 1024f
        b.txtRam.text = String.format(Locale.US, "%.1f", freeGb)
        val usedPct = if (info.totalMem > 0)
            (100 - (info.availMem * 100 / info.totalMem)).toInt() else 0
        b.barRam.progress = usedPct
    }

    private fun updateBattery() {
        val b = panelBinding ?: return
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        b.txtBattery.text = String.format(Locale.US, "Battery %d%%  ·  %.1f°C", pct, temp)
    }

    // ---------- Rotation ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!overlayCreated) return
        try {
            handleParams.y = clampY(handleParams.y)
            sizePanelForScreen()
            if (handleAttached) wm.updateViewLayout(handleBinding!!.root, handleParams)
            if (panelAttached) wm.updateViewLayout(panelBinding!!.root, panelParams)
        } catch (e: Exception) {
        }
    }

    // ---------- Cleanup ----------

    override fun onDestroy() {
        statsJob?.cancel()
        live?.shutdown()
        scope.cancel()
        hideHandle()
        val v = panelBinding?.root
        if (v != null && panelAttached) {
            try {
                wm.removeViewImmediate(v)
            } catch (e: Exception) {
            }
            panelAttached = false
        }
        try {
            if (notificationManager.isNotificationPolicyAccessGranted &&
                (panelBinding?.switchDnd?.isChecked == true || panelBinding?.switchBlockNotif?.isChecked == true)
            ) notificationManager.setInterruptionFilter(previousFilter)
        } catch (e: Exception) {
        }
        handleBinding = null
        panelBinding = null
        super.onDestroy()
    }
}
