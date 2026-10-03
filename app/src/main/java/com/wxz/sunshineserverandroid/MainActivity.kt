package com.wxz.sunshineserverandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.wxz.sunshineserverandroid.input.InputService
import com.wxz.sunshineserverandroid.ui.PageScrollColumn
import com.wxz.sunshineserverandroid.ui.SettingsItem
import com.wxz.sunshineserverandroid.ui.SettingsSection
import com.wxz.sunshineserverandroid.ui.applyImmersiveSystemBars
import com.wxz.sunshineserverandroid.ui.rememberPageTick
import com.wxz.sunshineserverandroid.ui.theme.SunshineServerAndroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        applyImmersiveSystemBars()
        ServerCore.init(applicationContext)
        setContent {
            SunshineServerAndroidTheme {
                MainPage()
            }
        }
        scheduleDebugInput(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scheduleDebugInput(intent)
    }

    /**
     * 手势自测入口：不用接客户端，直接注入合成触摸。
     * adb shell am start -n com.wxz.sunshineserverandroid/.MainActivity \
     *   --es dbg "swipe:700,1400,700,700,600"
     */
    private fun scheduleDebugInput(intent: Intent?) {
        val spec = intent?.getStringExtra("dbg") ?: return
        window.decorView.postDelayed({
            com.wxz.sunshineserverandroid.input.InputDispatcher.runDebug(spec)
        }, 500)
    }
}

/** 主页：标题栏「浏海阳光」+ 右上角 日志、设置（各自跳独立 Activity） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage() {
    val context = LocalContext.current
    val tick = rememberPageTick()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("浏海阳光") },
                actions = {
                    IconButton(onClick = { context.startActivity(Intent(context, LogActivity::class.java)) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "日志")
                    }
                    IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        MainContent(padding, tick)
    }
}

/** 主页正文：服务状态 / 连接信息 / 配对 */
@Composable
private fun MainContent(
    padding: PaddingValues,
    tick: Int
) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(ServerCore.running) }
    var waitingProjection by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { running = ServerCore.running }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        waitingProjection = false
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val intent = Intent(context, ServerService::class.java)
                .putExtra(ServerService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ServerService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
            running = true
        } else {
            Toast.makeText(context, "未授予屏幕采集权限，无法启动服务", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 14+ 可禁用投屏对话框里的"单个应用"选项：串流镜像的是整个屏幕，
    // 单应用模式会导致虚拟屏拿不到完整画面
    fun launchProjectionIntent() {
        val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val captureIntent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.POST_NOTIFICATIONS] == true) {
            waitingProjection = true
            launchProjectionIntent()
        } else {
            Toast.makeText(context, "缺少通知权限，前台服务无法运行", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestStartupPermissions() {
        val wanted = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // Android 17 LNP（ColorOS 已对 targetSdk 37 提前强制）：局域网访问（含 Moonlight 入站连接）
        if (android.os.Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
        }
        if (wanted.isEmpty()) {
            waitingProjection = true
            launchProjectionIntent()
        } else {
            notificationLauncher.launch(wanted.toTypedArray())
        }
    }

    PageScrollColumn(padding) {
        SettingsSection("Sunshine") {
            Text(
                if (running) "服务运行中" else "服务未启动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (running) {
                        context.startService(
                            Intent(context, ServerService::class.java)
                                .setAction(ServerService.ACTION_STOP)
                        )
                        running = false
                    } else {
                        requestStartupPermissions()
                    }
                },
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        running -> "停止服务"
                        waitingProjection -> "等待授权…"
                        else -> "启动服务"
                    }
                )
            }
            if (!InputService.isEnabled()) {
                SettingsItem(
                    icon = Icons.Filled.Accessibility,
                    title = "无障碍触控服务未开启",
                    subtitle = "远程控制（触摸/鼠标/按键注入）需要它，点击前往设置",
                    onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }
                )
            }
        }

        if (running) {
            SettingsSection("连接") {
                val ips = remember(tick) { ServerCore.localIps() }
                if (ips.isEmpty()) {
                    Text("未检测到局域网地址，请连接 Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for ((index, ip) in ips.withIndex()) {
                        Text(
                            if (index == 0) "地址：$ip（客户端填这个）" else "地址：$ip",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                Text(
                    "端口：HTTP ${com.wxz.sunshineserverandroid.net.NvHttpServer.PORT} · " +
                        "RTSP ${com.wxz.sunshineserverandroid.net.RtspServer.PORT} · " +
                        "视频 47998 · 控制 47999 · 音频 48000",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSection("配对") {
                val awaiting = remember(tick) { ServerCore.awaitingPin }
                if (awaiting) {
                    val device = remember(tick) { ServerCore.pendingPairDevice }
                    Text(
                        "收到配对请求：$device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "请输入 Moonlight 客户端上显示的 PIN",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    var pinInput by remember(awaiting) { mutableStateOf("") }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pinInput = it },
                            label = { Text("PIN") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (pinInput.length == 4) {
                                    ServerCore.submitPin(pinInput)
                                    Toast.makeText(context, "已提交 PIN：$pinInput", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = pinInput.length == 4
                        ) {
                            Text("确认")
                        }
                    }
                } else {
                    Text(
                        "在 Moonlight 客户端添加本主机并发起配对时，客户端会显示一个 4 位 PIN，届时在此输入以完成配对",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
