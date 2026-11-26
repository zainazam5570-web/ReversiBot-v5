package com.reversibot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.reversibot.bot.GameLoop
import com.reversibot.engine.Level
import com.reversibot.vision.Reading

/**
 * Foreground service: screen capture + floating panel + game loop ek jagah.
 * MainActivity se MediaProjection ki permission ka result le kar start hoti hai.
 */
class BotService : Service(), GameLoop.Listener, OverlayPanel.Callbacks {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.reversibot.STOP"
        private const val CHANNEL = "reversibot"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null
    private var panel: OverlayPanel? = null
    private var loop: GameLoop? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = readProjectionData(intent)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission (display over other apps) on karein", Toast.LENGTH_LONG).show()
            stopSelf(); return START_NOT_STICKY
        }

        startAsForeground()
        prefs = Prefs(this)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        // Android 14+: VirtualDisplay banane se pehle callback register karna lazmi hai
        val cbk = object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }
        projectionCallback = cbk
        proj.registerCallback(cbk, main)

        capture = ScreenCapture(this, proj)

        val p = OverlayPanel(this, prefs, this)
        panel = p
        p.show()

        val l = GameLoop(
            frames = { capture?.grab() },
            geometry = { prefs.loadGeometry(isLandscape()) },
            tap = { x, y -> BotAccessibilityService.tap(x, y) },
            level = { prefs.level },
            listener = this
        )
        loop = l
        l.start()
        running = true
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_DATA)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reversi Bot", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Reversi Bot chal raha hai")
            .setContentText("Band karne ke liye Stop dabayen")
            .setSmallIcon(R.drawable.ic_stat)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun isLandscape(): Boolean = (capture?.let { it.width > it.height }) ?: false

    private fun currentGeometry() = loop?.activeGeometry ?: prefs.loadGeometry(isLandscape())

    // ---- GameLoop.Listener (background thread) ----
    override fun onStatus(text: String) {
        panel?.setStatus(text + overlapWarning())
    }

    override fun onFrame(reading: Reading?, myColor: Int, bestMove: Int) {
        panel?.setFrame(reading, bestMove)
    }

    /** Panel board ke upar ho to screenshot mein panel dikhega aur detection bigar sakti hai. */
    private fun overlapWarning(): String {
        val g = currentGeometry() ?: return ""
        val r = panel?.screenRect() ?: return ""
        val hit = RectF.intersects(RectF(g.left, g.top, g.right, g.bottom), RectF(r))
        return if (hit) "\n⚠ Panel board ke upar hai - side mein khiskayen" else ""
    }

    // ---- OverlayPanel.Callbacks (main thread) ----
    override fun onAutoChanged(on: Boolean) { loop?.autoPlay = on }

    override fun onLevelChanged(level: Level) { /* prefs mein save ho chuka, loop har baar prefs se padhta hai */ }

    override fun onCalibrate() {
        loop?.autoPlay = false
        panel?.setAuto(false, notify = false)
        panel?.setVisible(false)
        CalibrationOverlay(this, currentGeometry()) { g ->
            panel?.setVisible(true)
            if (g != null) {
                prefs.saveGeometry(isLandscape(), g)
                Toast.makeText(this, "Calibration save ho gayi", Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    override fun onClose() { stopSelf() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capture?.resize()
        panel?.onScreenChanged()
    }

    override fun onDestroy() {
        running = false
        loop?.stop(); loop = null
        panel?.remove(); panel = null
        capture?.release(); capture = null
        projectionCallback?.let { try { projection?.unregisterCallback(it) } catch (e: Exception) { } }
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        super.onDestroy()
    }
}
