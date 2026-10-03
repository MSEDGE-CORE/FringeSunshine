package com.wxz.sunshineserverandroid.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** 沉浸式系统栏（与参照项目一致）：状态栏/导航栏全透明，关闭导航栏对比度遮罩 */
fun ComponentActivity.applyImmersiveSystemBars() {
    val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    enableEdgeToEdge(
        statusBarStyle = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        },
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
    window.isNavigationBarContrastEnforced = false
}
