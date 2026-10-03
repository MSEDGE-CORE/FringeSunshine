package com.wxz.sunshineserverandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wxz.sunshineserverandroid.ui.SettingsScreen
import com.wxz.sunshineserverandroid.ui.applyImmersiveSystemBars
import com.wxz.sunshineserverandroid.ui.theme.SunshineServerAndroidTheme

/** 设置页：独立 Activity（标题栏图标点进来，返回箭头/系统返回键退出） */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveSystemBars()
        ServerCore.init(applicationContext)
        setContent {
            SunshineServerAndroidTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}
