package com.wxz.sunshineserverandroid.net

import com.wxz.sunshineserverandroid.ServerCore
import com.wxz.sunshineserverandroid.stream.StreamConfig
import com.wxz.sunshineserverandroid.stream.StreamSession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * GameStream HTTP 服务：
 * 明文 47989（/serverinfo 等）+ TLS 47984（官方 Moonlight 配对强制 HTTPS）。
 *
 * XML 结构复刻 Sunshine nvhttp.cpp。
 */
class NvHttpServer(
    private val serverPort: Int = PORT,
    private val tls: Boolean = false
) : Thread(if (tls) "nvhttp-tls" else "nvhttp") {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var stopped = false
        private set

    private val pairingSessions = ConcurrentHashMap<String, PairingSession>()

    fun startServer(): Boolean = try {
        serverSocket = if (tls) createTlsServerSocket() else ServerSocket(serverPort)
        start()
        ServerCore.log("${if (tls) "HTTPS" else "HTTP"} 服务已启动，端口 $serverPort")
        true
    } catch (e: Exception) {
        ServerCore.log("${if (tls) "HTTPS" else "HTTP"} 服务启动失败: ${e.message}")
        false
    }

    /** 复用配对证书提供 TLS（官方客户端仅要求服务端证书，不校验 CA） */
    private fun createTlsServerSocket(): ServerSocket? {
        val cert = ServerCore.cert ?: return null
        val key = ServerCore.privateKey ?: return null
        val password = "sunshine-server".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null)
        keyStore.setKeyEntry("server", key, password, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(kmf.keyManagers, null, null)
        return context.serverSocketFactory.createServerSocket(serverPort)
    }

    fun stopServer() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    override fun run() {
        val socket = serverSocket ?: return
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        while (!stopped) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                if (stopped) break
                continue
            }
            // 每连接一线程：getservercert 会挂起等待用户输入 PIN（最长 120s），
            // 不能阻塞 accept 循环，否则 Moonlight 配对期间的状态轮询全部超时
            executor.execute {
                try {
                    handle(client)
                } catch (e: Exception) {
                    ServerCore.log("HTTP 处理异常: ${e.message}")
                } finally {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) return
        val method = parts[0]
        if (method != "GET") {
            respond(client, 404, "Not Found", xmlError(404))
            return
        }
        val url = parts[1]
        val pathEnd = url.indexOf('?')
        val path = if (pathEnd >= 0) url.substring(0, pathEnd) else url
        val args = parseQuery(if (pathEnd >= 0) url.substring(pathEnd + 1) else "")

        // 读掉剩余 header
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        // 会话/局域网地址必须用这个连接自己绑定的地址：
        // 本机可能同时有 Wi-Fi + 蜂窝多个 IPv4，按 NetworkInterface 枚举出来的第一个
        // 不一定是客户端正在用的那个，回给它的 rtsp://IP 就会连不上。
        val peer = try {
            client.inetAddress
        } catch (_: Exception) {
            null
        }
        val localIp = pickLocalIpFor(client, peer)
        noteRequest(client, path)

        val uniqueId = args["uniqueid"]
        when (path) {
            "/serverinfo" -> respond(client, 200, "OK", serverInfoXml(uniqueId, localIp))
            // Moonlight v7/官方客户端走 /pair（Sunshine 同款）；/pairing.html 为 GFE 旧路径兼容
            "/pair", "/pairing.html" -> respond(client, 200, "OK", handlePairing(args))
            "/unpair" -> {
                val id = uniqueId ?: ""
                if (id.isNotBlank()) ServerCore.removePairedClient(id)
                if (id.isNotBlank()) pairingSessions.remove(id)
                ServerCore.log("客户端解除配对：${if (id.isBlank()) "未知设备" else id}")
                respond(client, 200, "OK", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root status_code=\"200\"></root>")
            }
            "/applist", "/applist.xml" -> respond(client, 200, "OK", appListXml())
            // Moonlight 会拉 /appasset 取应用封面，404 会让部分客户端把整条记录判为异常
            "/appasset" -> {
                val png = boxArtPng(args["appid"] ?: "1")
                if (png.isEmpty()) respond(client, 404, "Not Found", xmlError(404))
                else respondBytes(client, 200, "OK", "image/png", png)
            }
            "/launch" -> handleLaunch(client, args, localIp, peer)
            "/cancel" -> {
                ServerCore.session?.stop()
                respond(client, 200, "OK", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root status_code=\"200\"></root>")
            }
            "/resume" -> respond(client, 200, "OK", resumeXml(localIp))
            "/servererror" -> respond(client, 200, "OK", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root status_code=\"200\"></root>")
            // 调试端点：导出应用内存日志（荣耀等系统屏蔽 logcat 时用）
            "/logs" -> respond(client, 200, "OK", ServerCore.snapshotLogs().joinToString("\n"))
            else -> respond(client, 404, "Not Found", xmlError(404))
        }
    }

    private val requestLogMs = ConcurrentHashMap<String, Long>()

    /**
     * 每个端点 1 条/3s 的访问日志。
     * 客户端连不上时，先看这里就能区分"请求根本没到"还是"到了但返回不对"。
     */
    private fun noteRequest(client: Socket, path: String) {
        val now = System.currentTimeMillis()
        val last = requestLogMs[path] ?: 0L
        if (now - last < 3_000) return
        requestLogMs[path] = now
        val remote = try {
            (client.remoteSocketAddress as? java.net.InetSocketAddress)?.let {
                "${it.address?.hostAddress}:${it.port}"
            } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        ServerCore.log("HTTP $path ← $remote")
    }

    /**
     * 挑一个"客户端确实连得上"的本机 IPv4。
     *
     * 手机可能同时挂着蜂窝 + Wi-Fi 两条网（本机就同时有 10.32.x 和 10.167.x），
     * 枚举出的第一个地址往往不是客户端那条链路，回给它的 rtsp://sessionUrl 就废了。
     * 优先级：连接自身绑定的地址（且与客户端同网段）> 任意与客户端同网段的本机地址 >
     * 连接自身绑定的地址 > 枚举第一个。
     */
    private fun pickLocalIpFor(client: Socket, peer: java.net.InetAddress?): String {
        val bound = toIpv4(safeLocalAddress(client))
        val peerV4 = peer as? java.net.Inet4Address
        if (peerV4 != null && bound != null && sameSubnet(peerV4, bound)) {
            return bound.hostAddress ?: firstIp()
        }
        if (peerV4 != null) {
            sameSubnetAddress(peerV4)?.let { return it }
        }
        if (bound != null && !bound.isLoopbackAddress && !bound.isAnyLocalAddress) {
            return bound.hostAddress ?: firstIp()
        }
        return firstIp()
    }

    private fun safeLocalAddress(client: Socket): java.net.InetAddress? = try {
        client.localAddress
    } catch (_: Exception) {
        null
    }

    private fun toIpv4(addr: java.net.InetAddress?): java.net.Inet4Address? = when (addr) {
        is java.net.Inet4Address -> addr
        is java.net.Inet6Address -> try {
            val b = addr.address
            val v4Mapped = b.size == 16 &&
                b[0].toInt() == 0 && b[1].toInt() == 0 &&
                b[2].toInt() == 0 && b[3].toInt() == 0 &&
                b[4].toInt() == 0 && b[5].toInt() == 0 &&
                b[6].toInt() == 0 && b[7].toInt() == 0 &&
                b[8].toInt() == 0 && b[9].toInt() == 0 &&
                b[10].toInt() == -1 && b[11].toInt() == -1
            if (v4Mapped) {
                InetAddress.getByAddress(b.copyOfRange(12, 16)) as? java.net.Inet4Address
            } else null
        } catch (_: Exception) {
            null
        }
        else -> null
    }

    private fun sameSubnet(a: java.net.Inet4Address, b: java.net.Inet4Address): Boolean =
        matchesPrefix(a.address, b.address, subnetPrefix(b))

    private fun sameSubnetAddress(peer: java.net.Inet4Address): String? {
        val peerBytes = peer.address
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? java.net.Inet4Address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix in 1..32 && matchesPrefix(peerBytes, addr.address, prefix)) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 用 peer 自己所在网卡的掩码判断（拿不到就退回 /24 家用网段） */
    private fun subnetPrefix(peer: java.net.Inet4Address): Int {
        try {
            val ni = NetworkInterface.getByInetAddress(peer) ?: return DEFAULT_PREFIX
            val prefix = ni.interfaceAddresses
                .firstOrNull { it.address is java.net.Inet4Address }
                ?.networkPrefixLength?.toInt()
            if (prefix != null && prefix in 1..32) return prefix
        } catch (_: Exception) {
        }
        return DEFAULT_PREFIX
    }

    private fun matchesPrefix(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        if (a.size != 4 || b.size != 4) return false
        val fullBytes = prefix / 8
        for (i in 0 until fullBytes) if (a[i] != b[i]) return false
        val restBits = prefix % 8
        if (restBits == 0) return true
        val mask = (0xFF shl (8 - restBits)) and 0xFF
        return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
    }

    private fun firstIp(): String = ServerCore.localIps().firstOrNull() ?: "0.0.0.0"

    // ---------------- 各端点 ----------------

    private fun serverInfoXml(uniqueId: String?, localIp: String): String {
        val paired = if (ServerCore.isPaired(uniqueId)) 1 else 0
        // state：Sunshine 约定 SUNSHINE_SERVER_BUSY / SUNSHINE_SERVER_FREE
        val state = if (ServerCore.session?.isRunning == true) "SUNSHINE_SERVER_BUSY" else "SUNSHINE_SERVER_FREE"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<root status_code=\"200\">\n" +
            "  <hostname>${ServerCore.hostName}</hostname>\n" +
            // appversion 必须首段 >= 7（与 Sunshine 一致），否则客户端按 Gen<7 走 SHA-1 派生密钥导致配对必败
            "  <appversion>7.1.431.-1</appversion>\n" +
            "  <protocolversion>7</protocolversion>\n" +
            "  <GfeVersion>3.23.0.74</GfeVersion>\n" +
            "  <uniqueid>${ServerCore.uniqueId}</uniqueid>\n" +
            "  <HttpsPort>$HTTPS_PORT</HttpsPort>\n" +
            "  <ExternalPort>$PORT</ExternalPort>\n" +
            "  <HTTPPort>$PORT</HTTPPort>\n" +
            "  <LocalIP>$localIp</LocalIP>\n" +
            // Sunshine 对明文请求固定回占位 MAC（客户端见到全 0 会忽略），缺失会让部分客户端丢主机
            "  <mac>00:00:00:00:00:00</mac>\n" +
            // 只编码 H.264（SCM_H264 = 0x1），缺失时客户端按 0 处理但鸿蒙分支会读它
            "  <ServerCodecModeSupport>1</ServerCodecModeSupport>\n" +
            "  <MaxLumaPixelsHEVC>0</MaxLumaPixelsHEVC>\n" +
            "  <MaxLGPixels>0</MaxLGPixels>\n" +
            "  <OutputDebugOptions>0</OutputDebugOptions>\n" +
            // 官方客户端读取 PairStatus（而非 paired），字段名错误会导致配对成功后仍显示未配对
            "  <PairStatus>$paired</PairStatus>\n" +
            "  <currentgame>0</currentgame>\n" +
            "  <state>$state</state>\n" +
            "</root>"
    }

    private fun handlePairing(args: Map<String, String>): String {
        val uniqueId = args["uniqueid"] ?: return xmlError(400)
        val phrase = args["phrase"]

        // pairchallenge：客户端校验是否已配对
        if (phrase == "pairchallenge") {
            val paired = ServerCore.isPaired(uniqueId)
            return pairingXml(paired, null)
        }

        val session = pairingSessions.computeIfAbsent(uniqueId) { PairingSession() }

        // getservercert 开启新配对流程：挂起等待用户在界面输入 Moonlight 显示的 PIN
        // （salt/clientcert 仅此阶段携带；clientchallenge 等后续阶段不带 salt）
        if (phrase == "getservercert") {
            val salt = args["salt"] ?: return xmlError(400)
            val clientCert = args["clientcert"] ?: ""
            session.reset(salt, uniqueId, clientCert)
            val deviceName = args["devicename"] ?: uniqueId
            val pin = ServerCore.awaitPin(deviceName, PIN_WAIT_TIMEOUT_MS)
            if (pin == null) {
                ServerCore.log("配对超时或被取消：$deviceName")
                return xmlError(408)
            }
            return try {
                val plainCert = session.getServerCert(pin)
                pairingXml(true, plainCert)
            } catch (e: Exception) {
                ServerCore.log("getservercert 失败: ${e.message}")
                pairingXml(false, null)
            }
        }

        // 其余阶段沿用现有配对会话
        return try {
            when {
                args.containsKey("clientchallenge") -> {
                    val response = session.clientChallenge(args["clientchallenge"]!!)
                    ServerCore.log("配对阶段 2/4 完成（clientchallenge）")
                    pairingXml(true, null, challengeResponse = response)
                }
                args.containsKey("serverchallengeresp") -> {
                    val secret = session.serverChallengeResp(args["serverchallengeresp"]!!)
                    ServerCore.log("配对阶段 3/4 完成（serverchallengeresp）")
                    pairingXml(true, null, pairingSecret = secret)
                }
                args.containsKey("clientpairingsecret") -> {
                    val ok = session.clientPairingSecret(args["clientpairingsecret"]!!)
                    if (ok) {
                        ServerCore.addPairedClient(uniqueId)
                        pairingSessions.remove(uniqueId)
                        ServerCore.log("配对成功：$uniqueId")
                    } else {
                        ServerCore.log("配对校验失败：$uniqueId（通常是 PIN 输错，请在客户端重新发起配对并输入新 PIN）")
                    }
                    pairingXml(ok, null)
                }
                else -> pairingXml(false, null)
            }
        } catch (e: Exception) {
            ServerCore.log("配对阶段失败: ${e.message}")
            pairingXml(false, null)
        }
    }

    private fun pairingXml(
        paired: Boolean,
        plainCert: String?,
        challengeResponse: String? = null,
        pairingSecret: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root status_code=\"200\">\n")
        if (plainCert != null) sb.append("  <plaincert>$plainCert</plaincert>\n")
        if (challengeResponse != null) sb.append("  <challengeresponse>$challengeResponse</challengeresponse>\n")
        if (pairingSecret != null) sb.append("  <pairingsecret>$pairingSecret</pairingsecret>\n")
        sb.append("  <paired>").append(if (paired) 1 else 0).append("</paired>\n</root>")
        return sb.toString()
    }

    private fun appListXml(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<root status_code=\"200\">\n" +
        "  <App>\n" +
        "    <IsHdrSupported>0</IsHdrSupported>\n" +
        "    <AppTitle>桌面</AppTitle>\n" +
        "    <ID>1</ID>\n" +
        "    <ShortName>Desktop</ShortName>\n" +
        "  </App>\n" +
        "</root>"

    private fun handleLaunch(
        client: Socket,
        args: Map<String, String>,
        localIp: String,
        peer: java.net.InetAddress?
    ) {
        val existing = ServerCore.session
        if (existing != null && existing.isRunning) {
            val age = System.currentTimeMillis() - existing.startedAtMs
            if (existing.hasConnectedClient || age < StreamSession.SESSION_CONNECT_TIMEOUT_MS) {
                respond(client, 200, "OK", launchXml(localIp, resumed = true))
                return
            }
            // 僵尸会话：客户端从未接入控制通道且已超时 → 清理后继续创建新会话
            // stop() 同步清 ServerCore.session（授权闸门已移除，投影由常驻 VD 复用）
            ServerCore.log("发现僵尸会话（${age / 1000}s 无控制通道），清理并重新启动")
            existing.stop()
        }
        val config = StreamConfig.fromLaunchArgs(args)
        val listener = launchListener ?: run {
            respond(client, 500, "Internal Server Error", xmlError(500))
            return
        }
        val session = listener.onLaunchRequested(config)
        if (session == null) {
            respond(client, 500, "Internal Server Error", xmlError(500))
            return
        }
        ServerCore.log(
            "客户端启动会话：${config.width}x${config.height}@${config.fps}，目标 $localIp" +
                "（socket local=${safeLocalAddress(client)}，peer=$peer）"
        )
        respond(client, 200, "OK", launchXml(localIp, resumed = false))
    }

    private fun launchXml(ip: String, resumed: Boolean): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<root status_code=\"200\">\n" +
        "  <sessionUrl0>rtsp://$ip:${RtspServer.PORT}</sessionUrl0>\n" +
        "  <gamesession>1</gamesession>\n" +
        "  <resume>" + (if (resumed) 1 else 0) + "</resume>\n</root>"

    private fun resumeXml(localIp: String): String {
        val session = ServerCore.session
        val resumable = session != null && session.isRunning &&
            (session.hasConnectedClient ||
                System.currentTimeMillis() - session.startedAtMs < StreamSession.SESSION_CONNECT_TIMEOUT_MS)
        val resume = if (resumable) 1 else 0
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<root status_code=\"200\">\n" +
            "  <sessionUrl0>rtsp://$localIp:${RtspServer.PORT}</sessionUrl0>\n" +
            "  <resume>$resume</resume>\n" +
            "  <gamesession>1</gamesession>\n" +
            "</root>"
    }

    private fun xmlError(code: Int): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root status_code=\"$code\"></root>"

    // ---------------- 基础工具 ----------------

    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx == -1) {
                map[URLDecoder.decode(pair, "UTF-8")] = ""
            } else {
                map[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
        return map
    }

    private fun respond(client: Socket, code: Int, message: String, body: String) {
        respondBytes(client, code, message, "text/html", body.toByteArray(Charsets.UTF_8))
    }

    private fun respondBytes(
        client: Socket,
        code: Int,
        message: String,
        contentType: String,
        bytes: ByteArray
    ) {
        val header = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    /** 程序生成的 640x360 应用封面（纯色 + 标题），避免 /appasset 返回 404 */
    private fun boxArtPng(appId: String): ByteArray = try {
        val w = 640
        val h = 360
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(0x1E, 0x2A, 0x3A))
        val accent = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x4C, 0x8D, 0xFF)
        }
        canvas.drawRoundRect(
            android.graphics.RectF(48f, 48f, w - 48f, h - 48f), 28f, 28f, accent
        )
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 64f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("Sunshine", w / 2f, h / 2f, textPaint)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        ByteArray(0)
    }

    interface LaunchListener {
        /** 返回创建的会话；无法创建（无投屏授权等）返回 null */
        fun onLaunchRequested(config: StreamConfig): com.wxz.sunshineserverandroid.stream.StreamSession?
    }

    companion object {
        const val PORT = 47989
        const val HTTPS_PORT = 47984

        /** 等待用户输入 PIN 的上限（对齐 Sunshine 5 分钟会话超时，取保守值） */
        private const val PIN_WAIT_TIMEOUT_MS = 120_000L

        /** 拿不到网卡掩码时按家用 /24 兜底 */
        private const val DEFAULT_PREFIX = 24

        @Volatile
        var launchListener: LaunchListener? = null
    }
}
