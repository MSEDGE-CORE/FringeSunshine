package com.wxz.sunshineserverandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.wxz.sunshineserverandroid.net.MdnsPublisher
import com.wxz.sunshineserverandroid.net.NvHttpServer
import com.wxz.sunshineserverandroid.net.RtspServer
import com.wxz.sunshineserverandroid.stream.ProjectionDisplay
import com.wxz.sunshineserverandroid.stream.StreamConfig
import com.wxz.sunshineserverandroid.stream.StreamSession

/**
 * 前台服务：持有 MediaProjection，运行 HTTP(47989) / RTSP(48010) 服务器，
 * 在 /launch 请求到达时创建流会话。
 */
class ServerService : Service(), NvHttpServer.LaunchListener {

    private var httpServer: NvHttpServer? = null
    private var httpsServer: NvHttpServer? = null
    private var rtspServer: RtspServer? = null
    private var mdnsPublisher: MdnsPublisher? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServerCore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        // intent 为 null：START_STICKY 自动重启（进程被系统清理后）。
        // MediaProjection 授权随进程死亡失效、无法找回，但 HTTP/RTSP/mDNS 可以先恢复，
        // 客户端至少能发现主机；串流需要用户回到应用重新授权。
        if (intent == null) {
            startAsForeground()
            startServers()
            ServerCore.running = true
            ServerCore.log("服务已从系统清理中自动恢复（网络服务已重启）；屏幕采集授权已失效，串流前请回到应用重新授权")
            logBatteryHint()
            return START_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            ServerCore.log("缺少投屏授权数据，服务无法启动")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        // 服务已在运行（自动恢复后用户重新授权）：只补绑投影，不重启网络服务
        if (ServerCore.running) {
            if (bindProjection(resultCode, resultData)) {
                ServerCore.log("已重新绑定屏幕采集授权，可以继续串流")
            }
            return START_STICKY
        }

        if (!bindProjection(resultCode, resultData)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startServers()
        ServerCore.running = true
        ServerCore.log("服务已启动，等待客户端连接")
        logBatteryHint()
        return START_STICKY
    }

    /** 绑定/重建 MediaProjection 与常驻虚拟屏；旧的失效资源会先释放 */
    private fun bindProjection(resultCode: Int, resultData: Intent): Boolean {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val old = projection
            projection = manager.getMediaProjection(resultCode, resultData)
            ServerCore.projectionDisplay?.release()
            ServerCore.projectionDisplay = ProjectionDisplay(projection!!)
            try {
                old?.stop()
            } catch (_: Exception) {
            }
            true
        } catch (e: Exception) {
            ServerCore.log("获取 MediaProjection 失败: ${e.message}")
            false
        }
    }

    /** 启动全部网络服务（HTTP/HTTPS/RTSP/mDNS） */
    private fun startServers() {
        NvHttpServer.launchListener = this
        // 常驻虚拟屏：整个服务生命周期只 createVirtualDisplay 一次（Android 14+ 强制），
        // 会话间用 setSurface/resize 切换，会话可无限次进入
        httpServer = NvHttpServer().also { it.startServer() }
        httpsServer = NvHttpServer(NvHttpServer.HTTPS_PORT, tls = true).also { it.startServer() }
        rtspServer = RtspServer().also { it.startServer() }
        mdnsPublisher = MdnsPublisher(this).also { it.start() }
    }

    /** 未加入电池优化白名单时提示：ColorOS 的内存清理会连可见前台服务一起杀 */
    private fun logBatteryHint() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            ServerCore.log("提示：应用未加入电池优化白名单，部分机型（OPPO/一加等）投屏中可能被系统清理杀掉；可在 设置→后台保活 处理")
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sunshine", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sunshine")
            .setContentText("运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            // 音频走 AudioPlaybackCapture（录系统播放），归 mediaProjection 类型；
            // microphone 类型 FGS 要求"正在使用麦克风的合法状态"，否则 startForeground 抛 SecurityException
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onLaunchRequested(config: StreamConfig): StreamSession? {
        if (ServerCore.projectionDisplay?.alive != true) {
            ServerCore.log("launch 被拒绝：投屏授权已失效，请停止服务后重新授权")
            return null
        }
        val mediaProjection = projection ?: run {
            ServerCore.log("launch 被拒绝：MediaProjection 为空（需在应用内重新授予屏幕采集并启动服务）")
            return null
        }
        val session = StreamSession(config, mediaProjection)
        if (!session.start()) {
            ServerCore.log("launch 被拒绝：流会话启动失败，详见上方错误")
            return null
        }
        ServerCore.session = session
        return session
    }

    private fun stopEverything() {
        ServerCore.session?.stop()
        mdnsPublisher?.stop(); mdnsPublisher = null
        httpServer?.stopServer(); httpServer = null
        httpsServer?.stopServer(); httpsServer = null
        rtspServer?.stopServer(); rtspServer = null
        ServerCore.projectionDisplay?.release()
        ServerCore.projectionDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        NvHttpServer.launchListener = null
        ServerCore.running = false
        ServerCore.log("服务已停止")
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.wxz.sunshineserverandroid.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "sunshine_server"
        private const val NOTIFICATION_ID = 100
    }
}
