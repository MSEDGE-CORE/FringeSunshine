package com.wxz.sunshineserverandroid.stream

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.wxz.sunshineserverandroid.ServerCore

/**
 * 服务级常驻虚拟屏。
 *
 * Android 14+（本机 SDK 37）强制一个 MediaProjection 实例只能
 * createVirtualDisplay 一次，违规时系统直接 stop(STOP_ERROR) 并抛 SecurityException。
 * 因此 VD 在首次串流时创建一次，会话之间只做 setSurface/resize 切换，
 * 仅在服务停止或投影被系统终止时释放。会话可无限次进入，无需重新授权。
 */
class ProjectionDisplay(private val projection: MediaProjection) {

    private val lock = Any()
    private var display: VirtualDisplay? = null

    /** 投影是否仍可用（系统 onStop 或服务 release 后为 false） */
    @Volatile
    var alive = true
        private set

    /** 当前挂在 VD 上的编码器表面（用于会话间防错序 detach） */
    private var currentSurface: Surface? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            ServerCore.log("MediaProjection 已被系统停止，结束当前会话")
            alive = false
            releaseDisplay()
            ServerCore.session?.stop()
        }
    }

    init {
        // Android 14+ 要求 createVirtualDisplay 前必须已注册回调（服务启动时注册一次）
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    /**
     * 会话开始：把编码器输入表面挂到常驻 VD。
     * 首次调用创建 VD，之后仅 setSurface + resize。
     */
    fun attach(surface: Surface, width: Int, height: Int, densityDpi: Int): Boolean {
        synchronized(lock) {
            if (!alive) return false
            return try {
                val existing = display
                if (existing == null) {
                    val created = createVirtualDisplay(surface, width, height, densityDpi)
                    display = created
                    currentSurface = surface
                    ServerCore.log("常驻虚拟屏已创建：${width}x$height")
                    created != null
                } else {
                    existing.resize(width, height, densityDpi)
                    existing.setSurface(surface)
                    currentSurface = surface
                    ServerCore.log("常驻虚拟屏已切换表面：${width}x$height")
                    true
                }
            } catch (e: Exception) {
                ServerCore.log("挂载虚拟屏失败: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
    }

    /** 会话结束：仅当仍是当前会话的表面时卸下（VD 保留，防止旧会话收尾误清新会话） */
    fun detach(surface: Surface) {
        synchronized(lock) {
            if (currentSurface !== surface) return
            currentSurface = null
            try {
                display?.setSurface(null)
            } catch (_: Exception) {
            }
        }
    }

    /** 服务停止或投影失效：释放 VD 并注销回调 */
    fun release() {
        alive = false
        releaseDisplay()
        try {
            projection.unregisterCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun releaseDisplay() {
        synchronized(lock) {
            currentSurface = null
            try {
                display?.release()
            } catch (_: Exception) {
            }
            display = null
        }
    }

    /**
     * API 33/34/37 的公开签名一致：(name, w, h, dpi, flags, surface, callback, handler)。
     * 曾在 API<35 分支反射旧的 (surface 在 flags 前) 签名，Android 14 上必然
     * NoSuchMethodException → 虚拟屏挂载失败，直接用唯一可用重载即可。
     */
    private fun createVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int
    ): VirtualDisplay? = projection.createVirtualDisplay(
        "sunshine-video",
        width,
        height,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
        null
    )
}
