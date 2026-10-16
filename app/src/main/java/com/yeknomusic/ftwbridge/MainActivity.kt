package com.yeknomusic.ftwbridge

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.feature.HealthConnectFeatures
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One screen: connect to Health Connect (Samsung Health), then sync.
 * The phone app opens this with ftwbridge://sync?u=<db url>&c=<pair code>; after a successful
 * sync from that link it closes itself so you land back in the phone app.
 */
class MainActivity : ComponentActivity() {

    private val gold = Color.parseColor("#F0B429")
    private lateinit var status: TextView
    private lateinit var allowBtn: Button
    private lateinit var syncBtn: Button
    private var returnAfterSync = false
    private var busy = false

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        lifecycleScope.launch { if (refresh()) sync() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleLink(intent)
        lifecycleScope.launch { if (refresh() && returnAfterSync) sync() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
        lifecycleScope.launch { if (refresh() && returnAfterSync) sync() }
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "ftwbridge") return
        val url = data.getQueryParameter("u")
        val code = data.getQueryParameter("c")
        if (url != null && code != null && url.startsWith("https://") && code.length >= 24) {
            Bridge.saveConfig(this, url, code)
        }
        returnAfterSync = data.host == "sync"
    }

    /** Updates the screen. Returns true when everything needed for a sync is in place. */
    private suspend fun refresh(): Boolean {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                show("Health Connect isn't available on this phone.", allow = false, sync = false)
                return false
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                show("Health Connect needs an update. Tap below to update it, then come back.", allow = true, sync = false)
                allowBtn.text = "Update Health Connect"
                allowBtn.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
                }
                return false
            }
        }
        if (Bridge.config(this) == null) {
            show("Not connected yet.\n\nOpen your Muscle Growth Tracker app → Watch card → \"Connect Samsung Health\". " +
                 "That link sets this app up automatically.", allow = false, sync = false)
            return false
        }
        val hc = HealthConnectClient.getOrCreate(this)
        val granted = hc.permissionController.getGrantedPermissions()
        if (!granted.containsAll(Bridge.PERMISSIONS)) {
            show("Allow access to Health Connect so your workouts, weight and steps can move between " +
                 "FitTracker and Samsung Health.", allow = true, sync = false)
            allowBtn.text = "Allow access"
            allowBtn.setOnClickListener { permissionLauncher.launch(requestedPermissions(hc)) }
            return false
        }
        Bridge.schedule(this)
        val bg = if (Bridge.BACKGROUND_READ in granted) "Syncs every hour, even when closed."
                 else "Syncs every hour. Steps & weight from Samsung Health update when this app or the sync link is opened."
        val last = Bridge.prefs(this).getString("last", null)
        show("Connected to Samsung Health.\n$bg" + (last?.let { "\n\nLast sync:\n$it" } ?: ""), allow = false, sync = true)
        return true
    }

    private fun requestedPermissions(hc: HealthConnectClient): Set<String> {
        val bgAvailable = try {
            hc.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Throwable) { false }
        return if (bgAvailable) Bridge.PERMISSIONS + Bridge.BACKGROUND_READ else Bridge.PERMISSIONS
    }

    private suspend fun sync() {
        if (busy) return
        val cfg = Bridge.config(this) ?: return
        busy = true
        syncBtn.isEnabled = false
        status.text = "Syncing with Samsung Health…"
        val summary = try {
            SyncEngine(this, cfg.first, cfg.second).run(foreground = true)
        } catch (e: Exception) {
            SyncSummary(0, 0, 0, 0, listOf(e.message ?: "Sync failed"))
        }
        val stamp = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        Bridge.prefs(this).edit().putString("last", "$stamp · " + summary.text()).apply()
        busy = false
        refresh()
        if (returnAfterSync && summary.errors.isEmpty()) {
            Toast.makeText(this, summary.text().lineSequence().first(), Toast.LENGTH_SHORT).show()
            returnAfterSync = false
            delay(700)
            finish()            // back to the phone app
        }
    }

    /* ---------------- plain views, dark & gold like the other FitTracker apps ---------------- */

    private fun show(text: String, allow: Boolean, sync: Boolean) {
        status.text = text
        allowBtn.visibility = if (allow) View.VISIBLE else View.GONE
        syncBtn.visibility = if (sync) View.VISIBLE else View.GONE
        syncBtn.isEnabled = !busy
    }

    private fun buildUi() {
        window.statusBarColor = Color.parseColor("#0A0A0B")
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(40), px(24), px(24))
        }
        col.addView(TextView(this).apply {
            text = "FITTRACKER ⇄ SAMSUNG HEALTH"
            setTextColor(gold); textSize = 13f; letterSpacing = 0.12f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Health bridge"
            setTextColor(Color.WHITE); textSize = 32f; typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, px(4), 0, px(20))
        })
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#CFCFD4")); textSize = 16f; setLineSpacing(0f, 1.25f)
            background = GradientDrawable().apply { setColor(Color.parseColor("#16161A")); cornerRadius = px(16).toFloat() }
            setPadding(px(18), px(16), px(18), px(16))
        }
        col.addView(status)

        fun button(label: String, primary: Boolean) = Button(this).apply {
            text = label; isAllCaps = true; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.parseColor("#111111") else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (primary) gold else Color.parseColor("#222227")); cornerRadius = px(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(54)).apply { topMargin = px(14) }
        }
        allowBtn = button("Allow access", true)
        syncBtn = button("Sync now", true).apply { setOnClickListener { lifecycleScope.launch { sync() } } }
        col.addView(allowBtn)
        col.addView(syncBtn)

        col.addView(TextView(this).apply {
            text = "What moves where:\n" +
                "• Workouts from your watch or phone → Samsung Health (strength training, with your sets in the notes)\n" +
                "• Weight: both ways\n" +
                "• Steps & heart rate from Samsung Health → your phone app\n\n" +
                "Samsung Health must be sharing with Health Connect: Samsung Health → Settings → Health Connect."
            setTextColor(Color.parseColor("#77777F")); textSize = 13f; setLineSpacing(0f, 1.3f)
            setPadding(px(2), px(24), px(2), 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            isFillViewport = true
            addView(col)
        })
        col.gravity = Gravity.TOP
    }
}
