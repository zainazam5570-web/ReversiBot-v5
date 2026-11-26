package com.reversibot

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.Display
import com.reversibot.vision.PixelSource

/** Image ke buffer se seedha pixel padhta hai (bitmap banaye baghair, tez). */
class ImagePixelSource(image: Image) : PixelSource {
    override val width: Int = image.width
    override val height: Int = image.height
    private val plane = image.planes[0]
    private val buf = plane.buffer
    private val rowStride = plane.rowStride
    private val pixelStride = plane.pixelStride

    override fun pixel(x: Int, y: Int): Int {
        val o = y * rowStride + x * pixelStride
        val r = buf.get(o).toInt() and 0xFF
        val g = buf.get(o + 1).toInt() and 0xFF
        val b = buf.get(o + 2).toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * MediaProjection se poori screen ka live capture. Resolution = asli screen resolution,
 * is liye pixel coordinates aur tap coordinates ek hi hain.
 */
class ScreenCapture(private val ctx: Context, private val projection: MediaProjection) {
    var width = 0; private set
    var height = 0; private set
    private var dpi = 0
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var current: Image? = null
    private val lock = Any()

    init {
        readMetrics()
        val r = newReader()
        reader = r
        display = projection.createVirtualDisplay(
            "ReversiBotCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun readMetrics() {
        val dm = DisplayMetrics()
        val d = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels
        dpi = dm.densityDpi
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

    /**
     * Sab se taaza frame. Screen static ho to naya frame nahi aata, tab pichla (abhi bhi sahi) frame milta hai.
     * Pichla PixelSource agle grab() par invalid ho jata hai - sirf ek thread se istemal karein.
     */
    fun grab(): PixelSource? {
        synchronized(lock) {
            val r = reader ?: return null
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                current?.close()
                current = img
            }
            val cur = current ?: return null
            return ImagePixelSource(cur)
        }
    }

    /** Screen rotate ho to naye size par chalao. */
    fun resize() {
        synchronized(lock) {
            val ow = width; val oh = height
            readMetrics()
            if (ow == width && oh == height) return
            current?.close(); current = null
            val old = reader
            val nr = newReader()
            display?.surface = nr.surface
            display?.resize(width, height, dpi)
            reader = nr
            old?.close()
        }
    }

    fun release() {
        synchronized(lock) {
            try { display?.release() } catch (e: Exception) { }
            display = null
            try { current?.close() } catch (e: Exception) { }
            current = null
            try { reader?.close() } catch (e: Exception) { }
            reader = null
        }
    }
}
