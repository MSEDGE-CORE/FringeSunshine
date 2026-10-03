package com.wxz.sunshineserverandroid

import android.content.Context
import android.os.Build
import android.content.SharedPreferences
import java.net.NetworkInterface
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import com.wxz.sunshineserverandroid.net.MoonCrypto

/**
 * 服务端全局状态：证书/私钥、唯一 ID、PIN、已配对客户端、当前流会话、日志。
 */
object ServerCore {
    private const val PREFS = "sunshine_state"
    private const val KEY_UNIQUE_ID = "unique_id"
    private const val KEY_PAIRED = "paired_clients"
    private const val KEY_HOST_NAME = "host_name"
    private const val KEY_CURSOR_ENABLED = "cursor_enabled"

    lateinit var appContext: Context
        private set

    var certPem: String = ""
        private set
    var keyPem: String = ""
        private set

    var cert: X509Certificate? = null
        private set
    var privateKey: PrivateKey? = null
        private set

    /** 服务器唯一 ID（16 进制大写，与 Moonlight 客户端显示的 Server ID 对应） */
    var uniqueId: String = ""
        private set

    /** Moonlight 客户端显示的主机名（serverinfo hostname / mDNS 服务名） */
    @Volatile
    var hostName: String = "Android"
        private set

    /** 修改显示名：serverinfo 立即生效；mDNS 广播名需重启服务后生效 */
    fun setHostName(name: String) {
        val trimmed = name.trim().ifEmpty { defaultHostName() }
        hostName = trimmed
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST_NAME, trimmed).apply()
        log("显示名已设置为：$trimmed（mDNS 广播名重启服务后生效）")
    }

    /** 鼠标模式是否显示可见光标（设置页手动开关） */
    @Volatile
    var cursorEnabled: Boolean = true
        private set

    fun setCursorEnabled(on: Boolean) {
        cursorEnabled = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CURSOR_ENABLED, on).apply()
        log("光标显示已${if (on) "开启" else "关闭"}")
    }

    /** 默认显示名：设备型号（保证两台手机同时广播时 mDNS 服务名不冲突） */
    fun defaultHostName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    /** 用户在界面输入的配对 PIN（Moonlight 客户端显示、由用户抄录到本机） */
    @Volatile
    var pin: String = ""
        private set

    /** 是否有配对请求正在等待用户输入 PIN */
    @Volatile
    var awaitingPin = false
        private set

    /** 等待输入 PIN 的配对请求来源设备名 */
    @Volatile
    var pendingPairDevice = ""
        private set

    /** 已配对客户端 uniqueid 集合 */
    val pairedClients = LinkedHashSet<String>()

    /** 服务器是否已启动（HTTP/RTSP 监听中） */
    @Volatile
    var running = false

    /** 当前流会话 */
    @Volatile
    var session: com.wxz.sunshineserverandroid.stream.StreamSession? = null

    /** 服务级常驻虚拟屏（Android 14+ 单次 createVirtualDisplay 限制，会话间复用） */
    @Volatile
    var projectionDisplay: com.wxz.sunshineserverandroid.stream.ProjectionDisplay? = null

    private val logLock = Any()
    private val logBuffer = ArrayDeque<String>()
    /**
     * 日志观察者列表：主界面、日志页等各自注册/注销，互不覆盖
     * （原来是单个 onLog 回调，页面一多后进入日志页会把主界面的刷新回调顶掉）
     */
    private val logListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addLogListener(listener: () -> Unit) {
        logListeners.add(listener)
    }

    fun removeLogListener(listener: () -> Unit) {
        logListeners.remove(listener)
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        certPem = appContext.assets.open("sunshine_cert.pem").bufferedReader().readText()
        keyPem = appContext.assets.open("sunshine_key.pem").bufferedReader().readText()
        cert = MoonCrypto.parseCert(certPem)
        privateKey = MoonCrypto.parsePrivateKey(keyPem)

        uniqueId = prefs.getString(KEY_UNIQUE_ID, null) ?: run {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            MoonCrypto.bytesToHex(bytes).also { prefs.edit().putString(KEY_UNIQUE_ID, it).apply() }
        }
        prefs.getString(KEY_PAIRED, "")?.split(",")?.filter { it.isNotBlank() }?.let { pairedClients.addAll(it) }
        hostName = prefs.getString(KEY_HOST_NAME, null) ?: defaultHostName()
        cursorEnabled = prefs.getBoolean(KEY_CURSOR_ENABLED, true)
    }

    /**
     * 配对请求到达（getservercert）：挂起 HTTP 线程，等待用户在界面输入
     * Moonlight 客户端显示的 PIN（与 Sunshine Web UI 输入 PIN 行为一致）。
     * 返回用户输入的 PIN；超时/被新请求取代/被取消返回 null。
     */
    fun awaitPin(deviceName: String, timeoutMs: Long): String? {
        pin = ""
        pendingPairDevice = deviceName
        awaitingPin = true
        activePinLatch.get().countDown() // 取代可能还在等待的旧配对请求
        val latch = java.util.concurrent.CountDownLatch(1)
        activePinLatch.set(latch)
        log("收到配对请求（$deviceName），请在界面输入 Moonlight 显示的 PIN")
        val got = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        awaitingPin = false
        pendingPairDevice = ""
        return if (got && pin.isNotBlank()) pin else null
    }

    /** 用户提交 PIN，唤醒等待中的配对请求 */
    fun submitPin(input: String) {
        pin = input
        activePinLatch.get().countDown()
    }

    private val activePinLatch = java.util.concurrent.atomic.AtomicReference(
        java.util.concurrent.CountDownLatch(1)
    )

    fun addPairedClient(uniqueId: String) {
        synchronized(pairedClients) {
            pairedClients.add(uniqueId)
            persistPaired()
        }
    }

    fun removePairedClient(uniqueId: String) {
        synchronized(pairedClients) {
            pairedClients.remove(uniqueId)
            persistPaired()
        }
    }

    fun isPaired(uniqueId: String?): Boolean =
        uniqueId != null && synchronized(pairedClients) { pairedClients.contains(uniqueId) }

    fun pairedClientSnapshot(): List<String> = synchronized(pairedClients) { pairedClients.toList() }

    private fun persistPaired() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRED, pairedClients.joinToString(","))
            .apply()
    }

    /**
     * 本机 IPv4 地址列表。
     * 枚举顺序通常是 rmnet(蜂窝)/tun(VPN) 在 wlan 之前，直接展示会把客户端连不上的
     * 地址顶到最前面，所以按「是否真实局域网网卡」排序，wlan/eth/ap 排前，其余排后。
     */
    fun localIps(): List<String> = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                Collections.list(ni.inetAddresses).asSequence().map { ni.name to it }
            }
            .filter { (_, addr) -> addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
            .map { (name, addr) -> lanRank(name) to (addr.hostAddress ?: "") }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** wlan/eth/ap 才是客户端能连上的局域网；rmnet=蜂窝，tun/vgate/wg=VPN */
    private fun lanRank(ifaceName: String): Int {
        val n = ifaceName.lowercase()
        return if (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap") ||
            n.startsWith("swlan") || n.startsWith("rndis")
        ) 0 else 1
    }

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$time] $message"
        android.util.Log.println(android.util.Log.INFO, "SunshineServer", message)
        synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > 200) logBuffer.removeFirst()
        }
        for (listener in logListeners) listener()
    }

    fun snapshotLogs(): List<String> = synchronized(logLock) { logBuffer.toList() }
}
