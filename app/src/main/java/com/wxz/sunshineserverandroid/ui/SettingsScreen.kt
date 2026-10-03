package com.wxz.sunshineserverandroid.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wxz.sunshineserverandroid.ServerCore
import com.wxz.sunshineserverandroid.input.InputService

/**
 * 设置页（独立 Activity 承载）：显示名 / 已配对设备 / 无障碍 / 光标。
 * 版式与参照项目一致：扁平滚动布局 + 节标题小字 + 节间大间距，内容一直铺到系统导航条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberPageTick()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        PageScrollColumn(padding) {
            SettingsSection("显示名") {
                Text(
                    "Moonlight 客户端列表中显示的主机名。serverinfo 立即生效；mDNS 广播名需重启服务后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var nameInput by remember { mutableStateOf(ServerCore.hostName) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
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

            SettingsSection("已配对设备") {
                val paired = remember(tick) { ServerCore.pairedClientSnapshot() }
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
                            verticalAlignment = Alignment.CenterVertically
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
                                onClick = { ServerCore.removePairedClient(clientId) }
                            ) {
                                Text("解除")
                            }
                        }
                    }
                }
            }

            SettingsSection("远程控制") {
                val accessibilityOn = InputService.isEnabled()
                SettingsItem(
                    icon = Icons.Filled.Accessibility,
                    title = "无障碍触控服务",
                    subtitle = if (accessibilityOn) {
                        "已开启（触摸、鼠标、按键注入靠它）"
                    } else {
                        "未开启，点击前往系统设置"
                    },
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
                val canOverlay = Settings.canDrawOverlays(context)
                var cursorEnabled by remember { mutableStateOf(ServerCore.cursorEnabled) }
                SettingsSwitchItem(
                    icon = Icons.Filled.Mouse,
                    title = "光标",
                    subtitle = if (!canOverlay) {
                        "缺少「显示在其他应用上层」权限，开启后请授权"
                    } else {
                        "鼠标模式在画面里显示可见指针"
                    },
                    checked = cursorEnabled,
                    onCheckedChange = { on ->
                        cursorEnabled = on
                        ServerCore.setCursorEnabled(on)
                        if (on && !canOverlay) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + context.packageName)
                                )
                            )
                        }
                    }
                )
            }

            SettingsSection("后台保活") {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
                SettingsItem(
                    icon = Icons.Filled.BatteryFull,
                    title = "忽略电池优化",
                    subtitle = if (ignoringBattery) {
                        "已忽略，系统省电策略不会限制本应用"
                    } else {
                        "未忽略，点击授权（防止投屏中被系统断网、清理杀进程）"
                    },
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                )
                SettingsItem(
                    icon = Icons.Filled.Shield,
                    title = "厂商后台限制（ColorOS 等）",
                    subtitle = "OPPO/一加机型请在此处：应用信息 → 电池 → 允许完全后台行为；并在多任务界面下拉锁定本应用。否则投屏中可能被系统内存清理杀掉",
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                )
            }

            SettingsSection("关于") {
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
                } catch (_: Exception) {
                    "-"
                }
                Text("浏海阳光", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
