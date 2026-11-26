package com.reversibot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.reversibot.vision.BoardGeometry
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay: board ke UPAR-LEFT aur NEECHE-RIGHT corner par tap karein,
 * phir zaroorat ho to corners ko drag karke grid ko squares par barabar karein.
 */
class CalibrationOverlay(
    private val ctx: Context,
    private val initial: BoardGeometry?,
    private val onResult: (BoardGeometry?) -> Unit
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private lateinit var root: FrameLayout
    private lateinit var view: CalView
    private lateinit var bar: LinearLayout
    private var shown = false

    fun show() {
        view = CalView(ctx)
        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        fun btn(t: String, color: Int, f: () -> Unit) = TextView(ctx).apply {
            text = t; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
            setOnClickListener { f() }
        }
        fun m() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(5); rightMargin = dp(5) }
        bar.addView(btn("✔ Save", 0xFF16A34A.toInt()) { save() }, m())
        bar.addView(btn("↺ Redo", 0xFF2C3E50.toInt()) { view.reset(); bar.visibility = View.GONE }, m())
        bar.addView(btn("✕ Cancel", 0xFFDC2626.toInt()) { finish(null) }, m())

        root = FrameLayout(ctx)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70) }
        )
        view.onReady = { bar.visibility = View.VISIBLE }
        initial?.let { view.setRect(it.left, it.top, it.right, it.bottom); bar.visibility = View.VISIBLE }

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(root, lp)
        shown = true
    }

    private fun save() {
        val r = view.rect
        val g = BoardGeometry(min(r.left, r.right), min(r.top, r.bottom), max(r.left, r.right), max(r.top, r.bottom))
        if (g.right - g.left < dp(100) || g.bottom - g.top < dp(100)) {
            view.warn = "Rectangle bohat chhota hai - dobara try karein"
            view.invalidate()
            return
        }
        finish(g)
    }

    private fun finish(g: BoardGeometry?) {
        if (shown) {
            try { wm.removeView(root) } catch (e: Exception) { }
            shown = false
        }
        onResult(g)
    }

    private inner class CalView(c: Context) : View(c) {
        val rect = RectF()
        private var phase = 0          // 0: pehla tap, 1: doosra tap, 2: adjust
        private var drag = -1
        var warn = ""
        var onReady: (() -> Unit)? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val loc = IntArray(2)

        fun setRect(l: Float, t: Float, r: Float, b: Float) { rect.set(l, t, r, b); phase = 2; invalidate() }
        fun reset() { rect.setEmpty(); phase = 0; warn = ""; invalidate() }

        private fun corners(): Array<FloatArray> = arrayOf(
            floatArrayOf(rect.left, rect.top), floatArrayOf(rect.right, rect.top),
            floatArrayOf(rect.left, rect.bottom), floatArrayOf(rect.right, rect.bottom)
        )

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.rawX
            val y = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    warn = ""
                    when (phase) {
                        0 -> { rect.set(x, y, x, y); phase = 1 }
                        1 -> { rect.right = x; rect.bottom = y; phase = 2; onReady?.invoke() }
                        else -> {
                            var best = -1; var bd = dp(80).toFloat()
                            corners().forEachIndexed { i, c ->
                                val d = hypot(c[0] - x, c[1] - y)
                                if (d < bd) { bd = d; best = i }
                            }
                            drag = best
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (phase == 2 && drag >= 0) {
                    when (drag) {
                        0 -> { rect.left = x; rect.top = y }
                        1 -> { rect.right = x; rect.top = y }
                        2 -> { rect.left = x; rect.bottom = y }
                        3 -> { rect.right = x; rect.bottom = y }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x55000000)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val stroke = dp(2).toFloat()
            if (phase >= 1) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = stroke
                p.color = 0xFF22D3EE.toInt()
                canvas.drawRect(rect, p)
                if (phase == 2) {
                    p.strokeWidth = 1f
                    p.color = 0xAA22D3EE.toInt()
                    for (i in 1..7) {
                        val fx = rect.left + (rect.right - rect.left) * i / 8f
                        val fy = rect.top + (rect.bottom - rect.top) * i / 8f
                        canvas.drawLine(fx, rect.top, fx, rect.bottom, p)
                        canvas.drawLine(rect.left, fy, rect.right, fy, p)
                    }
                }
            }
            p.style = Paint.Style.FILL
            p.color = 0xFFFACC15.toInt()
            if (phase == 1) canvas.drawCircle(rect.left, rect.top, dp(8).toFloat(), p)
            if (phase == 2) for (c in corners()) canvas.drawCircle(c[0], c[1], dp(11).toFloat(), p)
            canvas.restore()

            val lines = ArrayList<String>()
            when (phase) {
                0 -> lines.addAll(listOf("Step 1/2", "Board ke UPAR-LEFT corner", "par tap karein"))
                1 -> lines.addAll(listOf("Step 2/2", "Board ke NEECHE-RIGHT corner", "par tap karein"))
                else -> lines.addAll(listOf("Peele corners drag karke grid", "ko 8x8 squares par barabar karein", "phir Save dabayen"))
            }
            if (warn.isNotEmpty()) lines.add(warn)
            p.textSize = 15f * resources.displayMetrics.scaledDensity
            val lh = p.textSize * 1.25f
            val w = lines.maxOf { p.measureText(it) } + dp(24)
            val h = lh * lines.size + dp(16)
            val left = (width - w) / 2f
            val top = dp(60).toFloat()
            p.color = 0xE6101820.toInt()
            canvas.drawRoundRect(left, top, left + w, top + h, dp(12).toFloat(), dp(12).toFloat(), p)
            p.color = 0xFFFFFFFF.toInt()
            lines.forEachIndexed { i, s ->
                if (warn.isNotEmpty() && i == lines.size - 1) p.color = 0xFFF87171.toInt()
                canvas.drawText(s, left + dp(12), top + dp(8) + lh * (i + 1) - p.textSize * 0.25f, p)
            }
        }
    }
}
