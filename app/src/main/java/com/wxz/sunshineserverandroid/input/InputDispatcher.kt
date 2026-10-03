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

    // ---------------- 鼠标按键：按下即上屏、实时跟手 ----------------
    //
    // DOWN 一到就在**按下点**起一条 continued stroke，之后每个 move 把最新位置作为下一段
    // 目标续上去（continueStroke + continues=true），全程**不抬手、不重新按下**：
    // 按下点从头到尾只有最初那一个，绝不会 up/down 重置。
    // UP 时补最后一段（continues=false）抬手，落点就是抬手位置。
    //
    // **不做任何「单击 / 长按 / 滑动阈值」分类**，原始输入原样上屏：
    //   按住不动 → 系统自然读成长按；小位移 → 系统自然读成点击；大位移 → 自然读成拖动，
    //   全部由目标 App 自己判断，我们不替它改写输入。

    /** 上屏右键点击的「按下→抬起」模拟时长 */
    private const val RIGHT_CLICK_DURATION_MS = 120L

    /** 滚轮攒够这个位移才派发（太小会被 App 读成点一下） */
    private const val MIN_SCROLL_SEGMENT_PX = 60f

    /** 按住期间保留的采样点数（够算 0.5s 以上的速率即可） */
    private const val MAX_DRAG_SAMPLES = 64

    /** 一次性滚动路径最多画多少个点（识别到的位置太多时均匀抽样） */
    private const val MAX_SCROLL_STEPS = 64

    /** 滚动默认段时长（滚轮用）；按住拖动改用速率换算的时长 */
    private const val SCROLL_SEGMENT_MS = 150L

    /** 兜底一次性手势的按住时长夹取范围 */
    private const val MIN_PRESS_HOLD_MS = 40L
    private const val MAX_PRESS_HOLD_MS = 4_000L

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

        // 按住期间：记采样（链断时的兜底路径用），并把**最新位置**实时喂给在途链。
        if (leftButtonDown) {
            recordDragSample(screenX, screenY)
            synchronized(gestureLock) {
                lastInputMs = android.os.SystemClock.uptimeMillis()
                if (held) {
                    // 最新优先：还没派发出去的旧目标直接被覆盖，绝不排队积压
                    if (hypot(screenX - chainX, screenY - chainY) >= 1f) {
                        pendingX = clampX(screenX)
                        pendingY = clampY(screenY)
                        pendingValid = true
                    }
                    pumpLocked()
                }
            }
        }
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
                // 按下点立刻上屏并保持 down，之后的 move 全都续在这一条链上
                beginTouch(lastX, lastY)
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

    /**
     * 左键抬起：**不做任何识别分类**，原样上屏 —— 走到抬手位置然后抬起。
     * 链还活着就续最后一段收尾（continues=false）；链从没建立起来才走一次性兜底。
     */
    private fun finishMouseButton() {
        val n = dragN
        val alive = synchronized(gestureLock) { held }
        if (alive) {
            endTouch(lastX, lastY)
            clearDragSamples()
            return
        }
        // 按下从未成功（首段被拒绝）→ 一次性兜底：按下点 → 最后位置，时长 = 真实按住时长。
        // 链曾经按下过但中途断了就不兜底了，否则会把整条输入重放一遍变成两次操作。
        if (armKnown && n > 0 && !pointerWasDown) {
            emitFallbackOneShot()
            clearDragSamples()
            return
        }
        clearDragSamples()
        ServerCore.log("UP：链已收尾或无采样（pointerWasDown=$pointerWasDown），不兜底")
    }

    /**
     * **兜底**（正常流程走不到）：整条输入压成一个一次性手势 —— 按下点 → 记录到的每个位置 →
     * 抬手位置，一个手势、一个总时长（= 真实按住时长），按下点同样不会被重置。
     */
    private fun emitFallbackOneShot() {
        val n = dragN
        if (n <= 0) return
        val ax = if (armKnown) armX else lastX
        val ay = if (armKnown) armY else lastY
        if (ax < 0) return
        val startX = clampX(ax)
        val startY = clampY(ay)
        val stride = if (n > MAX_SCROLL_STEPS) (n + MAX_SCROLL_STEPS - 1) / MAX_SCROLL_STEPS else 1
        val heldMs = (android.os.SystemClock.uptimeMillis() - armAtMs)
            .coerceIn(MIN_PRESS_HOLD_MS, MAX_PRESS_HOLD_MS)

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
        val ex = clampX(dragSX[n - 1])
        val ey = clampY(dragSY[n - 1])
        if (abs(ex - lx) >= 1f || abs(ey - ly) >= 1f) path.lineTo(ex, ey)

        ServerCore.log("链断兜底：一次性手势 ${pts}点 按住=${heldMs}ms 着力点=(${ax.toInt()},${ay.toInt()})")
        dispatchScrollPath(path, heldMs)
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
     * 按住不动时的续约窗口（每段跑完就再续一段，指针始终不抬）。
     * UP 走 wantEnd 分支，只等当前在途段跑完就抬手，所以这个值不影响抬手延迟。
     */
    private const val HOLD_RENEW_MS = 32L

    /** 续约最小间隔：防链异常时 1ms 级自旋（会拖垮推流） */
    private const val RENEW_MIN_INTERVAL_MS = 24L

    /** 回调丢失时的兜底，超过就强制推进，否则输入会永久卡死 */
    private const val STROKE_TIMEOUT_MS = 1_000L

    /** 按住无移动时打「还在按着」心跳日志的周期（不再强制收尾，按住本来就是长按） */
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

    /** 系统里是否真的存在一个按下的指针（首段派发成功=true，抬手段成功=false） */
    @Volatile
    private var pointerWasDown = false

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



    private fun settle(gen: Int, completed: Boolean, atMs: Long, durMs: Long) {
        synchronized(gestureLock) {
            val elapsed = System.currentTimeMillis() - atMs
            // 段被取消 / 提前结束 = 链可能已经断了，必须看得见，否则会退化成死循环空转
            if (!completed) noteBadCallback("段被取消", elapsed, durMs)
            else if (elapsed + 5 < durMs) noteBadCallback("段提前结束", elapsed, durMs)
            if (gen != strokeGen) return
            if (!inFlight) return
            inFlight = false
            pumpLocked()
        }
    }

    private var lastBadLogMs = 0L

    private fun noteBadCallback(what: String, elapsed: Long, durMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastBadLogMs < 1000) return
        lastBadLogMs = now
        ServerCore.log(
            "⚠️ $what：实际=${elapsed}ms 请求=${durMs}ms 段数=$segCount" +
                " 位移=(${lastSegDx.toInt()},${lastSegDy.toInt()})"
        )
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

        // 按住不动：持续续约，**绝不中途抬手** —— 「按住不动」本来就是长按的原始输入。
        // （UP 丢失的兜底交给 reset()/cancelTouch，而不是在这里自作主张松手）
        // 续约限频：链被系统取消时回调会 1ms 内回来，这里不限频就会以 ~1000 次/秒
        // 狂发 dispatchGesture（binder 风暴），把 CPU 抢走导致推流卡顿。
        val now = System.currentTimeMillis()
        val since = now - lastRenewMs
        if (since < RENEW_MIN_INTERVAL_MS) {
            schedulePumpLocked(RENEW_MIN_INTERVAL_MS - since)
            return
        }
        lastRenewMs = now
        noteIdleHold()
        dispatchSegmentLocked(chainX, chainY, HOLD_RENEW_MS, continues = true)
    }

    /** 调用方必须持有 gestureLock */
    private fun dispatchSegmentLocked(toX: Float, toY: Float, duration: Long, continues: Boolean) {
        val x = clampX(toX)
        val y = clampY(toY)
        val prev = activeStroke
        // 起点 = 上一段**成功派发**的终点，必须严格对上，否则系统不认这条链
        val fx = clampX(chainX)
        val fy = clampY(chainY)
        val path = if (prev == null) {
            // 首段：单点路径（框架按 tap 处理，指针原地按住）
            Path().apply { moveTo(x, y) }
        } else {
            Path().apply {
                moveTo(fx, fy)
                lineTo(x, y)
            }
        }
        // 关键：续段必须用 continueStroke 挂到上一段的 strokeId 上，
        // 这样系统才**不重新下发 DOWN** —— 换成独立 StrokeDescription 就会 up/down 重置。
        val stroke = if (prev != null && prev.willContinue()) {
            prev.continueStroke(path, 0, duration, continues)
        } else {
            GestureDescription.StrokeDescription(path, 0, duration, continues)
        }

        val svc = service()
        if (svc == null) {
            inFlight = false
            held = false
            activeStroke = null
            return
        }
        noteSegment(x, y, duration, continues)
        val gen = ++strokeGen
        inFlight = true
        inFlightAtMs = System.currentTimeMillis()
        lastSegDurMs = duration
        lastSegDx = x - fx
        lastSegDy = y - fy
        val atMs = System.currentTimeMillis()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) =
                settle(gen, true, atMs, duration)

            override fun onCancelled(gestureDescription: GestureDescription) =
                settle(gen, false, atMs, duration)
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
            // 派发失败：状态一律不推进（chainX/activeStroke 保持在上一段），下次重试同一步
            inFlight = false
            if (prev == null) {
                held = false
                activeStroke = null
                ServerCore.log("按下段被拒绝，链未建立（UP 时走一次性兜底）")
            } else {
                ServerCore.log("续段被拒绝，等下一次输入重试")
            }
            return
        }
        // 成功才推进：chainX/Y = 系统里 pointer 当前的位置
        activeStroke = stroke
        chainX = x
        chainY = y
        if (prev == null) pointerWasDown = true
        if (!continues) {
            // 抬手段派发成功 → 指针已释放；pointerWasDown 仍为 true，表示「这条按下真的发生过」
            activeStroke = null
        }
    }

    private var lastIdleLogMs = 0L
    private var lastRenewMs = 0L

    /** 下一次补跑 pump 的任务（限频用），新的会被替换掉 */
    private var pendingPump: Runnable? = null

    /** 调用方必须持有 gestureLock */
    private fun schedulePumpLocked(delayMs: Long) {
        if (pendingPump != null) return
        val task = Runnable {
            synchronized(gestureLock) {
                pendingPump = null
                pumpLocked()
            }
        }
        pendingPump = task
        uiHandler.postDelayed(task, delayMs)
    }

    /** 长按期间每 10s 打一次「还在按着」，确认链没断 */
    private fun noteIdleHold() {
        val now = System.currentTimeMillis()
        if (now - lastIdleLogMs < TOUCH_HOLD_MS) return
        lastIdleLogMs = now
        ServerCore.log("按住无移动：持续续约保持 down（链段数=$segCount）")
    }

    private var lastSegDurMs = 0L
    private var lastSegDx = 0f
    private var lastSegDy = 0f

    private var segCount = 0
    private var lastSegLogMs = 0L

    /** 每条链的前 5 段必打，之后 1 条/秒，用于确认航点真的在逐段注入 */
    private fun noteSegment(x: Float, y: Float, duration: Long, continues: Boolean) {
        segCount++
        val now = System.currentTimeMillis()
        if (continues && segCount > 5 && now - lastSegLogMs < 1000) return
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
            pointerWasDown = false
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
        leftButtonDown = false
        armKnown = false
        pendingPump?.let { uiHandler.removeCallbacks(it); pendingPump = null }
        clearDragSamples()
        pointerWasDown = false
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
