package com.example.gamespace

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gamespace.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) openNotificationSettings()
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadGames()
        binding.btnOverlayPerm.setOnClickListener { openOverlaySettings() }
        binding.btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnNotifPerm.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnExport.setOnClickListener {
            val f = exportScript()
            binding.txtHelperOut.text =
                if (f != null) "Exported to:\n${shellPath(f)}" else "Could not export start.sh"
        }
        binding.btnCopyCmd.setOnClickListener {
            val f = exportScript()
            if (f == null) {
                binding.txtHelperOut.text = "Could not export start.sh"
            } else {
                val cmd = "adb shell sh ${shellPath(f)}"
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb command", cmd))
                binding.txtHelperOut.text = "Copied:\n$cmd"
            }
        }
        binding.btnRootStart.setOnClickListener { runHelper("start") }
        binding.btnRootStatus.setOnClickListener { runHelper("status") }
        binding.btnRootStop.setOnClickListener { runHelper("stop") }
        binding.btnStart.setOnClickListener {
            if (startPanel()) Toast.makeText(this, "Open a game and use the edge handle", Toast.LENGTH_LONG).show()
        }
        binding.btnStop.setOnClickListener {
            startService(
                Intent(this, BoosterOverlayService::class.java)
                    .setAction(BoosterOverlayService.ACTION_STOP)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    private fun hasNotif() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        binding.txtOverlayStatus.text =
            if (hasOverlay()) "Overlay permission: granted" else "Overlay permission: missing"
        binding.txtNotifStatus.text =
            if (hasNotif()) "Notification permission: granted" else "Notification permission: missing"
        binding.btnOverlayPerm.isEnabled = !hasOverlay()
        binding.btnNotifPerm.isEnabled = !hasNotif()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun startPanel(launching: Boolean = false): Boolean {
        if (!hasOverlay()) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return false
        }
        if (!hasNotif()) {
            Toast.makeText(this, "Grant notification permission first", Toast.LENGTH_SHORT).show()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        val svc = Intent(this, BoosterOverlayService::class.java)
        if (launching) svc.action = BoosterOverlayService.ACTION_LAUNCH
        ContextCompat.startForegroundService(this, svc)
        return true
    }

    private fun loadGames() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val games = packageManager.queryIntentActivities(query, 0)
            .filter {
                it.activityInfo.packageName != packageName &&
                    it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
            }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        binding.gameList.removeAllViews()
        binding.txtNoGames.visibility = if (games.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val d = resources.displayMetrics.density
        for (g in games) {
            val pkg = g.activityInfo.packageName
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                layoutParams = LinearLayout.LayoutParams((84 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (8 * d).toInt() }
                setBackgroundResource(R.drawable.bg_card)
            }
            tile.addView(ImageView(this).apply {
                setImageDrawable(g.loadIcon(packageManager))
                layoutParams = LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt())
            })
            tile.addView(TextView(this).apply {
                text = g.loadLabel(packageManager)
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
            tile.setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null && startPanel(true)) startActivity(launch)
            }
            binding.gameList.addView(tile)
        }
    }

    // ---------- start19 v2 helper ----------

    private fun scriptFile() = File(getExternalFilesDir(null), "start.sh")

    private fun shellPath(f: File) = f.absolutePath.replace("/storage/emulated/0", "/sdcard")

    private fun exportScript(): File? {
        return try {
            val f = scriptFile()
            f.parentFile?.mkdirs()
            assets.open("start.sh").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            f
        } catch (e: Exception) {
            null
        }
    }

    private fun runHelper(command: String) {
        val f = exportScript()
        if (f == null) {
            binding.txtHelperOut.text = "Could not export start.sh"
            return
        }
        binding.txtHelperOut.text = "Running: start.sh $command (root)..."
        Thread {
            val out = try {
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "sh '${f.absolutePath}' $command")
                )
                val text = p.inputStream.bufferedReader().readText() +
                        p.errorStream.bufferedReader().readText()
                p.waitFor()
                text
            } catch (e: Exception) {
                "Root is not available on this phone (${e.message}).\n" +
                        "Use \"Copy adb command\" and run it from a PC instead."
            }
            runOnUiThread {
                binding.txtHelperOut.text = out.ifBlank { "(no output)" }
            }
        }.start()
    }
}
