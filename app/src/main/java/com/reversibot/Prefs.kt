package com.reversibot

import android.content.Context
import com.reversibot.engine.Level
import com.reversibot.vision.BoardGeometry

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("reversibot", Context.MODE_PRIVATE)

    var level: Level
        get() = Level.values().getOrElse(sp.getInt("level", 1)) { Level.VERY_STRONG }
        set(v) { sp.edit().putInt("level", v.ordinal).apply() }

    var showMini: Boolean
        get() = sp.getBoolean("mini", true)
        set(v) { sp.edit().putBoolean("mini", v).apply() }

    var panelX: Int
        get() = sp.getInt("px", Int.MIN_VALUE)
        set(v) { sp.edit().putInt("px", v).apply() }

    var panelY: Int
        get() = sp.getInt("py", Int.MIN_VALUE)
        set(v) { sp.edit().putInt("py", v).apply() }

    /** Portrait aur landscape ki calibration alag alag save hoti hai. */
    fun saveGeometry(landscape: Boolean, g: BoardGeometry) {
        val k = if (landscape) "land" else "port"
        sp.edit()
            .putFloat("${k}_l", g.left).putFloat("${k}_t", g.top)
            .putFloat("${k}_r", g.right).putFloat("${k}_b", g.bottom)
            .apply()
    }

    fun loadGeometry(landscape: Boolean): BoardGeometry? {
        val k = if (landscape) "land" else "port"
        if (!sp.contains("${k}_l")) return null
        return BoardGeometry(
            sp.getFloat("${k}_l", 0f), sp.getFloat("${k}_t", 0f),
            sp.getFloat("${k}_r", 0f), sp.getFloat("${k}_b", 0f)
        )
    }
}
