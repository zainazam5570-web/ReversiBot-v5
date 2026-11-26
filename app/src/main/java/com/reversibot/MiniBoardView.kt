package com.reversibot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.reversibot.vision.Reading

/** Chhota board jo dikhata hai ke bot ko screen se kya nazar aa raha hai. */
class MiniBoardView(ctx: Context) : View(ctx) {
    private var reading: Reading? = null
    private var best = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(r: Reading?, bestMove: Int) {
        reading = r
        best = bestMove
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 8f
        p.style = Paint.Style.FILL
        p.color = 0xFF1F6B3A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), width.toFloat(), p)
        p.color = 0x55000000
        p.strokeWidth = 1f
        for (i in 0..8) {
            canvas.drawLine(i * s, 0f, i * s, width.toFloat(), p)
            canvas.drawLine(0f, i * s, width.toFloat(), i * s, p)
        }
        val r = reading ?: return
        for (i in 0 until 64) {
            val cx = (i and 7) * s + s / 2
            val cy = (i shr 3) * s + s / 2
            p.style = Paint.Style.FILL
            when {
                r.board[i] == 1 -> { p.color = 0xFF111111.toInt(); canvas.drawCircle(cx, cy, s * 0.4f, p) }
                r.board[i] == -1 -> { p.color = 0xFFF2F2F2.toInt(); canvas.drawCircle(cx, cy, s * 0.4f, p) }
                r.hints[i] -> { p.color = 0xFFFFC107.toInt(); canvas.drawCircle(cx, cy, s * 0.14f, p) }
            }
            if (i == best) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.12f
                p.color = 0xFF22C55E.toInt()
                canvas.drawCircle(cx, cy, s * 0.38f, p)
            }
        }
    }
}
