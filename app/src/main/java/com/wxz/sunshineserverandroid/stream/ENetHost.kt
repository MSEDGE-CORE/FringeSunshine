package com.wxz.sunshineserverandroid.stream

import com.wxz.sunshineserverandroid.ServerCore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 ENet 服务端（单 peer），与官方 Moonlight 客户端线协议兼容。
 *
 * 端口 47999。数据包头 {u16 BE peerID|session|flags, u16 BE sentTime?}，
 * moonlight 的 enet fork 使用真实 htons/htonl，所有命令字段为大端。流程：客户端 CONNECT(头 peerID=0xFFF) → 本端回 VERIFY_CONNECT →
 * 客户端 ACK 后连接建立；可靠包按 channel 去重保序投递并回 ACK；
 * 不可靠/无序包直接投递；分片按 startSequenceNumber 重组；
 * 本端发出的可靠命令带超时重传。
 *
 * 非 ENet 数据报（v1 自研客户端裸格式）通过 onLegacyPacket 交回调用方处理。
 */
class ENetHost(private val port: Int, private val listener: Listener) : Thread("enet-host") {

    interface Listener {
        /** ENet 握手完成（客户端已 ACK VERIFY_CONNECT） */
        fun onConnected()

        /** 收到数据包（可靠/不可靠/无序包的 payload 部分） */
        fun onPacket(payload: ByteArray)

        /** 对端断开（收到 DISCONNECT 或超时）。可能在本类内部线程回调 */
        fun onDisconnected()

        /** 非 ENet 数据报（自研客户端裸协议），由调用方自行处理与回复（用 sendRaw） */
        fun onLegacyPacket(buf: ByteArray, len: Int, from: InetSocketAddress)
    }

    // ---------------- 数据结构 ----------------

    private class Channel {
        var incomingReliableSeq = 0
        var outgoingReliableSeq = 0
        /** 已收到但尚未按序投递的可靠包：seq -> (data, 投递后推进到的 seq) */
        val pendingReliable = HashMap<Int, PendingReliable>()
        /** 可靠分片重组中：startSeq -> set */
        val pendingFragments = HashMap<Int, FragmentSet>()
    }

    private class PendingReliable(val data: ByteArray, val advanceTo: Int)

    private class FragmentSet(val startSeq: Int, val count: Int, val totalLength: Int) {
        /** fragmentNumber -> (data, fragOffset) */
        val parts = HashMap<Int, Pair<ByteArray, Int>>()
        var received = 0
        var firstSeenMs = System.currentTimeMillis()
    }

    /** 已发出待 ACK 的可靠命令 */
    private class Outgoing(
        val cmdNumber: Int,
        val channel: Int,
        val seq: Int,
        val wire: ByteArray,
        var attempts: Int = 0,
        var lastSentMs: Long = 0,
        var rtoMs: Int = INITIAL_RTO_MS
    )

    private class QueuedAck(val channel: Int, val seq: Int, val receivedSentTime: Int)

    // ---------------- 状态 ----------------

    private val lock = Object()

    @Volatile
    private var socket: DatagramSocket? = null

    private val stopped = AtomicBoolean(false)

    /** 本端分配给对端的槽位索引（写入 VERIFY_CONNECT.outgoingPeerID，对端后续包头用它） */
    private val ourSlotIndex = 0

    private var state = STATE_DISCONNECTED
    private var peerAddress: InetSocketAddress? = null
    private var connectId = 0
    /** 发出的包头 peerID：对端本地槽位索引（来自 CONNECT.outgoingPeerID） */
    private var headerPeerId = MAX_PEER_ID
    /** 发出的包头 session 位（写入 VERIFY_CONNECT.incomingSessionID） */
    private var sessionToSend = 0xFF
    /** 期望收到的 session 位（写入 VERIFY_CONNECT.outgoingSessionID） */
    private var sessionExpected = 0xFF
    private var mtu = DEFAULT_MTU
    private var channelCount = 1

    private val channels = ArrayList<Channel>()
    private val ackQueue = LinkedHashMap<Long, QueuedAck>()
    private val outgoing = ArrayList<Outgoing>()
    /** 不可靠分片重组表（key=startSeq） */
    private val unreliableFragments = HashMap<Int, FragmentSet>()
    private var sentTimeCounter = 0
    private var lastReceiveMs = 0L
    private var lastPingSentMs = 0L

    /** 待投递给 listener 的 payload（锁内收集、锁外投递，避免慢回调阻塞收包/ACK） */
    private val deliverQueue = ArrayList<ByteArray>()

    /** 收包诊断日志配额：仅前 60 个数据报打印详情，避免刷屏 */
    private val datagramLogs = AtomicInteger()

    /** 发包诊断日志配额 */
    private val sendLogs = AtomicInteger()

    // ---------------- 生命周期 ----------------

    fun startHost(): Boolean {
        return try {
            // 显式 IPv4：java.net.DatagramSocket(port) 在 Android 上会绑定到
            // AF_INET6（/proc/net/udp6 的 ::），部分系统下 IPv4 数据报无法送达，
            // 这里强制 INET 协议族，保证端口出现在 /proc/net/udp（IPv4 表）中。
            val channel = DatagramChannel.open(StandardProtocolFamily.INET)
            channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true)
            // InetSocketAddress(port) 的通配符是 IPv6 的 ::，绑定 IPv4 通道会抛
            // UnsupportedAddressTypeException，必须显式使用 IPv4 通配符 0.0.0.0
            channel.bind(InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), port))
            socket = channel.socket()
            start()
            ServerCore.log("ENet 控制通道监听 $port（IPv4）")
            true
        } catch (e: Exception) {
            ServerCore.log("ENet 控制通道绑定 $port 失败: ${e.javaClass.name}: ${e.message}")
            false
        }
    }

    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        // 尽力通知对端断开
        synchronized(lock) {
            if (state == STATE_CONNECTED) {
                queueDisconnectLocked()
                try {
                    flushLocked()
                } catch (_: Exception) {
                }
            }
        }
        socket?.close()
        interrupt()
    }

    fun isConnected(): Boolean = state == STATE_CONNECTED

    /** 发送可靠数据包（channel 0），仅在已连接时有效 */
    fun sendReliable(payload: ByteArray) {
        synchronized(lock) {
            if (state != STATE_CONNECTED) return
            val ch = channelAtLocked(0) ?: return
            val seq = (ch.outgoingReliableSeq + 1) and 0xFFFF
            ch.outgoingReliableSeq = seq
            val wire = ByteArray(6 + payload.size)
            wire[0] = (CMD_SEND_RELIABLE or FLAG_ACKNOWLEDGE).toByte()
            wire[1] = 0
            wire[2] = ((seq shr 8) and 0xFF).toByte()
            wire[3] = (seq and 0xFF).toByte()
            wire[4] = ((payload.size shr 8) and 0xFF).toByte()
            wire[5] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, wire, 6, payload.size)
            outgoing.add(Outgoing(CMD_SEND_RELIABLE, 0, seq, wire))
            try {
                flushLocked()
            } catch (_: Exception) {
            }
        }
    }

    /** 非 ENet（自研客户端）场景下发送裸 UDP 数据 */
    fun sendRaw(data: ByteArray, len: Int, to: InetSocketAddress) {
        try {
            socket?.send(DatagramPacket(data, len, to.address, to.port))
        } catch (_: Exception) {
        }
    }

    override fun run() {
        val sock = socket ?: return
        val buf = ByteArray(65536)
        try {
            sock.soTimeout = 20
        } catch (e: Exception) {
            ServerCore.log("ENet soTimeout 设置失败: ${e.message}")
        }
        while (!stopped.get()) {
            // 每轮新建 packet：Android DatagramSocket.receive 以 packet.length 为上限，
            // 复用会把后续数据报截断为上一次的长度，导致 SEND_RELIABLE 解析失败、ACK 缺失
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                tick()
                continue
            } catch (_: Exception) {
                if (stopped.get()) break
                continue
            }
            val from = InetSocketAddress(packet.address, packet.port)
            val len = packet.length
            if (datagramLogs.get() < 60) {
                datagramLogs.incrementAndGet()
                ServerCore.log("ENet 收到 UDP $len 字节 来自 $from hex=${hexFull(buf, len)}")
            }
            val handled = synchronized(lock) {
                handleDatagramLocked(buf, len, from)
            }
            deliverPending()
            if (!handled) {
                listener.onLegacyPacket(buf, len, from)
            }
            tick()
        }
    }

    /** 周期任务：超时重传、断开检查 */
    private fun tick() {
        synchronized(lock) {
            if (state == STATE_CONNECTED && System.currentTimeMillis() - lastReceiveMs > PEER_TIMEOUT_MS) {
                ServerCore.log("ENet 对端超时（15 秒无数据）")
                resetPeerLocked(notify = true)
            } else if (state != STATE_DISCONNECTED) {
                try {
                    flushLocked()
                    val now = System.currentTimeMillis()
                    if (state == STATE_CONNECTED && now - lastPingSentMs >= PING_INTERVAL_MS) {
                        sendPingLocked()
                    }
                } catch (_: Exception) {
                }
            }
        }
        deliverPending()
    }

    /** 锁外批量投递 payload，避免 listener 慢回调（如输入注入）阻塞 ENet 收包与 ACK */
    private fun deliverPending() {
        while (true) {
            val batch: List<ByteArray>
            synchronized(lock) {
                if (deliverQueue.isEmpty()) return
                batch = ArrayList(deliverQueue)
                deliverQueue.clear()
            }
            for (payload in batch) {
                listener.onPacket(payload)
            }
        }
    }

    // ---------------- 接收 ----------------

    /** 返回 true 表示按 ENet 处理；false 表示不是 ENet 数据报 */
    private fun handleDatagramLocked(buf: ByteArray, len: Int, from: InetSocketAddress): Boolean {
        if (len < 2) return false
        val peerIdRaw = be16(buf, 0)
        val flags = peerIdRaw and 0xC000
        val session = (peerIdRaw and 0x3000) shr 12
        val peerId = peerIdRaw and 0x0FFF
        val hasSentTime = (flags and HEADER_SENT_TIME) != 0
        // 真实 enet 包头：u16 peerID + 可选 u16 sentTime（2 或 4 字节，非 4/6）
        val headerSize = if (hasSentTime) 4 else 2
        if (len < headerSize) return false
        val receivedSentTime = if (hasSentTime) be16(buf, 2) else 0

        // 未连接槽位的 CONNECT
        if (peerId == MAX_PEER_ID) {
            if (len >= headerSize + 4) {
                val cmdByte = buf[headerSize].toInt() and 0xFF
                if ((cmdByte and CMD_MASK) == CMD_CONNECT) {
                    handleConnectLocked(buf, headerSize, len, from)
                    flushLocked()
                    return true
                }
            }
            return false
        }

        // 已有 peer 的常规数据
        if (state == STATE_DISCONNECTED) return false
        if (peerId != ourSlotIndex) return false
        val addr = peerAddress ?: return false
        if (addr != from) return false
        if (session != sessionExpected) return false

        lastReceiveMs = System.currentTimeMillis()

        var offset = headerSize
        while (offset + 4 <= len) {
            val cmdByte = buf[offset].toInt() and 0xFF
            val cmdNumber = cmdByte and CMD_MASK
            if (cmdNumber == 0 || cmdNumber >= COMMAND_SIZES.size) break
            val cmdSize = COMMAND_SIZES[cmdNumber]
            if (offset + cmdSize > len) break
            val channelId = buf[offset + 1].toInt() and 0xFF
            val reliableSeq = be16(buf, offset + 2)
            val hasAckFlag = (cmdByte and FLAG_ACKNOWLEDGE) != 0

            var next = offset + cmdSize
            var malformed = false
            when (cmdNumber) {
                CMD_ACK -> handleAckLocked(buf, offset)
                CMD_DISCONNECT -> {
                    if (hasAckFlag) queueAckLocked(channelId, reliableSeq, receivedSentTime)
                    flushLocked() // 先把 ACK 发出去
                    ServerCore.log("ENet 对端请求断开")
                    resetPeerLocked(notify = true)
                    return true
                }
                CMD_PING -> if (hasAckFlag) queueAckLocked(channelId, reliableSeq, receivedSentTime)
                CMD_SEND_RELIABLE -> {
                    val dataLen = be16(buf, offset + 4)
                    next = offset + cmdSize + dataLen
                    if (next > len) {
                        malformed = true
                    } else {
                        handleReliableLocked(channelId, reliableSeq, buf, offset + cmdSize, dataLen, receivedSentTime)
                    }
                }
                CMD_SEND_UNRELIABLE, CMD_SEND_UNSEQUENCED -> {
                    val dataLen = be16(buf, offset + 6)
                    next = offset + cmdSize + dataLen
                    if (next > len) {
                        malformed = true
                    } else if (dataLen > 0) {
                        deliverQueue.add(buf.copyOfRange(offset + cmdSize, next))
                    }
                }
                CMD_SEND_FRAGMENT, CMD_SEND_UNRELIABLE_FRAGMENT -> {
                    val dataLen = be16(buf, offset + 6)
                    next = offset + cmdSize + dataLen
                    if (next > len) {
                        malformed = true
                    } else {
                        handleFragmentLocked(
                            buf, offset, channelId, reliableSeq, hasAckFlag,
                            cmdNumber == CMD_SEND_FRAGMENT, receivedSentTime
                        )
                    }
                }
                // BANDWIDTH_LIMIT / THROTTLE_CONFIGURE 等其余命令：
                // 真实 moonlight fork 客户端连接约 1 秒后会发 BANDWIDTH_LIMIT（带 ACK 标志），
                // 不回 ACK 会导致其无限重传并在 timeoutMaximum(10s) 后主动断开。
                // 镜像上游 enet：任何携带 FLAG_ACKNOWLEDGE 的命令（ACK 自身除外）都要回 ACK。
                else -> if (hasAckFlag && cmdNumber != CMD_ACK) {
                    queueAckLocked(channelId, reliableSeq, receivedSentTime)
                }
            }
            if (malformed) break
            offset = next
        }

        flushLocked()
        return true
    }

    private fun handleConnectLocked(buf: ByteArray, off: Int, len: Int, from: InetSocketAddress) {
        // 标准 ENet CONNECT 命令共 48 字节（4 命令头 + 44 内容）
        if (len < off + 48) {
            ServerCore.log("ENet CONNECT 长度不足：$len < ${off + 48}，丢弃")
            return
        }
        val cmdChannelCount = be32(buf, off + 16)
        if (cmdChannelCount < MIN_CHANNELS || cmdChannelCount > MAX_CHANNELS) return

        // 重复 CONNECT（VERIFY 丢失重传）：立即重发 pending VERIFY
        if (state != STATE_DISCONNECTED && peerAddress == from) {
            for (cmd in outgoing) {
                if (cmd.cmdNumber == CMD_VERIFY_CONNECT) cmd.lastSentMs = 0
            }
            return
        }
        if (state != STATE_DISCONNECTED) return

        val cmdMtu = be32(buf, off + 8)
        val cmdWindow = be32(buf, off + 12)
        val inSession = buf[off + 6].toInt() and 0xFF
        val outSession = buf[off + 7].toInt() and 0xFF

        peerAddress = from
        connectId = be32(buf, off + 40)
        headerPeerId = be16(buf, off + 4)
        mtu = cmdMtu.coerceIn(MIN_MTU, MAX_MTU).coerceAtMost(DEFAULT_MTU)
        channelCount = cmdChannelCount

        // session 协商（镜像 enet handle_connect：0xFF 时取当前值再 +1 错开）
        var s1 = if (inSession == 0xFF) sessionToSend else inSession
        s1 = (s1 + 1) and 3
        if (s1 == sessionToSend) s1 = (s1 + 1) and 3
        sessionToSend = s1
        var s2 = if (outSession == 0xFF) sessionExpected else outSession
        s2 = (s2 + 1) and 3
        if (s2 == sessionExpected) s2 = (s2 + 1) and 3
        sessionExpected = s2

        channels.clear()
        for (i in 0 until channelCount) channels.add(Channel())

        // 窗口：本端不限带宽 → 65536，与对端请求取小后夹紧
        val window = (if (cmdWindow <= 0) MAX_WINDOW else cmdWindow)
            .coerceAtMost(MAX_WINDOW).coerceAtLeast(MIN_WINDOW)

        // VERIFY_CONNECT（44 字节，reliableSeq=0, channelID=0xFF；标准协议无 data 字段）
        val v = ByteArray(44)
        v[0] = (CMD_VERIFY_CONNECT or FLAG_ACKNOWLEDGE).toByte()
        v[1] = 0xFF.toByte()
        v[2] = 0
        v[3] = 0
        put16(v, 4, ourSlotIndex)
        v[6] = sessionToSend.toByte()
        v[7] = sessionExpected.toByte()
        put32(v, 8, mtu)
        put32(v, 12, window)
        put32(v, 16, channelCount)
        put32(v, 20, 0) // incomingBandwidth
        put32(v, 24, 0) // outgoingBandwidth
        put32(v, 28, be32(buf, off + 28)) // throttleInterval 镜像
        put32(v, 32, be32(buf, off + 32)) // throttleAcceleration
        put32(v, 36, be32(buf, off + 36)) // throttleDeceleration
        put32(v, 40, connectId)

        outgoing.clear()
        outgoing.add(Outgoing(CMD_VERIFY_CONNECT, 0xFF, 0, v))
        state = STATE_ACKNOWLEDGING
        lastReceiveMs = System.currentTimeMillis()
        ServerCore.log("ENet CONNECT 来自 $from（connectId=$connectId, mtu=$mtu, channels=$channelCount）")
    }

    private fun handleAckLocked(buf: ByteArray, off: Int) {
        val receivedSeq = be16(buf, off + 4)
        val channel = buf[off + 1].toInt() and 0xFF
        val it = outgoing.iterator()
        while (it.hasNext()) {
            val cmd = it.next()
            if (cmd.channel == channel && cmd.seq == receivedSeq) {
                it.remove()
                if (cmd.cmdNumber == CMD_VERIFY_CONNECT && state == STATE_ACKNOWLEDGING) {
                    state = STATE_CONNECTED
                    ServerCore.log("ENet 控制通道已建立")
                    listener.onConnected()
                }
                break
            }
        }
    }

    private fun handleReliableLocked(
        channelId: Int, seq: Int, buf: ByteArray, dataOff: Int, dataLen: Int, receivedSentTime: Int
    ) {
        val channel = channelAtLocked(channelId)
        if (channel != null) {
            val last = channel.incomingReliableSeq
            if (seq != last) {
                val diff = (seq - last) and 0xFFFF
                if (diff in 1..RELIABLE_WINDOW && dataLen > 0) {
                    channel.pendingReliable[seq] = PendingReliable(
                        buf.copyOfRange(dataOff, dataOff + dataLen), seq
                    )
                    drainChannelLocked(channel)
                }
            }
        }
        queueAckLocked(channelId, seq, receivedSentTime)
    }

    private fun handleFragmentLocked(
        buf: ByteArray, off: Int, channelId: Int, headerSeq: Int, hasAckFlag: Boolean,
        reliable: Boolean, receivedSentTime: Int
    ) {
        val startSeq = be16(buf, off + 4)
        val dataLen = be16(buf, off + 6)
        val fragCount = be32(buf, off + 8)
        val fragNumber = be32(buf, off + 12)
        val totalLength = be32(buf, off + 16)
        val fragOffset = be32(buf, off + 20)
        if (fragCount == 0 || fragNumber >= fragCount) return
        if (fragOffset >= totalLength || fragOffset + dataLen > totalLength) return
        if (dataLen <= 0) return

        if (hasAckFlag) queueAckLocked(channelId, headerSeq, receivedSentTime)

        if (reliable) {
            val channel = channelAtLocked(channelId) ?: return
            val set = channel.pendingFragments.getOrPut(startSeq) {
                FragmentSet(startSeq, fragCount, totalLength)
            }
            if (set.count == fragCount && !set.parts.containsKey(fragNumber)) {
                set.parts[fragNumber] = Pair(buf.copyOfRange(off + 24, off + 24 + dataLen), fragOffset)
                set.received++
                if (set.received >= fragCount) {
                    channel.pendingFragments.remove(startSeq)
                    val assembled = assemble(set)
                    val last = channel.incomingReliableSeq
                    val diff = (startSeq - last) and 0xFFFF
                    if (diff in 1..RELIABLE_WINDOW) {
                        // 分片包占用 startSeq..startSeq+count-1
                        channel.pendingReliable[startSeq] =
                            PendingReliable(assembled, (startSeq + fragCount - 1) and 0xFFFF)
                        drainChannelLocked(channel)
                    }
                }
            }
        } else {
            // 不可靠分片：重组完成即投递，不排序
            val set = unreliableFragments.getOrPut(startSeq) {
                FragmentSet(startSeq, fragCount, totalLength)
            }
            if (set.count == fragCount && !set.parts.containsKey(fragNumber)) {
                set.parts[fragNumber] = Pair(buf.copyOfRange(off + 24, off + 24 + dataLen), fragOffset)
                set.received++
                if (set.received >= fragCount) {
                    unreliableFragments.remove(startSeq)
                    deliverQueue.add(assemble(set))
                }
            }
        }
        cleanStaleFragments()
    }

    private fun assemble(set: FragmentSet): ByteArray {
        val assembled = ByteArray(set.totalLength)
        for ((_, part) in set.parts) {
            System.arraycopy(part.first, 0, assembled, part.second, part.first.size)
        }
        return assembled
    }

    private fun cleanStaleFragments() {
        val now = System.currentTimeMillis()
        for (ch in channels) {
            val it = ch.pendingFragments.values.iterator()
            while (it.hasNext()) {
                if (now - it.next().firstSeenMs > 5_000) it.remove()
            }
        }
        val it = unreliableFragments.values.iterator()
        while (it.hasNext()) {
            if (now - it.next().firstSeenMs > 5_000) it.remove()
        }
    }

    /** 按序投递 channel 缓存中的可靠包（PendingReliable.advanceTo 处理分片占多个 seq） */
    private fun drainChannelLocked(channel: Channel) {
        while (true) {
            val next = (channel.incomingReliableSeq + 1) and 0xFFFF
            val pending = channel.pendingReliable.remove(next) ?: break
            channel.incomingReliableSeq = pending.advanceTo
            deliverQueue.add(pending.data)
        }
    }

    private fun queueAckLocked(channel: Int, seq: Int, receivedSentTime: Int) {
        val key = (channel.toLong() shl 16) or seq.toLong()
        ackQueue[key] = QueuedAck(channel, seq, receivedSentTime)
        if (ackQueue.size > 64) {
            val it = ackQueue.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    private fun queueDisconnectLocked() {
        val wire = ByteArray(8)
        wire[0] = (CMD_DISCONNECT or FLAG_ACKNOWLEDGE).toByte()
        wire[1] = 0xFF.toByte()
        outgoing.add(Outgoing(CMD_DISCONNECT, 0xFF, 0, wire))
    }

    private fun sendPingLocked() {
        val sock = socket ?: return
        val target = peerAddress ?: return
        // 发送带 ACK 标志的 PING，使客户端回 ACK，更新 server 端的 lastReceiveMs，防止 15 秒超时
        val packet = buildPacketLocked { buf, off, _ ->
            buf[off] = (CMD_PING or FLAG_ACKNOWLEDGE).toByte()
            buf[off + 1] = 0xFF.toByte()
            put16(buf, off + 2, 0)
            off + 4
        }
        logSend(packet.first, packet.second)
        sock.send(DatagramPacket(packet.first, packet.second, target.address, target.port))
        lastPingSentMs = System.currentTimeMillis()
    }

    /** 发包诊断日志（前 40 个，全 hex） */
    private fun logSend(data: ByteArray, len: Int) {
        if (sendLogs.get() < 40) {
            sendLogs.incrementAndGet()
            ServerCore.log("ENet 发送 $len 字节 hex=${hexFull(data, len)}")
        }
    }

    private fun resetPeerLocked(notify: Boolean) {
        val wasConnected = state == STATE_CONNECTED
        state = STATE_DISCONNECTED
        peerAddress = null
        headerPeerId = MAX_PEER_ID
        sessionToSend = 0xFF
        sessionExpected = 0xFF
        channels.clear()
        ackQueue.clear()
        outgoing.clear()
        unreliableFragments.clear()
        if (notify && wasConnected) listener.onDisconnected()
    }

    // ---------------- 发送 ----------------

    private fun channelAtLocked(id: Int): Channel? =
        if (id < channels.size) channels[id] else null

    /** 组包发送：ACK 队列 + 到期（重）传的可靠命令。调用方持有 lock */
    private fun flushLocked() {
        if (state == STATE_DISCONNECTED) {
            ackQueue.clear()
            return
        }
        val sock = socket ?: return
        val target = peerAddress ?: return
        val now = System.currentTimeMillis()

        // 1) ACK 包（每包尽量多装）
        while (ackQueue.isNotEmpty()) {
            val packet = buildPacketLocked { buf, off, capacity ->
                var cursor = off
                val it = ackQueue.entries.iterator()
                while (it.hasNext() && cursor + 8 <= capacity) {
                    val ack = it.next().value
                    buf[cursor] = CMD_ACK.toByte()
                    buf[cursor + 1] = ack.channel.toByte()
                    put16(buf, cursor + 2, 0)
                    put16(buf, cursor + 4, ack.seq)
                    put16(buf, cursor + 6, ack.receivedSentTime)
                    it.remove()
                    cursor += 8
                }
                cursor
            }
            if (packet.second <= 4) break
            logSend(packet.first, packet.second)
            sock.send(DatagramPacket(packet.first, packet.second, target.address, target.port))
        }

        // 2) 可靠命令（首发 + 到期重传）
        for (cmd in outgoing) {
            val due = cmd.attempts == 0 || now - cmd.lastSentMs >= cmd.rtoMs
            if (!due) continue
            if (cmd.attempts > 0) cmd.rtoMs = (cmd.rtoMs * 2).coerceAtMost(MAX_RTO_MS)
            cmd.attempts++
            cmd.lastSentMs = now
            val packet = buildPacketLocked { buf, off, _ ->
                System.arraycopy(cmd.wire, 0, buf, off, cmd.wire.size)
                off + cmd.wire.size
            }
            logSend(packet.first, packet.second)
            sock.send(DatagramPacket(packet.first, packet.second, target.address, target.port))
        }

        // 3) 长期未 ACK → 断开
        var timedOut = false
        for (cmd in outgoing) {
            if (cmd.attempts > 0 && now - cmd.lastSentMs > PEER_TIMEOUT_MS) {
                ServerCore.log("ENet 可靠命令（cmd=${cmd.cmdNumber}）长时间未确认，断开对端")
                timedOut = true
                break
            }
        }
        if (timedOut) {
            resetPeerLocked(notify = true)
        }
    }

    /**
     * 构造一个数据包：4 字节头（peerID + sentTime）+ fill 填充命令区并返回结束偏移。
     * capacity 为缓冲区可用上限。
     */
    private fun buildPacketLocked(fill: (ByteArray, Int, Int) -> Int): Pair<ByteArray, Int> {
        val buf = ByteArray(mtu)
        sentTimeCounter = (sentTimeCounter + 1) and 0xFFFF
        put16(buf, 0, headerPeerId or (sessionToSend shl 12) or HEADER_SENT_TIME)
        put16(buf, 2, sentTimeCounter)
        val end = fill(buf, 4, mtu)
        return Pair(buf, end)
    }

    // ---------------- 基础工具 ----------------

    /** moonlight 的 enet fork 使用真实 htons/htonl：整条线上协议为大端 */
    private fun be16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun be32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun put16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun put32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 24) and 0xFF).toByte()
        buf[off + 1] = ((v shr 16) and 0xFF).toByte()
        buf[off + 2] = ((v shr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    companion object {
        const val CMD_ACK = 1
        const val CMD_CONNECT = 2
        const val CMD_VERIFY_CONNECT = 3
        const val CMD_DISCONNECT = 4
        const val CMD_PING = 5
        const val CMD_SEND_RELIABLE = 6
        const val CMD_SEND_UNRELIABLE = 7
        const val CMD_SEND_FRAGMENT = 8
        const val CMD_SEND_UNSEQUENCED = 9
        const val CMD_SEND_UNRELIABLE_FRAGMENT = 12

        const val FLAG_ACKNOWLEDGE = 0x80
        const val CMD_MASK = 0x0F
        const val HEADER_SENT_TIME = 0x8000
        const val MAX_PEER_ID = 0x0FFF

        const val MIN_MTU = 576
        const val MAX_MTU = 4096
        const val DEFAULT_MTU = 1392
        const val MIN_WINDOW = 4096
        const val MAX_WINDOW = 65536
        const val MIN_CHANNELS = 1
        const val MAX_CHANNELS = 255

        /** 可接受的前向 seq 距离 */
        const val RELIABLE_WINDOW = 0x2000

        const val INITIAL_RTO_MS = 500
        const val MAX_RTO_MS = 5_000
        const val PEER_TIMEOUT_MS = 15_000
        const val PING_INTERVAL_MS = 5_000

        private const val STATE_DISCONNECTED = 0
        private const val STATE_ACKNOWLEDGING = 1
        private const val STATE_CONNECTED = 2

        private val COMMAND_SIZES = intArrayOf(
            0,   // 0
            8,   // ACK
            48,  // CONNECT
            44,  // VERIFY_CONNECT
            8,   // DISCONNECT
            4,   // PING
            6,   // SEND_RELIABLE
            8,   // SEND_UNRELIABLE
            24,  // SEND_FRAGMENT
            8,   // SEND_UNSEQUENCED
            12,  // BANDWIDTH_LIMIT
            16,  // THROTTLE_CONFIGURE
            24,  // SEND_UNRELIABLE_FRAGMENT
        )
    }
}

/** 完整十六进制转储（最长 64 字节），用于 ENet 收发包诊断日志 */
private fun hexFull(buf: ByteArray, len: Int): String {
    val n = if (len < 64) len else 64
    val sb = StringBuilder()
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        sb.append(String.format("%02X", buf[i]))
    }
    if (len > n) sb.append("..($len)")
    return sb.toString()
}
