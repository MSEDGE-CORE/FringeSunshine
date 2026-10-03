package com.wxz.sunshineserverandroid.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Surface
import com.wxz.sunshineserverandroid.ServerCore
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 视频通道：常驻 MediaProjection 虚拟屏 -> MediaCodec H.264 -> Moonlight RTP 分包 -> UDP 47998。
 *
 * 虚拟屏由 [ProjectionDisplay] 服务级持有（Android 14+ 每实例仅允许一次
 * createVirtualDisplay），本类每会话只创建编码器并 attach/detach 自己的输入表面。
 *
 * 包格式（与 Sunshine videoBroadcastThread 一致，FEC percentage=0 简化路径）：
 * [RTP 12B: 0x90, ptype 96, seq BE, ts BE(90kHz), ssrc 0]
 * [4B 保留位]
 * [NV_VIDEO_PACKET 16B: streamPacketIndex LE, frameIndex LE, flags, extraFlags, multiFecFlags=0x10,
 *  multiFecBlocks, fecInfo LE (idx<<12 | shards<<22 | percentage<<4)]
 * [8B 短帧头: 0x01, latency u16LE, frameType, lastPayloadLen u16LE, 保留 2B]（仅首包）
 * [负载数据]
 */
class VideoStreamer(
    private val session: StreamSession
) : Thread("video-streamer") {

    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private val stopped = AtomicBoolean(false)

    @Volatile
    private var requestSync = false

    /** 编码器输出的 SPS/PPS（csd-0），每个 IDR 前手动拼上 */
    private var codecConfig: ByteArray? = null
    private var noPeerLogged = false
    private var firstFrameLogged = false

    // RTP 序列（低 16 位进 RTP 头，完整值左移 8 位进 streamPacketIndex）
    private var lowSeq = 0

    private val metrics: DisplayMetrics = ServerCore.appContext.resources.displayMetrics

    /** 输入侧限帧由 KEY_MAX_FPS_TO_ENCODER 完成，绝不能在编码后丢 P 帧：
     *  编码后的帧互相引用，丢掉一帧会让客户端解码器缺失参考帧 → 画质崩坏。 */
    private var statsEncoded = 0
    private var statsBytes = 0L
    private var statsWindowStartMs = 0L

    /** 上一帧进入发送的时刻，用来算帧间隔抖动（流畅度的直接指标） */
    private var lastFrameAtMs = 0L

    /** 本窗口内的帧间隔样本（ms），定长环形，够 5s@60fps 用 */
    private val ivBuf = IntArray(384)
    private var ivN = 0
    private var ivIdx = 0
    private var ivMax = 0
    private var ivOver33 = 0

    /** 画面静止（无输入帧）造成的超长间隔单独计数，不进抖动统计，否则把静止误判成卡顿 */
    private var ivIdle = 0
    private var ivIdleMax = 0

    /** 编码线程在 sendQueue 上被回压（队列满）的累计 ms / 次数 */
    private var queueBlockMs = 0L
    private var queueBlockN = 0

    /** 本窗口累计 socket.send 阻塞耗时（ns）与异常次数 */
    @Volatile
    private var sendNanos = 0L
    @Volatile
    private var sendErrors = 0
    @Volatile
    private var sendCount = 0
    @Volatile
    private var sendMaxNs = 0L
    @Volatile
    private var sendSlow = 0
    @Volatile
    private var sendSlowWhilePing = 0
    @Volatile
    private var slowLogCount = 0

    private var packetBuf: ByteArray? = null

    /** 编码→发送 的有界队列：满 6 帧（≈100ms @60fps）才回压编码器 */
    private val sendQueue = java.util.concurrent.ArrayBlockingQueue<OutFrame>(6)

    private var senderThread: Thread? = null

    /** 对端 ping 之前编码出来的帧先存住，否则首帧直接被丢掉，客户端要等下一次画面变化才有画面 */
    private var pendingPayload: ByteArray? = null
    private var pendingIdr = false
    private var pendingPts = 0L

    private var peerReadyMs = 0L
    private var lastFrameSentMs = 0L
    private var lastPokeMs = 0L
    private var lastStallLogMs = 0L

    override fun run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {
        }
        senderThread = Thread(
            {
                try {
                    senderLoop()
                } catch (e: Exception) {
                    if (!stopped.get()) {
                        ServerCore.log("视频发送线程异常: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            },
            "video-sender"
        ).also {
            it.priority = Thread.MAX_PRIORITY - 1
            it.start()
        }
        try {
            streamLoop()
        } catch (e: Exception) {
            if (!stopped.get()) {
                val reason = "${e.javaClass.simpleName}: ${e.message}"
                ServerCore.log("视频线程异常退出: $reason")
                session.fail(reason)
            }
        }
        stopped.set(true)
        try {
            senderThread?.interrupt()
        } catch (_: Exception) {
        }
        release()
    }

    private fun streamLoop() {
        val width = configWidth()
        val height = configHeight()
        val fps = session.config.fps.coerceIn(10, 60)
        statsWindowStartMs = System.currentTimeMillis()

        val bitrateKbps = session.config.bitrateKbps * 1000
        // 配置阶梯：优化参数+CBR → 优化参数+VBR → 纯基础参数。
        // Sunshine 用 CBR（VBR 在高动态画面会冲到 11Mbps+，超 WiFi 承载即丢包卡顿）；
        // 优化参数若被某个编码器拒绝，逐级退化而不是整个串流起不来。
        val attempts = listOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR to true,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR to true,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR to false,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR to false
        )
        var codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var configured = false
        for ((mode, enhanced) in attempts) {
            try {
                codec.configure(
                    buildFormat(width, height, fps, bitrateKbps, mode, enhanced),
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                configured = true
                if (!enhanced) {
                    ServerCore.log("编码器不接受优化参数，已回退基础配置")
                } else if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) {
                    ServerCore.log("编码器不支持 CBR，回退 VBR")
                }
                break
            } catch (e: Exception) {
                ServerCore.log(
                    "编码器 configure 失败（enhanced=$enhanced mode=$mode）：" +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                try {
                    codec.release()
                } catch (_: Exception) {
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
        }
        if (!configured) {
            try {
                codec.release()
            } catch (_: Exception) {
            }
            throw IllegalStateException("编码器配置失败（CBR/VBR + 优化/基础参数均被拒绝）")
        }
        encoder = codec
        val inputSurface = codec.createInputSurface()
        surface = inputSurface
        codec.start()

        // 挂到服务级常驻虚拟屏（首次创建，后续会话仅 setSurface/resize）
        val projectionDisplay = ServerCore.projectionDisplay
        if (projectionDisplay == null ||
            !projectionDisplay.attach(inputSurface, width, height, metrics.densityDpi)
        ) {
            throw IllegalStateException("虚拟屏挂载失败（投屏授权可能已失效）")
        }

        ServerCore.log("视频编码器就绪：${width}x${height}@$fps")

        val bufferInfo = MediaCodec.BufferInfo()
        while (!stopped.get()) {
            if (requestSync) {
                requestSync = false
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                codec.setParameters(params)
            }

            flushPendingFrame()
            pokeSourceIfNeeded()

            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val csd = codec.outputFormat.getByteBuffer("csd-0")
                if (csd != null) {
                    csd.rewind()
                    codecConfig = ByteArray(csd.remaining()).also { csd.get(it) }
                }
                continue
            }
            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false)
                continue
            }

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codecConfig = ByteArray(buffer.remaining()).also { buffer.get(it) }
                codec.releaseOutputBuffer(index, false)
                continue
            }

            val size = bufferInfo.size
            if (size > 0) {
                val isIdr = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val frame = ByteArray(size).also {
                    buffer.position(bufferInfo.offset.coerceAtLeast(0))
                    buffer.limit((bufferInfo.offset + size).coerceAtMost(buffer.capacity()))
                    buffer.get(it)
                }
                // IDR 前拼 SPS/PPS，保证客户端可解码
                val payload = if (isIdr && codecConfig != null) codecConfig!! + frame else frame
                sendFrame(payload, isIdr, bufferInfo.presentationTimeUs)
            }
            codec.releaseOutputBuffer(index, false)
            emitStats()
        }
    }

    /** 对端 ping 到达后，把暂存的首帧补发出去；不是 IDR 就再要一个关键帧 */
    private fun flushPendingFrame() {
        val payload = pendingPayload ?: return
        if (session.videoPeer == null) return
        val idr = pendingIdr
        val pts = pendingPts
        pendingPayload = null
        sendFrame(payload, idr, pts)
        if (!idr) requestSync = true
    }

    /**
     * 源屏静止或已息屏时 SurfaceFlinger 不出帧，编码器会一直拿不到输入。
     * 超过 1.5s 没有新帧就主动 invalidate 悬浮窗逼一次合成；同时打日志定位息屏。
     */
    private fun pokeSourceIfNeeded() {
        if (session.videoPeer == null) return
        val now = System.currentTimeMillis()
        if (peerReadyMs == 0L) {
            peerReadyMs = now
            ServerCore.log("视频对端就绪，等待源屏出帧（屏幕熄灭时虚拟屏不会出帧）")
            return
        }
        val last = lastFrameSentMs
        val quietMs = now - if (last != 0L) last else peerReadyMs
        if (quietMs < POKE_AFTER_QUIET_MS) return
        // 过程中息屏不点亮：只在逼帧挽救合成，屏幕亮灭交给 acquireKeepAwake 的常亮标记
        if (quietMs >= POKE_AFTER_QUIET_MS && now - lastPokeMs >= 300L) {
            lastPokeMs = now
            com.wxz.sunshineserverandroid.input.CursorOverlay.poke()
        }
        if (quietMs >= 5_000L && now - lastStallLogMs >= 10_000L) {
            lastStallLogMs = now
            val interactive = try {
                val pm = ServerCore.appContext
                    ?.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isInteractive == true
            } catch (_: Exception) {
                true
            }
            ServerCore.log(
                "视频已 ${"%.0f".format(quietMs / 1000.0)}s 无输出" +
                    "（屏幕${if (interactive) "亮着=画面完全静止" else "已息屏=虚拟屏停止合成"}）"
            )
        }
    }

    /** 每 5 秒打印一次发送统计（编码帧率即实际进入 RTP 的帧率） */
    private fun emitStats() {
        if (statsWindowStartMs == 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - statsWindowStartMs
        if (elapsed < 5_000L) return
        val encoded = statsEncoded
        val bytes = statsBytes
        val sendMs = sendNanos / 1_000_000.0
        val errs = sendErrors
        statsEncoded = 0
        statsBytes = 0L
        sendNanos = 0L
        sendErrors = 0
        val sCount = sendCount
        val sMaxMs = sendMaxNs / 1_000_000.0
        val sSlow = sendSlow
        val sSlowPing = sendSlowWhilePing
        sendCount = 0
        sendMaxNs = 0L
        sendSlow = 0
        sendSlowWhilePing = 0
        slowLogCount = 0
        statsWindowStartMs = now
        lastFrameAtMs = 0L
        val intervals = if (ivN > 1) IntArray(ivN) { ivBuf[it] } else IntArray(0)
        val idle = ivIdle
        val idleMax = ivIdleMax
        ivN = 0
        ivIdx = 0
        ivMax = 0
        ivOver33 = 0
        ivIdle = 0
        ivIdleMax = 0
        val qMs = queueBlockMs
        val qN = queueBlockN
        queueBlockMs = 0L
        queueBlockN = 0
        val sec = elapsed / 1000.0
        val ivInfo = if (intervals.size > 1) {
            intervals.sort()
            val p50 = intervals[intervals.size / 2]
            val p95 = intervals[(intervals.size * 95 / 100).coerceAtMost(intervals.size - 1)]
            val max = intervals[intervals.size - 1]
            val over = intervals.count { it > 33 }
            val idleInfo = if (idle > 0) "｜静止间隔 $idle 次 最长=${idleMax}ms" else ""
            "活跃帧间隔 p50=${p50}ms p95=${p95}ms max=${max}ms（>33ms 的 $over 次）$idleInfo"
        } else "帧间隔 n/a"
        val qInfo = if (qN > 0) " 队列回压=${qMs}ms/$qN 次" else ""
        ServerCore.log(
            "视频统计：${"%.1f".format(encoded / sec)} fps，${"%.1f".format(bytes * 8 / 1000.0 / sec)} kbps" +
                "｜$ivInfo｜发送=${"%.1f".format(sendMs)}ms/${sCount}包 最大=${"%.0f".format(sMaxMs)}ms" +
                " 慢包(>5ms)=$sSlow（ping 在 receive=$sSlowPing）异常=$errs$qInfo"
        )
    }

    /** 记录一次帧间隔（流畅度直接指标：抖动=掉帧/卡顿） */
    private fun noteFrameInterval() {
        val now = System.currentTimeMillis()
        val last = lastFrameAtMs
        lastFrameAtMs = now
        if (last == 0L) return
        val iv = (now - last).coerceIn(0, 5_000).toInt()
        // >300ms = 上一帧至今画面根本没变化（SurfaceFlinger 不出帧），不是编码/发送卡顿
        if (iv > IDLE_GAP_MS) {
            ivIdle++
            if (iv > ivIdleMax) ivIdleMax = iv
            return
        }
        if (iv > ivMax) ivMax = iv
        if (iv > 33) ivOver33++
        ivBuf[ivIdx] = iv
        ivIdx = (ivIdx + 1) % ivBuf.size
        if (ivN < ivBuf.size) ivN++
    }

    private fun buildFormat(
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        bitrateMode: Int,
        enhanced: Boolean
    ): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IDR_INTERVAL_S)
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            // 虚拟屏按屏幕刷新率（本机 120Hz）出帧，限到协商帧率；在编码前生效
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            if (enhanced) {
                // 硬编按这个速率准备线程/资源：输入突发时不排队，输出更均匀
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps * 2)
                // 禁 B 帧重排：客户端少 1~2 帧重排延迟与解码抖动
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                // 低延迟模式（API30+，minSdk 33）：更浅的输入/输出缓冲
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

    private fun configWidth(): Int {
        val requested = session.config.width
        return if (requested in 128..4096) requested else metrics.widthPixels
    }

    private fun configHeight(): Int {
        val requested = session.config.height
        return if (requested in 128..4096) requested else metrics.heightPixels
    }

    /** 一帧编码数据切包发送 */
    /** 发送线程队列元素：编码完的一整帧负载 */
    private class OutFrame(val payload: ByteArray, val ptsUs: Long, val idr: Boolean)

    /**
     * 编码线程只做**入队**，绝不碰 socket。
     *
     * 实测（1584x1584@60，~8Mbps）：`socket.send()` 单次可阻塞 100~300ms，5s 窗口里累计
     * 2.5~3.3s 花在 send 上 —— 它一旦卡住，`dequeueOutputBuffer` 停摆 → 编码器输出堆积 →
     * 放行后一串帧连着涌出（帧间隔实测 p50=3ms / max=1.3s），客户端看到的就是「憋一下再喷」的卡顿。
     * 现在慢的代价由队列吸收（6 帧≈100ms 上限），编码节奏不再被网络牵着走。
     */
    private fun sendFrame(frameData: ByteArray, isIdr: Boolean, ptsUs: Long) {
        if (session.videoPeer == null) {
            pendingPayload = frameData
            pendingIdr = isIdr
            pendingPts = ptsUs
            if (!noPeerLogged) {
                noPeerLogged = true
                ServerCore.log("视频编码器已有输出，但尚未收到客户端视频 UDP ping（暂存首帧等待对端）")
            }
            return
        }
        statsEncoded++
        statsBytes += frameData.size
        noteFrameInterval()
        lastFrameSentMs = System.currentTimeMillis()
        val q0 = System.nanoTime()
        try {
            sendQueue.put(OutFrame(frameData, ptsUs, isIdr))
        } catch (_: InterruptedException) {
        }
        val qdt = (System.nanoTime() - q0) / 1_000_000L
        if (qdt > 5L) {
            queueBlockMs += qdt
            queueBlockN++
        }
    }

    /** 发送线程：全进程唯一调用视频 socket.send 的地方 */
    private fun senderLoop() {
        while (!stopped.get()) {
            val item = try {
                sendQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            writeFrame(item)
        }
        // 收尾：把还在队列里的帧冲掉（会话已停，发多少算多少）
        while (true) {
            val item = sendQueue.poll() ?: break
            writeFrame(item)
        }
    }

    /** 把一帧切成 RTP 分片发出去（只在发送线程跑） */
    private fun writeFrame(item: OutFrame) {
        val peer: InetSocketAddress = session.videoPeer ?: return
        val socket = session.videoSocket ?: return
        val frameData = item.payload
        val ptsUs = item.ptsUs

        val packetSize = session.config.packetSize.coerceIn(256, 1024 * 64)
        val blockSize = packetSize + MAX_RTP_HEADER_SIZE
        val payloadPerPacket = blockSize - HEADER_SIZE
        val frameHeaderSize = 8

        // 总负载 = 8B 短帧头 + 帧数据，按 payloadPerPacket 切片（最后一片自然短）
        val totalPayload = frameHeaderSize + frameData.size
        val shardCount = (totalPayload + payloadPerPacket - 1) / payloadPerPacket

        val timestamp = (ptsUs / 1000L * 90L).toInt() // us -> 90kHz 时钟
        val frameIndex = frameCounter++
        val frameType: Int = if (item.idr) 2 else 1
        val lastPayloadLen = totalPayload % payloadPerPacket
        val lastLen = if (lastPayloadLen == 0) payloadPerPacket else lastPayloadLen

        var offset = 0
        val buf = packetBuf?.takeIf { it.size == blockSize }
            ?: ByteArray(blockSize).also { packetBuf = it }
        for (i in 0 until shardCount) {
            val isLast = i == shardCount - 1
            val chunkLen = if (isLast) lastLen else payloadPerPacket
            java.util.Arrays.fill(buf, 0, blockSize, 0.toByte())
            val packet = buf
            fillPacketHeader(packet, frameIndex, i, shardCount, timestamp)

            var pos = HEADER_SIZE
            if (i == 0) {
                // 8 字节短帧头
                packet[pos] = 0x01
                packet[pos + 1] = 0; packet[pos + 2] = 0 // latency
                packet[pos + 3] = frameType.toByte()
                packet[pos + 4] = (lastLen and 0xFF).toByte()
                packet[pos + 5] = ((lastLen shr 8) and 0xFF).toByte()
                pos += frameHeaderSize
            }
            val copyLen = minOf(chunkLen - (if (i == 0) frameHeaderSize else 0), frameData.size - offset)
            if (copyLen > 0) {
                System.arraycopy(frameData, offset, packet, pos, copyLen)
                offset += copyLen
            }
            // 剩余部分保持 0 填充（与 Sunshine 对齐块一致）

            val t0 = System.nanoTime()
            try {
                socket.send(DatagramPacket(packet, blockSize, peer.address, peer.port))
            } catch (_: Exception) {
                sendErrors++
            }
            val dt = System.nanoTime() - t0
            sendNanos += dt
            sendCount++
            if (dt > sendMaxNs) sendMaxNs = dt
            if (dt > 5_000_000L) {
                sendSlow++
                val since = session.pingReceiveSinceMs
                if (since != 0L) sendSlowWhilePing++
                if (dt > 20_000_000L && slowLogCount < 5) {
                    slowLogCount++
                    ServerCore.log(
                        "send 阻塞 ${dt / 1_000_000}ms｜此刻 ping 线程" +
                            (if (since != 0L) "已在 receive 里 ${System.currentTimeMillis() - since}ms（疑似锁争用）"
                            else "不在 receive")
                    )
                }
            }
        }
        lowSeq = (lowSeq + shardCount) and 0xFFFF
        if (!firstFrameLogged) {
            firstFrameLogged = true
            ServerCore.log("视频首帧已发送：${frameData.size}B，${shardCount} 个 UDP 分片，目标 $peer")
        }
    }

    private fun fillPacketHeader(
        packet: ByteArray,
        frameIndex: Int,
        shardIndex: Int,
        shardCount: Int,
        timestamp: Int
    ) {
        val seq = (lowSeq + shardIndex) and 0xFFFF
        // RTP 头（大端）
        packet[0] = 0x90.toByte() // 0x80 | FLAG_EXTENSION
        packet[1] = 96.toByte()   // 视频 payload type
        packet[2] = ((seq shr 8) and 0xFF).toByte()
        packet[3] = (seq and 0xFF).toByte()
        putIntBE(packet, 4, timestamp)
        putIntBE(packet, 8, 0) // ssrc
        // 保留 4 字节（12..15）为 0
        // NV_VIDEO_PACKET（小端）
        putIntLE(packet, 16, (lowSeq + shardIndex) shl 8)   // streamPacketIndex
        putIntLE(packet, 20, frameIndex)                     // frameIndex
        packet[24] = (0x1 or (if (shardIndex == 0) 0x4 else 0) or (if (shardIndex == shardCount - 1) 0x2 else 0)).toByte()
        packet[25] = 0                                       // extraFlags
        packet[26] = 0x10                                    // multiFecFlags
        packet[27] = 0                                       // multiFecBlocks: 单 FEC 块
        putIntLE(packet, 28, (shardIndex shl 12) or (shardCount shl 22) or (0 shl 4))
        // 32..39 之后为短帧头/数据（由调用方填充）
    }

    fun requestIdr() {
        val was = requestSync
        requestSync = true
        if (!was) ServerCore.log("客户端请求 IDR，已置位编码器 sync frame")
    }

    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        interrupt()
    }

    private fun release() {
        // 先从常驻 VD 卸下表面，再释放编码器/表面
        try {
            surface?.let { ServerCore.projectionDisplay?.detach(it) }
        } catch (_: Exception) {
        }
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        try {
            surface?.release()
        } catch (_: Exception) {
        }
    }

    private fun putIntLE(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value and 0xFF).toByte()
        packet[offset + 1] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 16) and 0xFF).toByte()
        packet[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putIntBE(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value shr 24) and 0xFF).toByte()
        packet[offset + 1] = ((value shr 16) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 3] = (value and 0xFF).toByte()
    }

    private var frameCounter = 1

    companion object {
        const val HEADER_SIZE = 32 // 12 RTP + 4 保留 + 16 NV_VIDEO_PACKET
        const val MAX_RTP_HEADER_SIZE = 16

        /** 静止这么久先 invalidate 逼一帧（不动屏幕） */
        const val POKE_AFTER_QUIET_MS = 1_500L

        /** 帧间隔超过该值即认为是「画面静止无输入」，不计入抖动 */
        const val IDLE_GAP_MS = 300

        /** 关键帧间隔（秒）：丢包后客户端要么等它、要么主动请求 IDR，调小=卡顿恢复更快、码率峰值略升 */
        const val IDR_INTERVAL_S = 3
    }
}
