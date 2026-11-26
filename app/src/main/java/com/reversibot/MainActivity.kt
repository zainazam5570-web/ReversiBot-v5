package com.reversibot

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: 3 permissions + Start. Baaqi sab kaam floating panel se hota hai. */
class MainActivity : Activity() {

    private val REQ_CAPTURE = 100
    private val REQ_NOTIF = 101

    private lateinit var tvOverlay: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvRunning: TextView
    private lateinit var btnStart: Button

    private val BG = 0xFF0B1220.toInt()
    private val CARD = 0xFF131C2E.toInt()
    private val STROKE = 0xFF22304A.toInt()
    private val TXT = 0xFFE8EEF6.toInt()
    private val MUTED = 0xFF93A4BA.toInt()
    private val ACCENT = 0xFF3B82F6.toInt()
    private val GREEN = 0xFF22C55E.toInt()
    private val RED = 0xFFEF4444.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, color: Int = TXT, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(2))
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun card(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(CARD); cornerRadius = dp(18).toFloat(); setStroke(dp(1), STROKE)
            }
            views.forEach { addView(it) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

    private fun button(s: String, color: Int = ACCENT, onClick: () -> Unit) =
        Button(this).apply {
            text = s; setAllCaps(false); setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = RippleDrawable(
                ColorStateList.valueOf(0x44FFFFFF),
                GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }, null
            )
            stateListAnimator = null
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(8) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
        }
        col.addView(text("♟ Reversi Bot", 28f, Color.WHITE, bold = true))
        col.addView(text("Screen dekh kar khud khelta hai. Pehle ye 3 steps poore karein:", 14f, MUTED))

        tvOverlay = text("", 14f, MUTED, bold = true)
        col.addView(card(
            text("1) Display over other apps", 16f, TXT, bold = true),
            tvOverlay,
            button("Overlay permission kholein") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        ))

        tvAccess = text("", 14f, MUTED, bold = true)
        col.addView(card(
            text("2) Accessibility service (sirf tap karne ke liye)", 16f, TXT, bold = true),
            tvAccess,
            button("Accessibility settings kholein") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Downloaded Apps / Installed apps mein 'Reversi Bot Tapper' ko ON karein", Toast.LENGTH_LONG).show()
            },
            text(
                "Agar ON nahi ho raha (grey): Settings > Apps > Reversi Bot > ⋮ (teen dots) > 'Allow restricted settings', phir dobara try karein.",
                12f, MUTED
            )
        ))

        tvRunning = text("", 14f, MUTED, bold = true)
        btnStart = button("▶ Bot shuru karein", GREEN) { startBot() }
        col.addView(card(
            text("3) Start", 16f, TXT, bold = true),
            btnStart,
            tvRunning,
            button("■ Bot band karein", RED) {
                startService(Intent(this, BotService::class.java).setAction(BotService.ACTION_STOP))
            }
        ))

        col.addView(card(
            text("Kaise chalayein", 16f, TXT, bold = true),
            text(
                "• Start dabayen, screen share par 'Entire screen / Poori screen' chunein.\n" +
                    "• Game kholein. Neeche chhota horizontal panel aayega (khiskane ke liye ⠿ pakdein).\n" +
                    "• Board khud pehchana jata hai (calibration ki zaroorat nahi). Agar na mile to 📐 Manual use karein.\n" +
                    "• Strength chunein, ▶ START dabayen. Bot khud apna color aur baari pehchanta hai.\n" +
                    "• Panel ko board ke upar na rakhein.",
                13f, MUTED
            )
        ))

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, BotAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val ac = accessibilityOn()
        tvOverlay.text = if (ov) "✅ ON" else "❌ OFF"
        tvOverlay.setTextColor(if (ov) GREEN else RED)
        tvAccess.text = if (ac) "✅ ON" else "❌ OFF"
        tvAccess.setTextColor(if (ac) GREEN else RED)
        tvRunning.text = if (BotService.running) "🟢 Bot chal raha hai" else "⚪ Bot band hai"
        tvRunning.setTextColor(if (BotService.running) GREEN else MUTED)
        val ready = ov && ac
        btnStart.alpha = if (ready) 1f else 0.5f
    }

    private fun startBot() {
        refresh()
        if (!Settings.canDrawOverlays(this) || !accessibilityOn()) {
            Toast.makeText(this, "Pehle step 1 aur 2 poore karein", Toast.LENGTH_LONG).show()
            return
        }
        if (BotService.running) {
            Toast.makeText(this, "Bot pehle se chal raha hai", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, BotService::class.java)
                    .putExtra(BotService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(BotService.EXTRA_DATA, data)
                startForegroundService(i)
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture ki ijazat nahi mili", Toast.LENGTH_LONG).show()
            }
        }
    }
}
