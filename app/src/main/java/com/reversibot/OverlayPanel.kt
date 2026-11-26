package com.reversibot

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.reversibot.engine.Level
import com.reversibot.vision.Reading

/**
 * Floating panel: horizontal, glass-style bar, 60% opacity.
 *
 *  ⠿ | [mini board] | [ ▶ START | Fast | Strong | Max | ▾ ]
 *                     [ status ...............  📐 👁 ✕ ]
 * Collapsed: ⠿ [ ▶ START | ▴ ] only.
 */
class OverlayPanel(
    private val ctx: Context,
    private val prefs: Prefs,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun onAutoChanged(on: Boolean)
        fun onLevelChanged(level: Level)
        fun onCalibrate()
        fun onClose()
    }

    private companion object {
        const val PANEL_ALPHA = 0.60f          // 60% opacity
        const val C_TEXT = 0xFFFFFFFF.toInt()
        const val C_MUTED = 0xFFC7D3E0.toInt()
        const val C_CHIP_OFF = 0x2EFFFFFF
        const val C_CHIP_ON = 0xFF3B82F6.toInt()
        const val C_GO = 0xFF16A34A.toInt()
        const val C_STOP = 0xFFDC2626.toInt()
        const val C_ICON = 0x26FFFFFF
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(ctx)
    private val status = TextView(ctx)
    private val mini = MiniBoardView(ctx)
    private lateinit var autoBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var foldBtn: TextView
    private lateinit var handle: TextView
    private lateinit var col: LinearLayout
    private lateinit var row2: LinearLayout
    private val levelBtns = LinkedHashMap<Level, TextView>()
    private var auto = false
    private var added = false
    private var collapsed = false
    private var lastText = ""

    private val lp = WindowManager.LayoutParams(
        panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = PANEL_ALPHA
    }

    private fun panelWidth(): Int {
        val w = ctx.resources.displayMetrics.widthPixels
        return minOf(w - dp(12), dp(380))
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    /** Rounded background with a light ripple on press. */
    private fun btnBg(color: Int, radiusDp: Int = 10) =
        RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), rounded(color, radiusDp), null)

    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C_TEXT)
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(4), 0, dp(4), 0)
            background = btnBg(color)
            setOnClickListener { onClick() }
        }

    private fun icon(text: String, color: Int = C_ICON, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(C_TEXT)
            textSize = 12.5f
            gravity = Gravity.CENTER
            background = btnBg(color, 8)
            setOnClickListener { onClick() }
        }

    private fun shortLabel(lv: Level) = when (lv) {
        Level.FAST -> "Fast"
        Level.VERY_STRONG -> "Strong"
        Level.MAX -> "Max"
    }

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(dp(2), dp(6), dp(8), dp(6))
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1E293B.toInt(), 0xFF0B1220.toInt())
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x55FFFFFF)
        }

        // drag handle (left strip)
        handle = TextView(ctx).apply {
            text = "⠿"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(0xFF8FA3BA.toInt())
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(22), dp(60)))

        // mini board
        root.addView(mini, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(8) })

        // right column: controls + status/icons
        col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // row 1: START + strength chips
        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        autoBtn = pill("▶ START", C_GO) { setAuto(!auto, notify = true) }
        row1.addView(autoBtn, LinearLayout.LayoutParams(dp(66), dp(32)))
        for (lv in Level.values()) {
            val b = pill(shortLabel(lv), C_CHIP_OFF) { selectLevel(lv, notify = true) }
            levelBtns[lv] = b
            row1.addView(b, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(4) })
        }
        foldBtn = icon("▾") {
            collapsed = !collapsed
            applyCollapsed()
        }
        row1.addView(foldBtn, LinearLayout.LayoutParams(dp(28), dp(32)).apply { leftMargin = dp(4) })
        col.addView(row1)
        selectLevel(prefs.level, notify = false)

        // row 2: status + icon buttons
        row2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status.apply {
            textSize = 10f
            setTextColor(C_MUTED)
            text = "Taiyar"
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row2.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })

        val sz = dp(26)
        fun addIcon(v: View) = row2.addView(v, LinearLayout.LayoutParams(sz, sz).apply { leftMargin = dp(3) })
        addIcon(icon("📐") { cb.onCalibrate() })
        viewBtn = icon("👁") {
            prefs.showMini = !prefs.showMini
            applyDetailsVisibility()
        }
        addIcon(viewBtn)
        addIcon(icon("✕", 0x55EF4444) { cb.onClose() })
        col.addView(row2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        attachDrag(handle)
        attachDrag(status)
        attachDrag(mini)
        applyCollapsed()
    }

    private fun applyDetailsVisibility() {
        mini.visibility = if (prefs.showMini && !collapsed) View.VISIBLE else View.GONE
        viewBtn.background = btnBg(if (prefs.showMini) 0x883B82F6.toInt() else C_ICON, 8)
    }

    /**
     * Collapsed: only START/STOP + the expand button remain (plus the small drag strip),
     * and the panel shrinks to just that. Expanded: full panel.
     */
    private fun applyCollapsed() {
        val full = !collapsed
        for (b in levelBtns.values) b.visibility = if (full) View.VISIBLE else View.GONE
        row2.visibility = if (full) View.VISIBLE else View.GONE
        foldBtn.text = if (collapsed) "▴" else "▾"
        applyDetailsVisibility()
        handle.layoutParams = LinearLayout.LayoutParams(
            if (collapsed) dp(16) else dp(22), if (collapsed) dp(34) else dp(60)
        )
        col.layoutParams = LinearLayout.LayoutParams(
            if (collapsed) LinearLayout.LayoutParams.WRAP_CONTENT else 0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            if (collapsed) 0f else 1f
        )
        lp.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else panelWidth()
        if (added) {
            wm.updateViewLayout(root, lp)
            root.post { clamp(); if (added) wm.updateViewLayout(root, lp) }
        }
    }

    private fun selectLevel(lv: Level, notify: Boolean) {
        prefs.level = lv
        for ((k, v) in levelBtns) v.background = btnBg(if (k == lv) C_CHIP_ON else C_CHIP_OFF)
        if (notify) cb.onLevelChanged(lv)
    }

    fun setAuto(on: Boolean, notify: Boolean) {
        auto = on
        ui.post {
            autoBtn.text = if (on) "⏸ STOP" else "▶ START"
            autoBtn.background = btnBg(if (on) C_STOP else C_GO)
        }
        if (notify) cb.onAutoChanged(on)
    }

    private fun attachDrag(handle: View) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    clamp()
                    if (added) wm.updateViewLayout(root, lp)
                    true
                }
                MotionEvent.ACTION_UP -> { prefs.panelX = lp.x; prefs.panelY = lp.y; true }
                else -> false
            }
        }
    }

    /** Keep the panel fully on screen (old saved positions / rotation). */
    private fun clamp() {
        val dm = ctx.resources.displayMetrics
        val w = if (root.width > 0) root.width else lp.width
        val h = if (root.height > 0) root.height else dp(76)
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - w))
        lp.y = lp.y.coerceIn(0, maxOf(0, dm.heightPixels - h))
    }

    fun show() {
        val dm = ctx.resources.displayMetrics
        lp.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else panelWidth()
        lp.x = if (prefs.panelX != Int.MIN_VALUE) prefs.panelX else (dm.widthPixels - panelWidth()) / 2
        lp.y = if (prefs.panelY != Int.MIN_VALUE) prefs.panelY else dm.heightPixels - dp(130)
        clamp()
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        added = true
    }

    /** Call after rotation / resolution change. */
    fun onScreenChanged() {
        ui.post {
            lp.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT else panelWidth()
            clamp()
            if (added) wm.updateViewLayout(root, lp)
        }
    }

    fun remove() {
        if (added) {
            try { wm.removeView(root) } catch (e: Exception) { }
            added = false
        }
    }

    fun setVisible(v: Boolean) { ui.post { root.visibility = if (v) View.VISIBLE else View.GONE } }

    fun setStatus(text: String) {
        if (text == lastText) return
        lastText = text
        ui.post { status.text = text }
    }

    fun setFrame(r: Reading?, best: Int) {
        if (prefs.showMini && !collapsed) mini.set(r, best)
    }

    /** Panel screen rectangle (warning if it covers the board). */
    fun screenRect(): Rect? {
        if (!added || root.visibility != View.VISIBLE || root.width == 0) return null
        return Rect(lp.x, lp.y, lp.x + root.width, lp.y + root.height)
    }
}
