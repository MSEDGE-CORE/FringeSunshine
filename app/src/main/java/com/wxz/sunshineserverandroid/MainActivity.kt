package com.wxz.sunshineserverandroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wxz.sunshineserverandroid.input.InputService
import com.wxz.sunshineserverandroid.ui.theme.SunshineServerAndroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MainPage() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(ServerCore.running) }
    var tick by remember { mutableIntStateOf(0) }
    var waitingProjection by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ServerCore.onLog = { tick++ }
        // 从设置页返回时没有日志事件，周期刷新以便状态按钮及时更新
        while (true) {
            kotlinx.coroutines.delay(2_000)
            tick++
        }
    }

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

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.POST_NOTIFICATIONS] == true) {
            waitingProjection = true
            val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
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
            val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            notificationLauncher.launch(wanted.toTypedArray())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("投屏服务端") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (running) "服务运行中" else "服务未启动",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Moonlight 协议（Sunshine 兼容子集）", style = MaterialTheme.typography.bodySmall)
                    if (!running) {
                        Text(
                            "启动后可在同一局域网的 Moonlight 客户端（或自研鸿蒙客户端）中添加本机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 显示名设置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("显示名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Moonlight 客户端列表中显示的主机名。serverinfo 立即生效；mDNS 广播名需重启服务后生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var nameInput by remember { mutableStateOf(ServerCore.hostName) }
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = nameInput,
                            onValueChange = { if (it.length <= 63) nameInput = it },
                            label = { Text("主机名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                ServerCore.setHostName(nameInput)
                                Toast.makeText(context, "显示名已保存：${ServerCore.hostName}", Toast.LENGTH_SHORT).show()
                            },
                            enabled = nameInput.isNotBlank() && nameInput.trim() != ServerCore.hostName
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            // 连接信息
            if (running) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("连接信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("配对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.OutlinedTextField(
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

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val paired = remember(tick) { ServerCore.pairedClientSnapshot() }
                        Text(
                            "已配对设备（${paired.size}）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (paired.isEmpty()) {
                            Text(
                                "尚未配对 Moonlight 设备",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            for (clientId in paired) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Moonlight 设备", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            clientId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            ServerCore.removePairedClient(clientId)
                                            tick++
                                        }
                                    ) {
                                        Text("解除")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 按钮
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!running) {
                    Button(
                        onClick = { requestStartupPermissions() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (waitingProjection) "等待授权…" else "启动服务")
                    }
                } else {
                    Button(
                        onClick = {
                            context.startService(
                                Intent(context, ServerService::class.java)
                                    .setAction(ServerService.ACTION_STOP)
                            )
                            running = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("停止服务")
                    }
                }
            }

            // 无障碍开关
            val accessibilityOn = InputService.isEnabled()
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (accessibilityOn) "无障碍触控服务：已开启" else "开启无障碍触控服务（远程控制需要）")
            }

            // 鼠标模式的可见光标
            val canOverlay = android.provider.Settings.canDrawOverlays(context)
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName)
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (canOverlay) "鼠标光标显示：已授权" else "开启悬浮光标（鼠标模式显示指针）")
            }

            // 日志
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val logs = remember(tick) { ServerCore.snapshotLogs().takeLast(30) }
                    if (logs.isEmpty()) {
                        Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                    } else {
                        for (line in logs) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
