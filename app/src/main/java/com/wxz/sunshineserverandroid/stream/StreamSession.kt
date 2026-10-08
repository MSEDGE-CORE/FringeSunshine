package com.wxz.sunshineserverandroid.stream

import android.media.projection.MediaProjection
import com.wxz.sunshineserverandroid.ServerCore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import java.security.SecureRandom

/**
 * 一次投屏流会话：RTSP ANNOUNCE 之后创建，
 * 持有视频/音频/控制三个 UDP 通道与编码器。
 */
class StreamSession(
    val config: StreamConfig,
    private val projection: MediaProjection
) {
    @Volatile
    var isRunning = false
        private set

    /** 会话创建时间（僵尸会话判定基准） */
    val startedAtMs = System.currentTimeMillis()

    /** 是否已有客户端控制通道接入：官方 ENet 或 v1 裸协议 */
    val hasConnectedClient: Boolean
        get() = controlConnected || controlPeer != null

    /** 视频通道对端（收到客户端 ping 后确定） */
    @Volatile
    var videoPeer: InetSocketAddress? = null

    /** 音频通道对端 */
    @Volatile
    var audioPeer: InetSocketAddress? = null

    /** 控制通道对端（v1 裸协议客户端） */
    @Volatile
    var controlPeer: InetSocketAddress? = null

    /** 官方客户端 ENet 控制通道是否已建立 */
    @Volatile
    var controlConnected = false

    /**
     * 客户端 ENet 断开的时刻（0 = 从未断开）。
     * 断开后会话保留 [RESUME_GRACE_MS] 供客户端 resume 重新接入，宽限期过后才由看门狗回收。
     */
    @Volatile
    var clientGoneSinceMs = 0L

    /** 控制流加密（SS_ENC_CONTROL_V2），由 RTSP ANNOUNCE 协商结果决定 */
    @Volatile
    var encryptionEnabled = false

    @Volatile
    var audioEncryptionEnabled = false

    /**
     * 当前 rikey（16 字节 AES key），控制流/音频加密共用。
     * Moonlight 每次连接（含 resume）都生成新 rikey 并放进 ANNOUNCE，
     * resume 时必须刷新，否则新客户端的加密控制帧全部解密失败。
     */
    @Volatile
    var riKey: ByteArray? = parseRiKey(config.rikeyHex)

    /** 客户端在 SETUP 阶段获得的 ping payload（16 字节随机 hex 字符串） */
    val avPingPayload: String = run {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append("%02X".format(b))
        sb.toString()
    }

    private var videoStreamer: VideoStreamer? = null
    private var audioStreamer: AudioStreamer? = null
    private var controlServer: ControlServer? = null
    @Volatile
    private var mediaStarted = false

    /** 视频/音频通道 socket：绑定固定端口，既发流也收客户端 ping */
    var videoSocket: DatagramSocket? = null
        private set
    var audioSocket: DatagramSocket? = null
        private set

    private var pingReceiver: Thread? = null

    /**
     * ping 线程当前正阻塞在 socket.receive() 里的起始时刻（0 = 不在 receive）。
     * 用来验证一个关键怀疑：DatagramSocket 的 send/receive 若共用一把锁，
     * ping 线程 200ms 的阻塞接收会把视频线程的 send 卡住 → 帧间隔抖动。
     */
    @Volatile
    var pingReceiveSinceMs = 0L
        internal set
    @Volatile
    private var videoPingLogged = false
    @Volatile
    private var audioPingLogged = false

    /** ping 接收线程代数：resume 时换代，旧线程自行退出，避免两个接收线程抢 socket */
    @Volatile
    private var pingGeneration = 0

    /** 编码器是否已启动（区分首次 ANNOUNCE 与 resume 的二次 ANNOUNCE） */
    val isMediaStarted: Boolean
        get() = mediaStarted

    fun start(): Boolean {
        if (isRunning) return true
        try {
            videoSocket = openUdp4(VIDEO_PORT)
            audioSocket = openUdp4(AUDIO_PORT)
            // 接收线程的循环条件依赖 isRunning，必须在线程启动前置为 true。
            isRunning = true
            startPingReceiver()
            // 息屏后镜像虚拟屏不再合成，编码器会彻底没有输入，必须先保持常亮
            com.wxz.sunshineserverandroid.input.CursorOverlay.acquireKeepAwake()
            val control = ControlServer(this)
            if (!control.start()) {
                stop()
                return false
            }
            controlServer = control
            ServerCore.log("流会话已启动：${config.width}x${config.height}@${config.fps}，packetsize=${config.packetSize}")
            return true
        } catch (e: Exception) {
            val st = e.stackTrace.take(5).joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
            ServerCore.log("流会话启动失败: ${e.javaClass.name}: ${e.message} @ $st")
            stop()
            return false
        }
    }

    fun startMedia(): Boolean {
        synchronized(this) {
            if (!isRunning) return false
            if (mediaStarted) return true
            mediaStarted = true
            try {
                videoStreamer = VideoStreamer(this).also { it.start() }
                audioStreamer = AudioStreamer(this, projection).also { it.start() }
                ServerCore.log("已按 ANNOUNCE 参数启动音视频编码器")
                return true
            } catch (e: Exception) {
                mediaStarted = false
                ServerCore.log("音视频编码器启动失败: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        }
    }

    /**
     * 监听视频/音频端口的客户端 ping，确定对端地址。
     *
     * 对端连续确认 PING_STABLE_COUNT 次后就**停止阻塞接收**：`socket.receive()` 阻塞时会
     * 持有这把 socket 的锁，同一 socket 上的 `send()` 只能干等到超时才发出去（实测单次
     * 卡 50~200ms，慢包全部落在 ping 处于 receive 中的那一刻）。局域网内客户端源端口不会变，
     * 继续收只是白白抢发送时间；稳定后把 socket 完全让给发送线程。
     */
    private fun startPingReceiver() {
        val generation = ++pingGeneration
        pingReceiver = Thread {
            val buf = ByteArray(256)
            var videoPings = 0
            var audioPings = 0
            var lastVideoPeer: InetSocketAddress? = null
            var lastAudioPeer: InetSocketAddress? = null
            while (isRunning && pingGeneration == generation) {
                var listening = false
                for (entry in listOf(Pair(videoSocket, true), Pair(audioSocket, false))) {
                    val socket = entry.first ?: continue
                    val isVideo = entry.second
                    val pings = if (isVideo) videoPings else audioPings
                    if (pings >= PING_STABLE_COUNT) continue
                    listening = true
                    try {
                        socket.soTimeout = VIDEO_PING_TIMEOUT_MS
                        val packet = DatagramPacket(buf, buf.size)
                        pingReceiveSinceMs = System.currentTimeMillis()
                        socket.receive(packet)
                        pingReceiveSinceMs = 0L
                        val message = String(buf, 0, packet.length, Charsets.US_ASCII)
                        if (!onAvPing(isVideo, packet.address, packet.port, message)) continue
                        val now = if (isVideo) videoPeer else audioPeer
                        if (isVideo) {
                            if (now == lastVideoPeer) videoPings++ else {
                                lastVideoPeer = now
                                videoPings = 1
                            }
                        } else {
                            if (now == lastAudioPeer) audioPings++ else {
                                lastAudioPeer = now
                                audioPings = 1
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        pingReceiveSinceMs = 0L
                    } catch (_: Exception) {
                        pingReceiveSinceMs = 0L
                        if (!isRunning) return@Thread
                    }
                }
                if (!listening) {
                    ServerCore.log("视频/音频对端已稳定，停止 ping 接收（socket 全让给发送线程）")
                    break
                }
            }
        }.also { it.start() }
    }

    /**
     * 客户端离开（控制通道断开）即暂停捕获——对齐原版 Sunshine 的"离开即停"语义：
     * 分离编码表面（虚拟屏停止合成）、停音频采集、放开常亮锁（屏幕可正常超时息屏）。
     * 会话对象/编码器/UDP socket 全部保留，resume 时由 [prepareForResume] 热恢复。
     */
    fun pauseCapture() {
        videoPeer = null
        audioPeer = null
        videoStreamer?.pauseCapture()
        audioStreamer?.pauseCapture()
        com.wxz.sunshineserverandroid.input.CursorOverlay.releaseKeepAwake()
        ServerCore.log("客户端离开：已暂停屏幕捕获与音频采集，等待 resume")
    }

    /**
     * 客户端断开后重新协商（resume 的二次 ANNOUNCE）：客户端重新建了 UDP socket，
     * 源端口大概率变了，必须清掉旧对端并重新监听 ping，否则视频/音频会一直发往死地址。
     */
    fun prepareForResume() {
        videoPeer = null
        audioPeer = null
        videoPingLogged = false
        audioPingLogged = false
        clientGoneSinceMs = 0L
        // 新 ANNOUNCE 带来了新 rikey（config.rikeyHex 已被 applyAnnounceArgs 更新），
        // 控制流与音频加密都必须换新 key，否则解密全部失败
        riKey = parseRiKey(config.rikeyHex)
        // 排干旧客户端积压在缓冲区的 ping：接收线程"稳定后停止接收"期间旧包持续入队，
        // 不排干会把 videoPeer/audioPeer 绑到旧死端口，新客户端的 ping 永远没人理。
        // 此时新客户端尚未 PLAY，缓冲里只有旧包，丢弃是安全的。
        drainUdpSocket(videoSocket)
        drainUdpSocket(audioSocket)
        startPingReceiver()
        // "离开即停"的热恢复：解除暂停门控、恢复音频采集（表面重挂由 forceFreshFrame 完成）
        videoStreamer?.resumeCapture()
        audioStreamer?.resumeCapture()
        // resume 视为一次新的连接：息屏则点亮（与首次启动同一策略），并刷新常亮锁
        com.wxz.sunshineserverandroid.input.CursorOverlay.acquireKeepAwake()
        // 源屏静止时编码器无输入，强制重挂表面逼出一帧 IDR 首帧
        videoStreamer?.forceFreshFrame()
        ServerCore.log("会话恢复：已重置视频/音频对端，等待新 ping")
    }

    /** 非阻塞排干 UDP 接收缓冲里的积压包（1ms 超时轮询直到读空） */
    private fun drainUdpSocket(socket: DatagramSocket?) {
        if (socket == null) return
        val oldTimeout = try {
            socket.soTimeout
        } catch (_: Exception) {
            return
        }
        var drained = 0
        try {
            socket.soTimeout = 1
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            while (true) {
                socket.receive(packet)
                drained++
            }
        } catch (_: java.net.SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            try {
                socket.soTimeout = oldTimeout
            } catch (_: Exception) {
            }
        }
        if (drained > 0) {
            ServerCore.log("已丢弃旧客户端积压 ping：${drained} 个")
        }
    }

    fun stop() {
        if (!isRunning && videoStreamer == null && audioStreamer == null && controlServer == null) return
        isRunning = false
        // 通知官方客户端优雅终止（原因 0x80030023），v1 客户端无此逻辑
        try {
            controlServer?.sendControlMessage(
                ControlServer.TYPE_TERMINATION,
                byteArrayOf(
                    0x80.toByte(), 0x03, 0x00, 0x23
                )
            )
        } catch (_: Exception) {
        }
        videoStreamer?.shutdown(); videoStreamer = null
        audioStreamer?.shutdown(); audioStreamer = null
        mediaStarted = false
        controlServer?.shutdown(); controlServer = null
        try {
            pingReceiver?.interrupt()
        } catch (_: Exception) {
        }
        try {
            videoSocket?.close()
        } catch (_: Exception) {
        }
        try {
            audioSocket?.close()
        } catch (_: Exception) {
        }
        videoSocket = null
        audioSocket = null
        com.wxz.sunshineserverandroid.input.InputDispatcher.reset()
        com.wxz.sunshineserverandroid.input.CursorOverlay.releaseKeepAwake()
        ServerCore.log("流会话已停止")
        if (ServerCore.session === this) {
            ServerCore.session = null
        }
    }

    /** 客户端向视频/音频端口发送的 ping：更新对端地址 */
    fun onAvPing(isVideo: Boolean, address: InetAddress, port: Int, message: String): Boolean {
        if (!message.contains(avPingPayload) && message != "PING") return false
        val peer = InetSocketAddress(address, port)
        if (isVideo) {
            videoPeer = peer
            if (!videoPingLogged) {
                videoPingLogged = true
                ServerCore.log("视频 UDP 对端已绑定：$peer")
            }
        } else {
            audioPeer = peer
            if (!audioPingLogged) {
                audioPingLogged = true
                ServerCore.log("音频 UDP 对端已绑定：$peer")
            }
        }
        return true
    }

    fun requestIdr() {
        videoStreamer?.requestIdr()
    }

    fun fail(reason: String) {
        if (!isRunning) return
        ServerCore.log("视频流失败：$reason")
        stop()
    }

    /** 向官方客户端发送控制消息（未连接/未启用时静默丢弃） */
    fun sendControlMessage(type: Int, payload: ByteArray) {
        controlServer?.sendControlMessage(type, payload)
    }

    /** "32 hex 字符 → 16 字节 key" */
    /**
     * /resume 或 /launch 命中既有会话时调用：Moonlight 每次连接都生成新 rikey，
     * 且只通过 /resume（/launch）查询串传递——RTSP ANNOUNCE 不带 rikey，
     * 不更新会导致新客户端的加密控制帧全部 AEADBadTag。
     */
    fun updateRiKey(rikeyHex: String, rikeyIdHex: String) {
        if (rikeyHex.isNotEmpty()) {
            config.rikeyHex = rikeyHex
            config.rikeyIdHex = rikeyIdHex
            riKey = parseRiKey(rikeyHex)
            ServerCore.log("会话加密密钥已按 resume 参数更新")
        }
    }

    private fun parseRiKey(hex: String): ByteArray? {
        if (hex.length != 32) return null
        return try {
            val key = ByteArray(16)
            for (i in 0 until 16) {
                key[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            key
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 对端发现阶段的 receive 超时（越小，发现期 send 被锁卡住的上限越小） */
        const val VIDEO_PING_TIMEOUT_MS = 50

        /** 同一地址连续确认几次 ping 后认为对端稳定，此后不再阻塞接收 */
        const val PING_STABLE_COUNT = 5

        const val VIDEO_PORT = 47998
        const val CONTROL_PORT = 47999
        const val AUDIO_PORT = 48000

        /** launch 后客户端应接入控制通道的超时（健康流程 /launch→RTSP→ENet 通常 <5s） */
        const val SESSION_CONNECT_TIMEOUT_MS = 30_000L

        /**
         * 客户端 ENet 断开后会话的保留时长。断开瞬间即暂停捕获（对齐原版 Sunshine 的
         * "离开即停"），但会话对象/编码器/socket 保留——Moonlight 的 resume 是"先断旧
         * ENet 再发 /resume"，保留期内 /resume 才能热恢复（无需重建编码器）。过期回收。
         */
        const val RESUME_GRACE_MS = 60_000L

        fun sendUdp(socket: java.net.DatagramSocket, peer: InetSocketAddress?, data: ByteArray, length: Int) {
            val target = peer ?: return
            try {
                socket.send(DatagramPacket(data, length, target.address, target.port))
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * 显式 IPv4 UDP socket：java.net.DatagramSocket(port) 在 Android 上绑定到
 * AF_INET6（/proc/net/udp6 的 ::），部分系统下 IPv4 数据报无法送达，
 * 这里强制 INET 协议族保证 IPv4 可达（与 ENetHost.startHost 同一策略）。
 */
private fun openUdp4(port: Int): DatagramSocket {
    return try {
        val channel = DatagramChannel.open(StandardProtocolFamily.INET)
        channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
        // 不放大 SO_SNDBUF：实时流要求发不出去时尽快 backpressure，
        // 缓冲过大只会把积压排队成秒级延迟后一次性到达。
        // InetSocketAddress(port) 的通配符是 IPv6 的 ::，绑定 IPv4 通道会抛
        // UnsupportedAddressTypeException，必须显式使用 IPv4 通配符 0.0.0.0
        channel.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        channel.socket()
    } catch (e: Exception) {
        ServerCore.log("openUdp4($port) 异常: ${e.javaClass.name}: ${e.message}")
        throw e
    }
}
