package com.wxz.sunshineserverandroid.net

import android.content.Context
import android.os.Build
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.wxz.sunshineserverandroid.ServerCore

/**
 * mDNS 服务发现广播：注册 `_nvstream._tcp` 指向 HTTP 47989，
 * 官方 Moonlight 客户端靠它自动发现主机（Sunshine 同款，无 TXT 记录）。
 */
class MdnsPublisher(private val context: Context) {

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { register() }
    private val pendingTimeoutRunnable = Runnable { onRegistrationPendingTimeout() }
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var started = false
    private var registered = false
    private var retryDelayMs = INITIAL_RETRY_MS

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }

        acquireMulticastLock()
        // 不监听网络变化：ColorOS/Android 的默认网络在 Wi-Fi/蜂窝间周期性切换，
        // onLost→撤销→重注册会造成 mDNS 服务反复 Add/Rmv（客户端看到主机闪现消失）。
        // 接口变化由系统 mdnsd 自行处理，这里只负责注册 + 失败退避重试。
        register()
    }

    private fun register() {
        synchronized(lock) {
            if (!started || registered || listener != null) return
        }

        val manager = try {
            context.getSystemService(Context.NSD_SERVICE) as NsdManager
        } catch (e: Exception) {
            scheduleRetry("获取 NsdManager 失败: ${e.message}")
            return
        }
        val info = NsdServiceInfo().apply {
            serviceName = ServerCore.hostName.take(MAX_SERVICE_NAME_LENGTH).ifBlank { "Android" }
            serviceType = SERVICE_TYPE
            setPort(NvHttpServer.PORT)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                handler.removeCallbacks(pendingTimeoutRunnable)
                synchronized(lock) {
                    // 陈旧实例（已超时清理或已 stop）：尽力注销，避免孤儿注册残留
                    if (listener !== this) {
                        try {
                            nsdManager?.unregisterService(this)
                        } catch (_: Exception) {
                        }
                        return
                    }
                    registered = true
                    retryDelayMs = INITIAL_RETRY_MS
                }
                ServerCore.log("mDNS 服务已注册：${serviceInfo.serviceName} $SERVICE_TYPE:${NvHttpServer.PORT}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.removeCallbacks(pendingTimeoutRunnable)
                synchronized(lock) {
                    if (listener !== this) return
                    listener = null
                    registered = false
                }
                scheduleRetry("mDNS 注册失败：errorCode=$errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.removeCallbacks(pendingTimeoutRunnable)
                synchronized(lock) {
                    if (listener !== this) return
                    listener = null
                    registered = false
                }
                scheduleRetry("mDNS 注销失败：errorCode=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                handler.removeCallbacks(pendingTimeoutRunnable)
                synchronized(lock) {
                    if (listener !== this) return
                    listener = null
                    registered = false
                }
                if (started) scheduleRetry("mDNS 服务已注销，准备重新注册")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, context.mainExecutor, l)
            } else {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            }
            synchronized(lock) {
                nsdManager = manager
                listener = l
            }
            // 回调可能既不成功也不失败地永不到达（系统侧挂起），10s 兜底避免 listener 永久卡死重试
            handler.postDelayed(pendingTimeoutRunnable, PENDING_TIMEOUT_MS)
        } catch (e: Exception) {
            scheduleRetry("mDNS 注册异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 注册回调 10s 未到达：清掉 listener 让 register() 可重新发起，并尽力注销半注册状态 */
    private fun onRegistrationPendingTimeout() {
        val manager: NsdManager?
        val stale: NsdManager.RegistrationListener?
        synchronized(lock) {
            if (!started || registered || listener == null) return
            manager = nsdManager
            stale = listener
            listener = null
            nsdManager = null
        }
        if (manager != null && stale != null) {
            try {
                manager.unregisterService(stale)
            } catch (_: Exception) {
            }
        }
        scheduleRetry("mDNS 注册回调超时（${PENDING_TIMEOUT_MS}ms 无响应）")
    }

    fun stop() {
        val manager: NsdManager?
        val l: NsdManager.RegistrationListener?
        synchronized(lock) {
            if (!started) return
            started = false
            registered = false
            manager = nsdManager
            l = listener
            listener = null
            nsdManager = null
        }
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(pendingTimeoutRunnable)
        if (manager != null && l != null) {
            try {
                manager.unregisterService(l)
            } catch (_: Exception) {
            }
        }
        multicastLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {
            }
        }
        multicastLock = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi.createMulticastLock("sunshine-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            ServerCore.log("获取 mDNS 多播锁失败: ${e.message}")
        }
    }

    private fun scheduleRetry(reason: String) {
        val delay = synchronized(lock) {
            if (!started) return
            val current = retryDelayMs
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            current
        }
        ServerCore.log("$reason，${delay}ms 后重试 mDNS")
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    companion object {
        const val SERVICE_TYPE = "_nvstream._tcp"
        private const val MAX_SERVICE_NAME_LENGTH = 63
        private const val INITIAL_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val PENDING_TIMEOUT_MS = 10_000L
    }
}
