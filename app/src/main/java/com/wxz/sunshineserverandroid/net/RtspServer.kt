package com.wxz.sunshineserverandroid.net

import com.wxz.sunshineserverandroid.ServerCore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RTSP 设置通道（端口 48010）：OPTIONS / DESCRIBE / SETUP / ANNOUNCE / PLAY。
 *
 * 每个客户端连接由线程池独立处理，任何一条连接卡死/空闲都不会阻塞其他连接
 * （客户端 connect 由内核代答，若 accept 线程被占用，表现恰好是
 * "连接成功但 OPTIONS 15 秒无响应"）。
 */
class RtspServer : Thread("rtsp-server") {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var stopped = false
        private set

    private val clientPool: ExecutorService =
        Executors.newCachedThreadPool { r -> Thread(r, "rtsp-client") }

    fun startServer(): Boolean = try {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        serverSocket = socket
        start()
        ServerCore.log("RTSP 服务已启动，端口 $PORT")
        true
    } catch (e: Exception) {
        ServerCore.log("RTSP 服务启动失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    fun stopServer() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientPool.shutdownNow()
    }

    override fun run() {
        val socket = serverSocket ?: return
        var acceptFailures = 0
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (stopped) break
                acceptFailures++
                // 带退避重试，避免热自旋；周期性留下日志便于诊断
                if (acceptFailures == 1 || acceptFailures % 20 == 0) {
                    ServerCore.log("RTSP accept 异常（第 $acceptFailures 次）: ${e.javaClass.simpleName}: ${e.message}")
                }
                try {
                    sleep(200)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            acceptFailures = 0
            clientPool.execute {
                try {
                    handleClient(client)
                } catch (e: Throwable) {
                    ServerCore.log("RTSP 处理异常: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        ServerCore.log("RTSP 服务线程退出")
    }

    /**
     * moonlight（moonlight-common-c）的 TCP RTSP 为"每请求一个连接"：
     * 连接 → 发一条请求 → 持续读到服务端关闭连接（EOF）→ 才解析响应。
     * 因此处理完一条请求必须立即关闭连接，不能像 HTTP keep-alive 那样等待下一条。
     */
    private fun handleClient(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        ServerCore.log("RTSP 连接：${client.inetAddress?.hostAddress}:${client.port}")

        val requestLine = try {
            reader.readLine() ?: return // 对端关闭
        } catch (_: SocketTimeoutException) {
            ServerCore.log("RTSP 连接 15 秒无请求，关闭")
            return
        }
        if (requestLine.isEmpty()) return
        ServerCore.log("RTSP <- $requestLine")
        val parts = requestLine.split(" ")
        if (parts.size < 3) return
        val method = parts[0]

        // 解析 header
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx == -1) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val payload = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(chars, 0, read)
        } else ""
        val cseq = headers["cseq"] ?: "1"

        when (method) {
            "OPTIONS" -> respond(client, cseq, "RTSP/1.0 200 OK")
            "DESCRIBE" -> respond(client, cseq, "RTSP/1.0 200 OK", describeBody())
            "ANNOUNCE" -> handleAnnounce(client, cseq, payload)
            "SETUP" -> handleSetup(client, cseq, parts[1])
            "PLAY" -> respond(client, cseq, "RTSP/1.0 200 OK")
            "TEARDOWN" -> {
                ServerCore.session?.stop()
                respond(client, cseq, "RTSP/1.0 200 OK")
            }
            else -> respond(client, cseq, "RTSP/1.0 404 NOT FOUND")
        }
        // 响应完毕即由 finally 关闭连接（客户端读到 EOF 才会解析响应）
    }

    /** DESCRIBE：告知能力（v1 固定 H.264 + Opus 立体声） */
    private fun describeBody(): String =
        "a=x-ss-general.featureFlags:0x" + Integer.toHexString(FEATURE_FLAGS) + "\n" +
            "a=x-ss-general.encryptionSupported:1\n" + // SS_ENC_CONTROL_V2
            "a=x-ss-general.encryptionRequested:1\n" +
            "a=fmtp:97 surround-params=210\n" + // 2 声道 1 流 0 耦合 映射 0,1
            "a=rtpmap:97 opus/48000/2\n"

    private fun handleAnnounce(client: Socket, cseq: String, payload: String) {
        val session = ServerCore.session
        if (session == null || !session.isRunning) {
            respond(client, cseq, "RTSP/1.0 500 INTERNAL SERVER ERROR")
            return
        }
        com.wxz.sunshineserverandroid.stream.StreamConfig.applyAnnounceArgs(session.config, payload)
        // 加密协商结果：x-ss-general.encryptionEnabled（位 0 = SS_ENC_CONTROL_V2）
        for (line in payload.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("a=x-ss-general.encryptionEnabled:")) continue
            val value = trimmed.substringAfter(':').trim().toIntOrNull() ?: 0
            session.encryptionEnabled = (value and 0x01) != 0
            session.audioEncryptionEnabled = (value and 0x04) != 0
        }
        if (session.encryptionEnabled && session.riKey == null) {
            // 客户端要求加密但 launch 未提供 rikey：只能拒绝加密
            session.encryptionEnabled = false
        }
        if (session.audioEncryptionEnabled && session.riKey == null) {
            session.audioEncryptionEnabled = false
        }
        if (!session.startMedia()) {
            respond(client, cseq, "RTSP/1.0 500 INTERNAL SERVER ERROR")
            return
        }
        ServerCore.log(
            "ANNOUNCE：${session.config.width}x${session.config.height}@${session.config.fps}，" +
                "packetsize=${session.config.packetSize}，音频 ${session.config.audioChannels}ch，" +
                "控制流加密=${session.encryptionEnabled}"
        )
        respond(client, cseq, "RTSP/1.0 200 OK")
    }

    /** SETUP：告知客户端各通道端口与 ping payload */
    private fun handleSetup(client: Socket, cseq: String, target: String) {
        val session = ServerCore.session
        if (session == null) {
            respond(client, cseq, "RTSP/1.0 500 INTERNAL SERVER ERROR")
            return
        }
        val streamId = target.substringAfter("streamid=", "").substringBefore('/').substringBefore('&')
        val videoPort = com.wxz.sunshineserverandroid.stream.StreamSession.VIDEO_PORT
        val audioPort = com.wxz.sunshineserverandroid.stream.StreamSession.AUDIO_PORT
        val controlPort = com.wxz.sunshineserverandroid.stream.StreamSession.CONTROL_PORT

        // moonlight 要求 SETUP 响应携带 Session 头（取 ";" 前的 token 用于后续请求）
        val sessionHeader = "Session: 1;timeout=90\r\n"
        val extra = when (streamId) {
            "audio" -> sessionHeader + "Transport: server_port=$audioPort\r\nX-SS-Ping-Payload: ${session.avPingPayload}\r\n"
            "video" -> sessionHeader + "Transport: server_port=$videoPort\r\nX-SS-Ping-Payload: ${session.avPingPayload}\r\n"
            "control" -> sessionHeader + "Transport: server_port=$controlPort\r\nX-SS-Connect-Data: 1\r\n"
            else -> sessionHeader + "Transport: server_port=$controlPort\r\n"
        }
        respond(client, cseq, "RTSP/1.0 200 OK", null, extra)
    }

    private fun respond(
        client: Socket,
        cseq: String,
        statusLine: String,
        body: String? = null,
        extraHeaders: String? = null
    ) {
        val sb = StringBuilder()
        sb.append(statusLine).append("\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        if (extraHeaders != null) sb.append(extraHeaders)
        if (body != null) {
            sb.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
        }
        sb.append("\r\n")
        if (body != null) sb.append(body)
        client.getOutputStream().apply {
            write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    companion object {
        const val PORT = 48010
        const val FEATURE_FLAGS = 0x01
    }
}
