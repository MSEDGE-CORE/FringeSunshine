package com.wxz.sunshineserverandroid.stream

import com.wxz.sunshineserverandroid.ServerCore
import com.wxz.sunshineserverandroid.input.InputDispatcher
import java.net.InetSocketAddress

/**
 * 控制通道：UDP 47999，双路径。
 *
 * 官方 Moonlight 客户端：ENet（[ENetHost]），控制消息可能是明文 V1 {u16 type, payload}
 * 或加密帧（[ControlCrypto]）。
 * v1 自研客户端：裸格式 {u16 type LE, u16 length LE, payload}。
 */
class ControlServer(private val session: StreamSession) {

    private val enet = ENetHost(StreamSession.CONTROL_PORT, object : ENetHost.Listener {
        override fun onConnected() {
            session.controlConnected = true
        }

        override fun onPacket(payload: ByteArray) {
            handleEnetPacket(payload)
        }

        override fun onDisconnected() {
            session.controlConnected = false
            if (session.isRunning) {
                ServerCore.log("控制通道断开，结束会话")
                session.stop()
            }
        }

        override fun onLegacyPacket(buf: ByteArray, len: Int, from: InetSocketAddress) {
            handleLegacyPacket(buf, len, from)
        }
    })

    @Volatile
    private var lastPingTime = System.currentTimeMillis()

    @Volatile
    private var legacyActive = false

    private var watchdog: Thread? = null

    /** 发送序列号（加密帧用，服务端方向独立计数） */
    private var outSeq = 0

    fun start(): Boolean {
        if (!enet.startHost()) return false
        watchdog = Thread {
            while (enet.isAlive && !enet.isInterrupted) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (legacyActive && session.isRunning &&
                    System.currentTimeMillis() - lastPingTime > LEGACY_PING_TIMEOUT_MS
                ) {
                    ServerCore.log("控制通道 ping 超时（v1 客户端），结束会话")
                    session.stop()
                    break
                }
                // 官方/v1 客户端都未接入：/launch 后客户端从未走到控制通道（中途退出/断网），
                // 会话若不清理会一直 isRunning，吞掉后续所有 launch
                if (session.isRunning && !session.hasConnectedClient &&
                    System.currentTimeMillis() - session.startedAtMs > StreamSession.SESSION_CONNECT_TIMEOUT_MS
                ) {
                    ServerCore.log("会话创建 ${StreamSession.SESSION_CONNECT_TIMEOUT_MS / 1000}s 内无客户端控制通道连接，清理僵尸会话")
                    session.stop()
                    break
                }
            }
        }.also { it.start() }
        return true
    }

    fun shutdown() {
        enet.shutdown()
        watchdog?.interrupt()
    }

    /** 是否为官方 ENet 客户端连接 */
    fun isEnetConnected(): Boolean = enet.isConnected()

    /**
     * 发送控制消息给客户端（官方客户端）：
     * 加密启用时套 V2 加密帧，否则明文 V1 {u16 type, payload}，走 ENet 可靠包。
     */
    fun sendControlMessage(type: Int, payload: ByteArray) {
        if (!enet.isConnected()) return
        val key = session.riKey
        if (session.encryptionEnabled && key != null) {
            outSeq++
            val frame = ControlCrypto.encrypt(key, outSeq, type, payload)
            if (frame != null) enet.sendReliable(frame)
        } else {
            val packet = ByteArray(2 + payload.size)
            packet[0] = (type and 0xFF).toByte()
            packet[1] = ((type shr 8) and 0xFF).toByte()
            System.arraycopy(payload, 0, packet, 2, payload.size)
            enet.sendReliable(packet)
        }
    }

    // ---------------- 官方 ENet 路径 ----------------

    private fun handleEnetPacket(payload: ByteArray) {
        lastPingTime = System.currentTimeMillis()
        if (payload.size < 2) return

        if (session.encryptionEnabled) {
            val key = session.riKey
            if (key == null) return
            if (le16(payload, 0) == TYPE_ENCRYPTED) {
                val decoded = ControlCrypto.decrypt(key, payload)
                if (decoded == null) {
                    ServerCore.log("控制加密帧解密失败（len=${payload.size}）")
                    return
                }
                dispatchControl(decoded.first, decoded.second)
            } else {
                // 加密流上收到明文包：官方客户端不会这样，忽略
            }
        } else {
            dispatchControl(le16(payload, 0), payload.copyOfRange(2, payload.size))
        }
    }

    /** 控制消息分发（payload 不含控制头） */
    private fun dispatchControl(type: Int, payload: ByteArray) {
        when (type) {
            TYPE_PERIODIC_PING -> lastPingTime = System.currentTimeMillis()
            TYPE_LOSS_STATS, TYPE_FRAME_STATS -> Unit
            TYPE_REQUEST_IDR, TYPE_INVALIDATE_REF_FRAMES -> session.requestIdr()
            TYPE_INPUT_DATA -> handleInput(payload, encrypted = session.encryptionEnabled)
            TYPE_TERMINATION -> {
                ServerCore.log("客户端主动终止会话")
                session.stop()
            }
            else -> Unit
        }
    }

    /**
     * 输入数据（0x0206）。
     * 加密控制流：Sunshine 去掉 V2 头 4 字节后直接 passthrough，
     * payload 就是 NV_INPUT_HEADER 起始的原始输入包，不能再剥前缀。
     * 明文 V1：{u32 BE 加密长度, 加密块}，需要跳过 4 字节。
     */
    private fun handleInput(payload: ByteArray, encrypted: Boolean) {
        var offset = 0
        if (!encrypted && payload.size >= 8) {
            val declared = be32(payload, 0)
            if (declared in 4..(payload.size - 4)) {
                offset = 4
            }
        }
        InputDispatcher.dispatch(payload, offset, payload.size - offset)
    }

    // ---------------- v1 自研客户端裸路径 ----------------

    private fun handleLegacyPacket(buf: ByteArray, len: Int, from: InetSocketAddress) {
        if (len < 4) return
        val type = le16(buf, 0)
        val length = le16(buf, 2)
        if (4 + length > len) return

        session.controlPeer = InetSocketAddress(from.address, from.port)
        legacyActive = true
        val payload = buf.copyOfRange(4, 4 + length)

        when (type) {
            TYPE_PERIODIC_PING -> lastPingTime = System.currentTimeMillis()
            TYPE_LOSS_STATS -> Unit
            TYPE_REQUEST_IDR -> {
                lastPingTime = System.currentTimeMillis()
                session.requestIdr()
            }
            TYPE_INVALIDATE_REF_FRAMES -> session.requestIdr()
            TYPE_INPUT_DATA -> {
                lastPingTime = System.currentTimeMillis()
                handleInput(payload, encrypted = false)
            }
            TYPE_TERMINATION -> {
                ServerCore.log("客户端主动终止会话")
                session.stop()
            }
        }
    }

    private fun le16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8))

    private fun be32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    companion object {
        const val TYPE_PERIODIC_PING = 0x0200
        const val TYPE_LOSS_STATS = 0x0201
        const val TYPE_FRAME_STATS = 0x0204
        const val TYPE_INPUT_DATA = 0x0206
        const val TYPE_RUMBLE = 0x010B
        const val TYPE_TERMINATION = 0x0109
        const val TYPE_INVALIDATE_REF_FRAMES = 0x0301
        const val TYPE_REQUEST_IDR = 0x0302
        const val TYPE_ENCRYPTED = 0x0001
        const val TYPE_HDR_MODE = 0x010E

        private const val LEGACY_PING_TIMEOUT_MS = 10_000L
    }
}
