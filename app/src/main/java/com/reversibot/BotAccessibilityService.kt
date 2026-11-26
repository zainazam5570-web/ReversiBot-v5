package com.reversibot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Sirf ek kaam: screen par diye gaye point par tap karna (dispatchGesture).
 * Koi screen content nahi padhti.
 */
class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotAccessibilityService? = null

        /** Kisi bhi thread se call kiya ja sakta hai. false = service on nahi. */
        fun tap(x: Float, y: Float): Boolean {
            val s = instance ?: return false
            s.main.post { s.doTap(x, y) }
            return true
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun doTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
