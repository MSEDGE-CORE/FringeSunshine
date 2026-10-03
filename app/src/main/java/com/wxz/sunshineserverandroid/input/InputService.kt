package com.wxz.sunshineserverandroid.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.wxz.sunshineserverandroid.ServerCore

/**
 * 无障碍服务：把 Moonlight 输入包转化为本机手势/按键。
 * 触摸、鼠标移动用 dispatchGesture；按键暂用 shell input keyevent（v1 限制）。
 */
class InputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ServerCore.log("无障碍输入服务已连接")
    }

    /**
     * 真值观察器：客户端注入的手势最终被目标 App 理解成了什么，只有这里能看见。
     *
     * 单击被识别成多次长按 → 这里会刷 LONG_CLICKED；滑动无效 → 永远看不到 SCROLLED。
     * 每秒最多 6 条，避免刷屏。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return
        val now = System.currentTimeMillis()
        if (now - lastEventLogMs < 160) return
        lastEventLogMs = now
        val name = when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
            else -> "TEXT_CHANGED"
        }
        ServerCore.log(
            "目标App收到 [$name] ${e.className ?: "?"}" +
                " 包=${e.packageName} 文本=${e.text?.joinToString("/")?.take(40)}"
        )
    }

    private var lastEventLogMs = 0L

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: InputService? = null

        fun isEnabled(): Boolean = instance != null
    }
}
