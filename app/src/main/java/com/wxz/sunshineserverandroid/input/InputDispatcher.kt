package com.wxz.sunshineserverandroid.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.wxz.sunshineserverandroid.ServerCore
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 解析 NV 输入包（moonlight-common-c Input.h 的字节格式）并注入本机。
 *
 * 每个输入包：{u32 BE size（不含该字段）, u32 LE magic} + 包体。
 * 支持：触摸(0x55000002)、绝对鼠标(0x00000005)、鼠标按键(0x00000008/09)、
 * 滚轮(0x0000000A/0x55000001)、按键(0x00000003/04)、UTF-8 文本(0x00000017)。
 *
 * 注入途径：触摸/鼠标 → 无障碍手势；按键/文本 → 输入法 InputConnection，
 * 输入法未接入时按键退回无障碍全局动作。
 */
object InputDispatcher {

    // SS_TOUCH_PACKET.eventType

    // 鼠标按键
    private const val BUTTON_LEFT = 0x01
    private const val BUTTON_RIGHT = 0x03

    /** 当前指针位置（屏幕像素） */
    @Volatile
    private var lastX = -1f

    @Volatile
    private var lastY = -1f

    /** 触摸按下后的活动 stroke，用于 continueStroke 实现 move/up */
    @Volatile
    private var activeStroke: GestureDescription.StrokeDescription? = null

    fun dispatch(buffer: ByteArray, start: Int, end: Int) {
        var offset = start
        while (offset + 8 <= end) {
            val size = be32(buffer, offset)
            if (size < 4) break
            val total = 4 + size
            if (offset + total > end) break
            val magic = le32(buffer, offset + 4)
            val bodyStart = offset + 8
            val bodyEnd = minOf(offset + total, end)
            handlePacket(magic, buffer, bodyStart, bodyEnd)
            offset += total
        }
    }

    private fun handlePacket(magic: Int, buffer: ByteArray, start: Int, end: Int) {
        noteInput(magic)
        when (magic) {
            0x00000005 -> handleAbsMouse(buffer, start, end)
            0x00000008, 0x00000009 -> handleMouseButton(magic, buffer, start, end)
            0x0000000A, 0x55000001 -> handleScroll(buffer, start, end)
            0x00000003, 0x00000004 -> handleKey(magic, buffer, start, end)
            0x00000017 -> handleText(buffer, start, end)
        }
    }

    /** 输入到达日志（每类最多 1 条/秒，用于确认控制流解密后输入确实派发到了本机） */
    private var lastInputLogMs = 0L

    private fun noteInput(magic: Int) {
        val now = System.currentTimeMillis()
        if (now - lastInputLogMs < 1000) return
        lastInputLogMs = now
        val name = when (magic) {
            0x55000002 -> "触摸(忽略)"
            0x00000005 -> "绝对鼠标"
            0x00000008, 0x00000009 -> "鼠标按键"
            0x0000000A, 0x55000001 -> "滚轮"
            0x00000003, 0x00000004 -> "按键"
            0x00000017 -> "文本"
            else -> "0x${magic.toString(16)}"
        }
        val svc = if (service() != null) "已连接" else "未连接"
        ServerCore.log("输入派发：$name（辅助功能=$svc）")
    }

    // ---------------- 鼠标按键：UP 之后才合成操作 ----------------
    //
    // 之前左键一按下就把屏幕按住，抬手那一下再从 A 跳到 B —— 中间鼠标随手一动就变成
    // 一次瞬移拖拽，App 看到的就是「点一下却滚了一屏」；而且按住不放时 stroke 一直续约，
    // 按住 500ms 就被系统判成长按。
    //
    // 现在：**按住期间不上屏任何 delta**，只记采样；一切都在松手那一刻才识别 ——
    //   · 位移 ≥ 阈值 → **滚动**：把「记忆的 DOWN 位置 → 抬手位置」整段注入一次，
    //     时长按抬手前的速率换算（快 = 短时长 = 快滚，慢 = 长时长 = 慢滚）。
    //     段位移必须 ≥ MIN_SCROLL_SEGMENT_PX，否则系统会把「按下→微移→抬起」
    //     读成**单击**，在按下位置误触发点击。
    //   · 位移 < 阈值且按住 ≥500ms → 上屏**长按**
    //   · 位移 < 阈值且短按        → 上屏**单击**
    private const val LONG_PRESS_MS = 500L

    /**
     * 单击/长按的「按下→抬起」时长**复刻真实按住时长**（UP 时刻 - DOWN 时刻），
     * 只在两端夹一下：太短系统不认，太长会被 TOUCH_HOLD_MS 强制收尾。
     */
    private const val MIN_PRESS_DURATION_MS = 40L
    private const val MAX_PRESS_DURATION_MS = 4_000L

    /** 上屏右键点击的「按下→抬起」模拟时长 */
    private const val RIGHT_CLICK_DURATION_MS = 120L

    /** 滑动识别阈值：抬手前累计位移达到它就算滚动，否则按单击/长按处理 */
    private const val DRAG_THRESHOLD_PX = 60f

    /** 单个滚动段的最小位移（小于触摸 slop 会被 App 读成单击），必须 ≥ DRAG_THRESHOLD_PX */
    private const val MIN_SCROLL_SEGMENT_PX = 60f

    /** 按住期间保留的采样点数（够算 0.5s 以上的速率即可） */
    private const val MAX_DRAG_SAMPLES = 64

    /** 一次性滚动路径最多画多少个点（识别到的位置太多时均匀抽样） */
    private const val MAX_SCROLL_STEPS = 64

    /** 抬手前取这个时间窗内的位移算速率（fling 速度） */
    private const val FLING_WINDOW_MS = 250L

    /** 滚动默认段时长（滚轮用）；按住拖动改用速率换算的时长 */
    private const val SCROLL_SEGMENT_MS = 150L

    private const val MIN_SCROLL_DUR_MS = 120L
    private const val MAX_SCROLL_DUR_MS = 3_000L
    private const val MAX_SCROLL_DIST = 1600f

    /** 鼠标左键是否按着（只表示客户端按着，不等于屏幕上已经按压） */
    @Volatile
    private var leftButtonDown = false

    @Volatile
    private var armX = 0f

    @Volatile
    private var armY = 0f

    @Volatile
    private var armAtMs = 0L

    /** 是否已经收到过一次 DOWN（没有就用屏幕中心兜底） */
    @Volatile
    private var armKnown = false

    /** 按住期间的位移采样（x/y/时刻），UP 时据此识别滚动与速率 */
    private val dragSX = FloatArray(MAX_DRAG_SAMPLES)
    private val dragSY = FloatArray(MAX_DRAG_SAMPLES)
    private val dragST = LongArray(MAX_DRAG_SAMPLES)
    private var dragN = 0

    /** 抬手后延迟释放的长按任务（新按下会取消它） */
    @Volatile
    private var pendingRelease: Runnable? = null

    /** 上屏按下（现在只有鼠标合成的点击/长按会用到）；返回是否成功按下 */
    private fun pressAt(x: Float, y: Float): Boolean {
        if (held) return false
        pendingRelease?.let { uiHandler.removeCallbacks(it); pendingRelease = null }
        beginTouch(x, y)
        return true
    }

    /** 上屏松手 */
    private fun releaseAt(x: Float, y: Float) {
        if (!held) return
        pendingRelease?.let { uiHandler.removeCallbacks(it); pendingRelease = null }
        endTouch(x, y)
    }

    /** 所有合成的 down→up 都必须带一个模拟时长，不能背靠背发完就结束 */
    private fun pressAndRelease(x: Float, y: Float, durationMs: Long) {
        if (!pressAt(x, y)) return
        val release = Runnable { releaseAt(x, y) }
        pendingRelease = release
        uiHandler.postDelayed(release, durationMs)
    }

    // ---------------- 绝对鼠标 ----------------

    /** NV_ABS_MOUSE_MOVE_PACKET：x/y/unused/width/height 均 BE16 */
    private fun handleAbsMouse(buffer: ByteArray, start: Int, end: Int) {
        if (end - start < 10) return
        val x = be16(buffer, start)
        val y = be16(buffer, start + 2)
        val width = be16(buffer, start + 6)
        val height = be16(buffer, start + 8)
        if (width <= 0 || height <= 0) return
        val (screenX, screenY) = videoToScreen(x.toFloat() / width, y.toFloat() / height)
        lastX = screenX
        lastY = screenY
        noteGeometry(x, y, width, height, screenX, screenY)
        CursorOverlay.moveTo(screenX, screenY)

        // 按住期间**不上屏任何 delta**：只记采样，UP 时一次性按速率识别滚动/单击。
        if (leftButtonDown) recordDragSample(screenX, screenY)
    }

    private fun recordDragSample(x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        if (dragN < MAX_DRAG_SAMPLES) {
            val i = dragN
            dragSX[i] = x; dragSY[i] = y; dragST[i] = t
            dragN = i + 1
        } else {
            System.arraycopy(dragSX, 1, dragSX, 0, MAX_DRAG_SAMPLES - 1)
            System.arraycopy(dragSY, 1, dragSY, 0, MAX_DRAG_SAMPLES - 1)
            System.arraycopy(dragST, 1, dragST, 0, MAX_DRAG_SAMPLES - 1)
            val i = MAX_DRAG_SAMPLES - 1
            dragSX[i] = x; dragSY[i] = y; dragST[i] = t
        }
    }

    private fun clearDragSamples() {
        dragN = 0
    }

    /** 抬手前 FLING_WINDOW_MS 窗口内的速率（px/ms）；样本不足则退回全程均速 */
    private fun dragSpeed(dxArm: Float, dyArm: Float, armT: Long): Float {
        if (dragN == 0) return 0f
        val li = dragN - 1
        val tEnd = dragST[li]
        var base = 0
        for (i in li downTo 0) {
            if (tEnd - dragST[i] >= FLING_WINDOW_MS) { base = i; break }
        }
        val bx = if (base == li) dxArm else dragSX[li] - dragSX[base]
        val by = if (base == li) dyArm else dragSY[li] - dragSY[base]
        val dt = if (base == li) (tEnd - armT) else (tEnd - dragST[base])
        return if (dt > 0) hypot(bx, by) / dt else 0f
    }

    private var lastGeoLogMs = 0L

    /** 每 5s 打一次几何参数，便于校准黑边扣除是否正确 */
    private fun noteGeometry(mx: Int, my: Int, mw: Int, mh: Int, sx: Float, sy: Float) {
        val now = System.currentTimeMillis()
        if (now - lastGeoLogMs < 5000) return
        lastGeoLogMs = now
        val g = geometry()
        val scale = minOf(g.videoW / g.screenW, g.videoH / g.screenH)
        val barX = (g.videoW - g.screenW * scale) / 2f
        val barY = (g.videoH - g.screenH * scale) / 2f
        ServerCore.log(
            "光标几何：鼠标 $mx/$mw,$my/$mh → (%d,%d)".format(sx.toInt(), sy.toInt()) +
                "，屏幕 ${g.screenW.toInt()}x${g.screenH.toInt()}" +
                "，视频 ${g.videoW.toInt()}x${g.videoH.toInt()}" +
                "，黑边 L${barX.toInt()}/T${barY.toInt()}" +
                "，系统栏 T${g.insetTop.toInt()}/B${g.insetBottom.toInt()}"
        )
    }

    private fun handleMouseButton(magic: Int, buffer: ByteArray, start: Int, end: Int) {
        if (end - start < 1) return
        val button = buffer[start].toInt() and 0xFF
        val down = magic == 0x00000008
        when (button) {
            BUTTON_LEFT -> if (down) {
                if (leftButtonDown || lastX < 0) return
                leftButtonDown = true
                armX = lastX
                armY = lastY
                armKnown = lastX >= 0
                clearDragSamples()
                armAtMs = android.os.SystemClock.uptimeMillis()
            } else {
                if (!leftButtonDown) return
                leftButtonDown = false
                finishMouseButton()
            }
            BUTTON_RIGHT -> if (down && lastX >= 0 && foreignDispatchAllowed()) {
                dispatchSimpleTap(lastX, lastY, RIGHT_CLICK_DURATION_MS)
            }
        }
    }

    /** 左键抬起：**滚动/单击/长按三者都在这里才识别**，按住期间不上屏任何东西 */
    private fun finishMouseButton() {
        val x = if (armKnown) armX else lastX
        val y = if (armKnown) armY else lastY
        val armT = armAtMs
        if (x < 0) {
            clearDragSamples()
            return
        }

        // 必须在 clearDragSamples() **之前**把采样读出来，否则速率/按住时长全是 0
        val n = dragN
        if (armKnown && n > 0) {
            val li = n - 1
            val dx = dragSX[li] - x
            val dy = dragSY[li] - y
            val dist = hypot(dx, dy)
            val endT = dragST[li]
            val speed = dragSpeed(dx, dy, armT)
            if (dist >= DRAG_THRESHOLD_PX) {
                // emitScroll 要读采样，必须排在 clearDragSamples() 之前
                emitScroll(dx, dy, dist, speed, armT, endT)
                clearDragSamples()
                return
            }
            clearDragSamples()
        } else {
            clearDragSamples()
        }

        // 单击/长按都按**真实按住时长**复刻上屏，只在两端夹一下
        val heldMs = android.os.SystemClock.uptimeMillis() - armT
        val pressMs = heldMs.coerceIn(MIN_PRESS_DURATION_MS, MAX_PRESS_DURATION_MS)
        val kind = if (heldMs >= LONG_PRESS_MS) "长按" else "单击"
        ServerCore.log(
            "UP识别为$kind 按住=${heldMs}ms → 上屏按下→抬起 ${pressMs}ms" +
                "（真实时长复刻，落点 ${x.toInt()},${y.toInt()}）"
        )
        pressAndRelease(x, y, pressMs)
    }

    /**
     * UP 时注入的滚动：**一次性**在记忆的 DOWN 位置按下 →依次经过识别到的每个位置 → 抬起。
     * 整个过程只有一个手势、一个总时长（= 位移 / 抬手前的速率 → 输入越快上屏越快）。
     *
     * 不再拆成多段链：多段链每段都要重新按下，会把「最初的按下点」重置掉。
     */
    private fun emitScroll(dx: Float, dy: Float, dist: Float, speed: Float, armT: Long, endT: Long) {
        val total = (if (speed > 0.001f) (dist / speed).toLong() else MAX_SCROLL_DUR_MS)
            .coerceIn(MIN_SCROLL_DUR_MS, MAX_SCROLL_DUR_MS)
        val n = dragN
        if (n <= 0) return

        val ax = if (armKnown) armX else screenWidth() / 2f
        val ay = if (armKnown) armY else screenHeight() / 2f
        val startX = clampX(ax)
        val startY = clampY(ay)
        val stride = if (n > MAX_SCROLL_STEPS) (n + MAX_SCROLL_STEPS - 1) / MAX_SCROLL_STEPS else 1

        val path = Path()
        path.moveTo(startX, startY)
        var lx = startX
        var ly = startY
        var pts = 1
        var i = 0
        while (i < n && pts < MAX_SCROLL_STEPS) {
            val px = clampX(dragSX[i])
            val py = clampY(dragSY[i])
            if (abs(px - lx) >= 1f || abs(py - ly) >= 1f) {
                path.lineTo(px, py)
                lx = px; ly = py; pts++
            }
            i += stride
        }
        // 末点必取：抬手位置一定被模拟到
        val ex = clampX(dragSX[n - 1])
        val ey = clampY(dragSY[n - 1])
        if (abs(ex - lx) >= 1f || abs(ey - ly) >= 1f) path.lineTo(ex, ey)

        ServerCore.log(
            "UP识别为滚动 dist=${dist.toInt()}px 速度=%.3fpx/ms".format(speed) +
                " 按住=${endT - armT}ms → **一次性**总时长=${total}ms" +
                " 路径=${pts}个点 着力点=(${ax.toInt()},${ay.toInt()})"
        )
        dispatchScrollPath(path, total)
    }

    /** NV_SCROLL_PACKET/SS_HSCROLL：scrollAmt1、scrollAmount 均 BE16 */
    private fun handleScroll(buffer: ByteArray, start: Int, end: Int) {
        if (end - start < 2) return
        val signed = be16Signed(buffer, start)
        if (signed == 0) return

        // 输入速度 → 上屏速度：两次滚轮事件间隔越短，本次换算出的距离越长。
        // 指数平滑，避免单次抖动把页面甩出去。
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastWheelMs == 0L) 100L else (now - lastWheelMs).coerceIn(10L, 500L)
        lastWheelMs = now
        val raw = (100f / dt).coerceIn(0.5f, 8f) // 10 次/秒记为基准 1.0
        wheelSpeed = wheelSpeed * 0.5f + raw * 0.5f

        // 滚动的着力点 = 按下时的那个位置（没有按键按下就取本次滚动起始时的光标位置），
        // 不能用已经移动过的新光标位置，否则滚着滚着会跑到别的控件上。
        val burst = lastWheelMs == 0L || now - lastWheelMs > SCROLL_BURST_MS
        // 着力点 = 记忆里输入端执行 DOWN 的那个位置（armX/armY），**永远不用当前光标**。
        // 光标随后怎么移动都不影响 delta 落点；只有新的 DOWN 才会更新记忆。
        if (burst) {
            val ax = if (armKnown) armX else screenWidth() / 2f
            val ay = if (armKnown) armY else screenHeight() / 2f
            ServerCore.log("滚动着力点 = 记忆的 DOWN 位置 (${ax.toInt()},${ay.toInt()})")
        }

        val notch = max(1, abs(signed) / 40) * 120f
        pendingScrollDY += if (signed > 0) -notch * wheelSpeed else notch * wheelSpeed
        pendingScrollDY = pendingScrollDY.coerceIn(-2400f, 2400f)
        tryDispatchScroll()
    }

    private var lastWheelMs = 0L

    /** 本次滚动的着力点（按下位置 / 滚动开始时的光标位置） */
    /** 两次滚轮事件间隔超过它就算一次新的滚动 */
    private const val SCROLL_BURST_MS = 300L

    @Volatile
    private var wheelSpeed = 1f

    /** 在途滚动期间累积的位移（双轴），等上一段跑完再送，避免手势互相取消 */
    @Volatile
    private var pendingScrollDX = 0f

    @Volatile
    private var pendingScrollDY = 0f

    /** 下一段滚动的时长：滚轮用 SCROLL_SEGMENT_MS，按住拖动用速率换算值 */
    @Volatile
    private var pendingScrollMs = SCROLL_SEGMENT_MS

    @Volatile
    private var scrollInFlight = false

    private fun tryDispatchScroll() {
        if (scrollInFlight) return
        if (!foreignDispatchAllowed()) return
        val dx = pendingScrollDX
        val dy = pendingScrollDY
        if (hypot(dx, dy) < MIN_SCROLL_SEGMENT_PX) {
            // 太小：派发出去就是「按下→微移→抬起」，App 会当成点一下。攒够再发。
            return
        }
        pendingScrollDX = 0f
        pendingScrollDY = 0f
        noteScroll(dx, dy)
        // 着力点 = 记忆里执行 DOWN 的位置，永远不用当前光标、也不兜底到屏幕中心
        val ax = if (armKnown) armX else screenWidth() / 2f
        val ay = if (armKnown) armY else screenHeight() / 2f
        val startX = clampX(ax)
        val startY = clampY(ay)
        val endX = clampX(startX + dx)
        val endY = clampY(startY + dy)
        if (endX == startX && endY == startY) return
        val duration = pendingScrollMs.coerceIn(MIN_SCROLL_DUR_MS, MAX_SCROLL_DUR_MS)
        pendingScrollMs = SCROLL_SEGMENT_MS
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        dispatchScrollPath(path, duration)
    }

    /** 一次性滚动手势：按下点 → 路径上的每个点 → 抬起，只有这一个手势，中途不重置按下点 */
    private fun dispatchScrollPath(path: Path, duration: Long) {
        if (scrollInFlight) {
            ServerCore.log("上一段滚动尚未结束，本次滚动跳过")
            return
        }
        if (!foreignDispatchAllowed()) return
        val svc = service() ?: return
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        scrollInFlight = true
        val ok = try {
            svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = settleScroll()

                override fun onCancelled(gestureDescription: GestureDescription) = settleScroll()
            }, uiHandler)
        } catch (e: Exception) {
            ServerCore.log("滚动手势异常：${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) {
            scrollInFlight = false
            return
        }
        uiHandler.postDelayed({ settleScroll() }, duration + 500)
    }

    private var lastScrollLogMs = 0L

    private fun noteScroll(dx: Float, dy: Float) {
        val now = System.currentTimeMillis()
        if (now - lastScrollLogMs < 1000) return
        lastScrollLogMs = now
        val ax = if (armKnown) armX else screenWidth() / 2f
        val ay = if (armKnown) armY else screenHeight() / 2f
        ServerCore.log("滚动段 delta=(${dx.toInt()},${dy.toInt()}) 着力点=(${ax.toInt()},${ay.toInt()})")
    }

    private fun settleScroll() {
        if (!scrollInFlight) return
        scrollInFlight = false
        if (pendingScrollDX != 0f || pendingScrollDY != 0f) tryDispatchScroll()
    }

    // ---------------- 按键 / 文本 ----------------

    /**
     * NV_KEYBOARD_PACKET：flags@0, keyCode LE16@1（Windows VK 码）, modifiers@3, zero2@4。
     *
     * 投屏输入法已移除（本机不再支持远程打字）。无障碍服务没有按键注入 API，
     * 只能退化为全局动作：返回/主页/多任务/方向/确认 仍然可用。
     */
    private fun handleKey(magic: Int, buffer: ByteArray, start: Int, end: Int) {
        if (end - start < 6) return
        if (magic != KEY_DOWN_EVENT_MAGIC) return
        val vk = le16(buffer, start + 1)
        val androidKey = KeyMap.vkToAndroid(vk) ?: return

        val svc = service()
        if (svc == null) {
            noteNoIme("按键")
            return
        }
        val handled = when (androidKey) {
            android.view.KeyEvent.KEYCODE_ESCAPE ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            android.view.KeyEvent.KEYCODE_F1, android.view.KeyEvent.KEYCODE_HOME ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            android.view.KeyEvent.KEYCODE_F2, android.view.KeyEvent.KEYCODE_F3 ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            android.view.KeyEvent.KEYCODE_MENU ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_MENU)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
            android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER ->
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
            else -> false
        }
        if (!handled) noteNoIme("按键")
    }

    /**
     * UTF8_TEXT_EVENT_MAGIC（Sunshine 扩展）：body = 客户端直接给出的 UTF-8 文本，
     * 由输入法 commitText，绕过按键映射，中英文混输最可靠。
     */
    private fun handleText(buffer: ByteArray, start: Int, end: Int) {
        if (end <= start) return
        noteNoIme("文本输入")
    }

    private var lastNoImeWarnMs = 0L

    private fun noteNoIme(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastNoImeWarnMs < 5000) return
        lastNoImeWarnMs = now
        ServerCore.log("${what}不可用：投屏输入法已移除，本机不再支持远程打字")
    }

    // ---------------- 上屏手势（串行派发） ----------------
    //
    // dispatchGesture 的官方语义是「派发即取消进行中的手势」（见 AccessibilityService
    // javadoc: "Any gestures currently in progress ... will be cancelled"）。
    //
    // 之前每个 MOVE 都立刻派发，上一段 segment 还没走到终点就被下一次派发取消，
    // 系统只注入了段起点 → 指针一动不动 → **滑动完全无效**；
    // 而 UP 那段若被中途吞掉，TOUCH_HOLD_MS 的长按就一直压着 → **单击变成长按**。
    //
    // 现在改成：同一时刻只允许一个手势在跑，等 onCompleted/onCancelled 回来再派发下一段，
    // 中间新到的坐标合并成一段，保证每段都能走到终点把坐标注入进去。

    /** 单段位移时长：≥2 个采样周期（120Hz≈8ms），保证末点能被注入 */
    private const val SEGMENT_MS = 24L

    /**
     * 按住不动时的续约窗口。
     * 上一段走完才会派发下一段，所以这个值直接决定「收到 UP 之后还要多久才真正抬手」——
     * 太大 → 单击被拉成长按；太小 → 续约派发太频繁。80ms 是折中。
     */
    private const val HOLD_RENEW_MS = 80L

    /** 回调丢失时的兜底，超过就强制推进，否则输入会永久卡死 */
    private const val STROKE_TIMEOUT_MS = 1_000L

    /** 一直没有新输入事件则强制收尾，避免释放丢失后屏幕被永久按住 */
    private const val TOUCH_HOLD_MS = 5_000L

    /**
     * GestureDescription.StrokeDescription 会硬校验 path 四角坐标全部 ≥0，
     * 出现任何负坐标就抛 IllegalArgumentException（路径边界不能为负）并杀掉进程，
     * 因此所有手势坐标统一夹回屏幕内。
     */
    private fun clampX(v: Float): Float =
        if (v.isNaN()) 0f else v.coerceIn(0f, max(0f, screenWidth() - 1f))

    private fun clampY(v: Float): Float =
        if (v.isNaN()) 0f else v.coerceIn(0f, max(0f, screenHeight() - 1f))

    private val gestureLock = Any()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 屏幕上是否正被按住（鼠标合成的点击/长按） */
    @Volatile
    private var held = false

    /** 已派发链的末端坐标（下一段的起点必须与它重合） */
    @Volatile
    private var chainX = 0f

    @Volatile
    private var chainY = 0f

    /** 在途派发期间新到的坐标（合并成一段） */
    @Volatile
    private var pendingX = 0f

    @Volatile
    private var pendingY = 0f

    @Volatile
    private var pendingValid = false

    @Volatile
    private var wantEnd = false

    @Volatile
    private var inFlight = false

    @Volatile
    private var inFlightAtMs = 0L

    @Volatile
    private var lastInputMs = 0L

    /** 派发代号：只认当前代的回调，否则上一段的过期回调会把新一段误判成已结束 */
    @Volatile
    private var strokeGen = 0



    private fun settle(gen: Int) {
        synchronized(gestureLock) {
            if (gen != strokeGen) return
            if (!inFlight) return
            inFlight = false
            pumpLocked()
        }
    }

    /** 回调丢失的兜底：在途手势超过 STROKE_TIMEOUT_MS 就当作它结束了 */
    private fun clearStaleLocked() {
        if (inFlight && System.currentTimeMillis() - inFlightAtMs > STROKE_TIMEOUT_MS) {
            inFlight = false
            ServerCore.log("手势回调超时（${howStaleMs()}ms），强制推进下一段")
        }
    }

    private fun howStaleMs(): Long = System.currentTimeMillis() - inFlightAtMs

    private fun pumpLocked() {
        clearStaleLocked()
        if (inFlight) return

        if (wantEnd) {
            val tx = if (pendingValid) pendingX else chainX
            val ty = if (pendingValid) pendingY else chainY
            pendingValid = false
            wantEnd = false
            held = false
            dispatchSegmentLocked(tx, ty, SEGMENT_MS, continues = false)
            activeStroke = null
            return
        }
        if (!held) {
            // 收尾段已派发完（或被取消）→ 链彻底结束
            activeStroke = null
            return
        }

        if (pendingValid) {
            pendingValid = false
            dispatchSegmentLocked(pendingX, pendingY, SEGMENT_MS, continues = true)
            return
        }

        // 按住不动：续约，否则段走完手指就被系统抬起来
        if (System.currentTimeMillis() - lastInputMs > TOUCH_HOLD_MS) {
            ServerCore.log("按压超过 ${TOUCH_HOLD_MS / 1000}s 无输入事件，强制收尾")
            held = false
            wantEnd = false
            pendingValid = false
            dispatchSegmentLocked(chainX, chainY, SEGMENT_MS, continues = false)
            activeStroke = null
            return
        }
        dispatchSegmentLocked(chainX, chainY, HOLD_RENEW_MS, continues = true)
    }

    /** 调用方必须持有 gestureLock */
    private fun dispatchSegmentLocked(toX: Float, toY: Float, duration: Long, continues: Boolean) {
        val x = clampX(toX)
        val y = clampY(toY)
        val prev = activeStroke
        val stroke = if (prev == null) {
            // 首段：单点路径（框架按 tap 处理，指针原地按住）
            GestureDescription.StrokeDescription(
                Path().apply { moveTo(x, y) }, 0, duration, continues
            )
        } else {
            // 续段：起点必须严格等于上一段终点，否则系统不认这条链
            val fx = clampX(chainX)
            val fy = clampY(chainY)
            GestureDescription.StrokeDescription(
                Path().apply {
                    moveTo(fx, fy)
                    lineTo(x, y)
                }, 0, duration, continues
            )
        }
        activeStroke = stroke
        chainX = x
        chainY = y
        noteSegment(x, y, duration, continues)

        val svc = service()
        if (svc == null) {
            inFlight = false
            held = false
            activeStroke = null
            return
        }
        val gen = ++strokeGen
        inFlight = true
        inFlightAtMs = System.currentTimeMillis()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = settle(gen)

            override fun onCancelled(gestureDescription: GestureDescription) = settle(gen)
        }
        val ok = try {
            svc.dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), callback, uiHandler
            )
        } catch (e: Exception) {
            ServerCore.log("dispatchGesture 异常：${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) {
            inFlight = false
            ServerCore.log("dispatchGesture 被拒绝，本段丢失")
        }
    }

    private var segCount = 0
    private var lastSegLogMs = 0L

    /** 每条链的前 5 段必打，之后 1 条/秒，用于确认航点真的在逐段注入 */
    private fun noteSegment(x: Float, y: Float, duration: Long, continues: Boolean) {
        segCount++
        val now = System.currentTimeMillis()
        if (segCount > 5 && now - lastSegLogMs < 1000) return
        lastSegLogMs = now
        ServerCore.log(
            "段#$segCount → (${x.toInt()},${y.toInt()}) 时长=${duration}ms" +
                " 续=${continues} 在途=$inFlight held=$held"
        )
    }

    private fun beginTouch(x0: Float, y0: Float) {
        synchronized(gestureLock) {
            cancelTouchLocked()
            val x = clampX(x0)
            val y = clampY(y0)
            held = true
            wantEnd = false
            pendingValid = false
            segCount = 0
            activeStroke = null
            chainX = x
            chainY = y
            lastInputMs = System.currentTimeMillis()
            inFlight = false
            dispatchSegmentLocked(x, y, SEGMENT_MS, continues = true)
        }
    }

    private fun endTouch(x0: Float, y0: Float) {
        synchronized(gestureLock) {
            if (!held) {
                activeStroke = null
                return
            }
            lastInputMs = System.currentTimeMillis()
            pendingX = clampX(x0)
            pendingY = clampY(y0)
            pendingValid = true
            wantEnd = true
            pumpLocked()
        }
    }

    /** 收掉未收尾的 continued stroke。没有活动 stroke 就什么都不做 */
    private fun cancelTouch() {
        synchronized(gestureLock) {
            cancelTouchLocked()
        }
    }

    private fun cancelTouchLocked() {
        if (!held && activeStroke == null) return
        held = false
        wantEnd = false
        pendingValid = false
        clearStaleLocked()
        if (inFlight) return // 在途的这段结束后 pump 会因 held=false 自然停下
        if (activeStroke != null) {
            dispatchSegmentLocked(chainX, chainY, SEGMENT_MS, continues = false)
        }
        activeStroke = null
    }

    /**
     * 滚轮/右键这类「与当前触摸互斥」的独立手势。dispatchGesture 会取消进行中的手势，
     * 所以触摸还没收尾时直接丢弃，避免把在途的收尾段吃掉导致手指被永久按住。
     */
    private fun foreignDispatchAllowed(): Boolean = synchronized(gestureLock) {
        clearStaleLocked()
        if (held || activeStroke != null || inFlight) {
            ServerCore.log("忽略滚轮/右键手势：上屏 stroke 尚未收尾")
            return false
        }
        true
    }

    private fun dispatchSimpleTap(x0: Float, y0: Float, durationMs: Long) {
        val x = clampX(x0)
        val y = clampY(y0)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, max(durationMs, 1)))
            .build()
        service()?.dispatchGesture(gesture, null, null)
    }

    private fun service(): InputService? = InputService.instance

    /** 会话结束：收掉未完成的 stroke 并隐藏鼠标光标 */
    fun reset() {
        cancelTouch()
        CursorOverlay.hide()
        pendingRelease?.let { uiHandler.removeCallbacks(it); pendingRelease = null }
        leftButtonDown = false
        armKnown = false
        clearDragSamples()
        pendingScrollDX = 0f
        pendingScrollDY = 0f
        pendingScrollMs = SCROLL_SEGMENT_MS
        scrollInFlight = false
        wheelSpeed = 1f
        lastWheelMs = 0L
        lastX = -1f
        lastY = -1f
    }

    // ---------------- 自测注入 ----------------
    //
    // 只走鼠标路径（与真实客户端完全相同的 handleAbsMouse/handleMouseButton），
    // 触摸模式已移除，不再有 tap/swipe/longpress 这类触摸自测。

    private val debugHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun runDebug(spec: String) {
        try {
            val parts = spec.split(":")
            val head = parts[0]
            val nums = parts.getOrElse(1) { "" }.split(",").map { it.trim().toFloat() }
            when (head) {
                "mclick" -> {
                    ServerCore.log("自测 鼠标单击(${nums[0]},${nums[1]})")
                    moveMouseTo(nums[0], nums[1])
                    mouseButton(true)
                    debugHandler.postDelayed({ mouseButton(false) }, 70)
                }
                "mlong" -> {
                    val hold = nums.getOrElse(2) { 900f }.toLong()
                    ServerCore.log("自测 鼠标长按(${nums[0]},${nums[1]}) ${hold}ms → 应上屏长按")
                    moveMouseTo(nums[0], nums[1])
                    mouseButton(true)
                    debugHandler.postDelayed({ mouseButton(false) }, hold)
                }
                "mdrag" -> {
                    val dur = nums.getOrElse(4) { 500f }.toLong()
                    ServerCore.log("自测 鼠标按住拖动(${nums[0]},${nums[1]})→(${nums[2]},${nums[3]}) ${dur}ms → 应折成 delta 滚动")
                    moveMouseTo(nums[0], nums[1])
                    mouseButton(true)
                    val steps = 10
                    for (i in 1 until steps) {
                        val f = i / steps.toFloat()
                        debugHandler.postDelayed({
                            moveMouseTo(
                                nums[0] + (nums[2] - nums[0]) * f,
                                nums[1] + (nums[3] - nums[1]) * f
                            )
                        }, dur * i / steps)
                    }
                    debugHandler.postDelayed({
                        moveMouseTo(nums[2], nums[3])
                        mouseButton(false)
                    }, dur)
                }
                else -> ServerCore.log("自测命令无法识别：$spec")
            }
        } catch (e: Exception) {
            ServerCore.log("自测失败：$spec → $e")
        }
    }

    /** 组一个 NV_ABS_MOUSE_MOVE 包并走完整 handle 路径（含坐标换算） */
    private fun moveMouseTo(x: Float, y: Float) {
        val scale = 10000
        val mx = (x / screenWidth() * scale).toInt().coerceIn(0, scale)
        val my = (y / screenHeight() * scale).toInt().coerceIn(0, scale)
        val body = ByteArray(10)
        putBe16(body, 0, mx); putBe16(body, 2, my)
        putBe16(body, 4, 0); putBe16(body, 6, scale); putBe16(body, 8, scale)
        handleAbsMouse(body, 0, body.size)
    }

    private fun mouseButton(down: Boolean) {
        val body = ByteArray(1)
        body[0] = 0x01
        handleMouseButton(if (down) 0x00000008 else 0x00000009, body, 0, body.size)
    }

    private fun putBe16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    // ---------------- 屏幕几何 ----------------
    //
    // 客户端给的归一化坐标是**视频帧**内的比例。虚拟屏按宽高比等比缩放镜像本机屏时
    // 多出来的就是"画面外面的黑边"，必须先减掉黑边再除以缩放比才能落到真实屏幕坐标。
    // 状态栏/导航栏本身就在镜像画面里，**不做额外扣除**——否则顶部会整体偏移一个状态栏高度。

    private data class Geometry(
        val screenW: Float, val screenH: Float,
        val videoW: Float, val videoH: Float,
        val insetLeft: Float, val insetTop: Float,
        val insetRight: Float, val insetBottom: Float
    )

    @Volatile
    private var geoCache: Geometry? = null

    @Volatile
    private var geoAtMs = 0L

    private fun geometry(): Geometry {
        val now = android.os.SystemClock.uptimeMillis()
        geoCache?.let { if (now - geoAtMs < 1000) return it }

        val ctx = ServerCore.appContext
        var screenW = 0f
        var screenH = 0f
        var insetLeft = 0f
        var insetTop = 0f
        var insetRight = 0f
        var insetBottom = 0f
        try {
            // maximumWindowMetrics 的 bounds 不扣除 inset，正好是整块物理屏
            val wm = ctx.getSystemService(android.view.WindowManager::class.java)
            val bounds = wm.maximumWindowMetrics.bounds
            screenW = bounds.width().toFloat()
            screenH = bounds.height().toFloat()
            val insets = wm.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            insetLeft = insets.left.toFloat()
            insetTop = insets.top.toFloat()
            insetRight = insets.right.toFloat()
            insetBottom = insets.bottom.toFloat()
        } catch (e: Exception) {
            val dm = ctx.resources.displayMetrics
            screenW = dm.widthPixels.toFloat()
            screenH = dm.heightPixels.toFloat()
        }
        if (screenW <= 0f || screenH <= 0f) {
            val dm = ctx.resources.displayMetrics
            screenW = dm.widthPixels.toFloat()
            screenH = dm.heightPixels.toFloat()
        }

        val video = ServerCore.session?.config
        val videoW = (video?.width ?: 0).let { if (it in 128..4096) it.toFloat() else screenW }
        val videoH = (video?.height ?: 0).let { if (it in 128..4096) it.toFloat() else screenH }

        val g = Geometry(screenW, screenH, videoW, videoH, insetLeft, insetTop, insetRight, insetBottom)
        geoCache = g
        geoAtMs = now
        return g
    }

    private fun screenWidth(): Float = geometry().screenW

    private fun screenHeight(): Float = geometry().screenH

    /**
     * 视频帧内的归一化坐标 (0..1) → 本机屏幕物理像素。
     *
     * 虚拟屏 1920x1200 镜像 1440x3168 的手机屏时按宽高比等比缩放并居中，
     * 两侧（或上下）多出来的就是"画面外面的黑边"。客户端鼠标/触摸的 x、y 是相对
     * **视频帧**（含黑边）的比例，必须先减掉黑边再除以缩放比才能落到真实屏幕坐标。
     * 系统栏本身就在画面里，所以这里不再做额外扣除——否则顶部会整体偏移一个状态栏高度。
     */
    private fun videoToScreen(fx: Float, fy: Float): Pair<Float, Float> {
        val g = geometry()
        val scale = minOf(g.videoW / g.screenW, g.videoH / g.screenH)
        if (scale <= 0f) return fx * g.screenW to fy * g.screenH

        val barX = (g.videoW - g.screenW * scale) / 2f
        val barY = (g.videoH - g.screenH * scale) / 2f
        val sx = ((fx * g.videoW - barX) / scale).coerceIn(0f, g.screenW)
        val sy = ((fy * g.videoH - barY) / scale).coerceIn(0f, g.screenH)
        return sx to sy
    }

    // ---------------- 字节序 ----------------

    private fun be32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)

    private fun le32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun be16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun be16Signed(buf: ByteArray, off: Int): Int {
        val v = be16(buf, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private const val KEY_DOWN_EVENT_MAGIC = 0x00000003
}
