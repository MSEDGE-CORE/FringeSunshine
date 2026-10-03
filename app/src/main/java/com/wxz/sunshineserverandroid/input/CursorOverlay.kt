package com.wxz.sunshineserverandroid.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import com.wxz.sunshineserverandroid.ServerCore

/**
 * 鼠标模式下的可见光标。
 *
 * 绝对鼠标坐标只更新一个虚拟指针，本机屏幕上没有任何可见反馈；
 * 这里加一层 TYPE_APPLICATION_OVERLAY 全屏透明窗口画一个箭头指针，
 * 该窗口会被 MediaProjection 一并采集，因此客户端画面里也能看到光标。
 * 需要"显示在其他应用上层"权限。
 */
object CursorOverlay {

    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    @Volatile
    private var attached = false

    @Volatile
    private var permissionWarned = false

    /** 绝对鼠标移动：把光标挪到 (x, y)（屏幕像素）；设置页关闭光标后直接不画 */
    fun moveTo(x: Float, y: Float) {
        handler.post {
            if (!ServerCore.cursorEnabled) {
                cursorView?.setCursor(0f, 0f, false)
                return@post
            }
            val context = ServerCore.appContext ?: return@post
            if (!Settings.canDrawOverlays(context)) {
                warnOnce(context)
                return@post
            }
            if (!ensureAttached(context)) return@post
            cursorView?.setCursor(x, y, true)
        }
    }

    /** 切回触摸模式或会话结束：隐藏光标（窗口保留，避免反复 add/remove 闪烁） */
    fun hide() {
        handler.post {
            cursorView?.setCursor(0f, 0f, false)
        }
    }

    // ---------------- 串流期间保持屏幕常亮 ----------------
    //
    // 虚拟屏是 display 0 的镜像：屏幕一息，SurfaceFlinger 就不再合成，
    // 镜像端拿不到任何新 buffer -> 编码器零输入 -> 客户端画面直接停住。
    // 策略：串流开始前点亮屏幕并挂 FLAG_KEEP_SCREEN_ON 拦住超时息屏；
    // 过程中用户主动息屏则尊重之，不再重新点亮。

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** 会话期间保住 CPU，防止息屏后设备休眠导致会话逻辑停摆 */
    private var cpuLock: android.os.PowerManager.WakeLock? = null

    /**
     * 会话开始：先点亮屏幕，再挂 FLAG_KEEP_SCREEN_ON 拦住「超时自动息屏」。
     *
     * 串流过程中用户主动按电源键息屏则不点亮：常亮标记只拦系统超时，
     * 之后屏幕保持息屏状态直到会话结束。
     */
    fun acquireKeepAwake() {
        handler.post {
            val context = ServerCore.appContext ?: return@post
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (cpuLock == null) {
                    cpuLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sunshine:cpu")
                        .apply {
                            setReferenceCounted(false)
                            acquire(60 * 60 * 1000L)
                        }
                }
                // 会话级 SCREEN_BRIGHT_WAKE_LOCK：与 FLAG_KEEP_SCREEN_ON 等价的拦超时手段，
                // 但不依赖悬浮窗窗口——缺"显示在其他应用上层"权限时 FLAG 挂不上，
                // 串流中途超时息屏会让虚拟屏停止合成。用户主动按电源键息屏不受它影响。
                if (wakeLock?.isHeld != true) {
                    @Suppress("DEPRECATION")
                    wakeLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "sunshine:session-keep-awake"
                    ).apply {
                        setReferenceCounted(false)
                        acquire(60 * 60 * 1000L)
                    }
                }
                if (!pm.isInteractive) {
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "sunshine:startup-wake"
                    )
                    wl.setReferenceCounted(false)
                    wl.acquire(5_000L)
                    ServerCore.log("串流开始/恢复：屏幕已熄灭，点亮后再继续")
                } else {
                    ServerCore.log("串流开始/恢复：屏幕已点亮")
                }
            } catch (e: Exception) {
                ServerCore.log("点亮屏幕失败：${e.javaClass.simpleName}")
            }
            // 悬浮窗尽量挂上（光标显示与 poke 逼帧仍依赖它），挂不上也不影响常亮
            if (ensureAttached(context)) {
                setKeepScreenOnFlag(context, true)
                ServerCore.log("已挂载常亮悬浮窗（串流期间阻止息屏）")
            } else if (!Settings.canDrawOverlays(context)) {
                ServerCore.log("缺少\"显示在其他应用上层\"权限：光标不显示，已用系统 wakelock 保持常亮")
            }
        }
    }

    /** 会话结束：放开常亮，允许系统按超时息屏 */
    fun releaseKeepAwake() {
        handler.post {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
                cpuLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            wakeLock = null
            cpuLock = null
            val context = ServerCore.appContext ?: return@post
            val v = cursorView ?: return@post
            setKeepScreenOnFlag(context, false, v)
        }
    }

    private fun setKeepScreenOnFlag(context: Context, on: Boolean, view: View? = cursorView) {
        val v = view ?: return
        val wm = windowManager ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        val has = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (has == on) return
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            wm.updateViewLayout(v, params)
        } catch (e: Exception) {
            ServerCore.log("更新常亮标记失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 逼源屏重新合成一帧。
     * 画面完全静止时 SurfaceFlinger 不产出新 buffer，编码器会一直 dequeue 不到输入；
     * 让本窗口 invalidate 一下就会触发一次合成，镜像端随之拿到新帧。
     */
    fun poke() {
        handler.post {
            cursorView?.invalidate()
        }
    }

    /** 悬浮窗是否已挂载（未授权/挂载失败时为 false，调用方需降级逼帧手段） */
    fun isAttached(): Boolean = attached

    fun detach() {
        handler.post {
            val wm = windowManager
            val v = cursorView
            if (attached && wm != null && v != null) {
                try {
                    wm.removeViewImmediate(v)
                } catch (_: Exception) {
                }
            }
            attached = false
            cursorView = null
            windowManager = null
        }
    }

    private fun warnOnce(context: Context) {
        if (permissionWarned) return
        permissionWarned = true
        ServerCore.log("鼠标光标不显示：缺少\"显示在其他应用上层\"权限")
    }

    private fun ensureAttached(context: Context): Boolean {
        if (attached && cursorView != null) return true
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = CursorView(context)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // API 30+ 由 fitInsetsTypes 决定是否被系统栏挤走，置 0 才铺满整屏
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                setFitInsetsIgnoringVisibility(true)
                // 打孔屏默认会把窗口裁到挖孔下方（本机正好矮 160px = 状态栏高）
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            wm.addView(view, params)
            windowManager = wm
            cursorView = view
            attached = true
            true
        } catch (e: Exception) {
            ServerCore.log("创建光标悬浮窗失败：${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private class CursorView(context: Context) : View(context) {

        private var cursorX = 0f
        private var cursorY = 0f
        private var visible = false
        private var loggedSize = false

        private val arrow: Path = Path().apply {
            // 热点在 (0,0) 的经典左上箭头
            moveTo(0f, 0f)
            lineTo(0f, 25f)
            lineTo(6.5f, 18.5f)
            lineTo(11f, 27f)
            lineTo(15f, 25f)
            lineTo(10.5f, 16.5f)
            lineTo(18f, 16.5f)
            close()
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeJoin = Paint.Join.ROUND
        }

        fun setCursor(x: Float, y: Float, show: Boolean) {
            cursorX = x
            cursorY = y
            visible = show
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (loggedSize) return
            loggedSize = true
            logGeometry()
        }

        private fun logGeometry() {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            ServerCore.log("光标悬浮窗视图：${width}x${height}，位于屏幕 (${loc[0]},${loc[1]})")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!visible) return
            // cursorX/Y 是物理屏绝对坐标，减掉窗口在屏幕上的位置才是本视图的绘制坐标
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(cursorX - loc[0], cursorY - loc[1])
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, stroke)
            canvas.restore()
        }
    }
}
