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

    /** 控制流加密（SS_ENC_CONTROL_V2），由 RTSP ANNOUNCE 协商结果决定 */
    @Volatile
    var encryptionEnabled = false

    @Volatile
    var audioEncryptionEnabled = false

    /** launch 的 rikey（16 字节 AES key），控制流加密用 */
    val riKey: ByteArray? = parseRiKey(config.rikeyHex)

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
    @Volatile
    private var videoPingLogged = false
    @Volatile
    private var audioPingLogged = false

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

    /** 监听视频/音频端口的客户端 ping，确定对端地址 */
    private fun startPingReceiver() {
        pingReceiver = Thread {
            val buf = ByteArray(256)
            while (isRunning) {
                for (entry in listOf(Pair(videoSocket, true), Pair(audioSocket, false))) {
                    val socket = entry.first ?: continue
                    try {
                        socket.soTimeout = 200
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val message = String(buf, 0, packet.length, Charsets.US_ASCII)
                        onAvPing(entry.second, packet.address, packet.port, message)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) {
                        if (!isRunning) return@Thread
                    }
                }
            }
        }.also { it.start() }
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
        const val VIDEO_PORT = 47998
        const val CONTROL_PORT = 47999
        const val AUDIO_PORT = 48000

        /** launch 后客户端应接入控制通道的超时（健康流程 /launch→RTSP→ENet 通常 <5s） */
        const val SESSION_CONNECT_TIMEOUT_MS = 30_000L

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
