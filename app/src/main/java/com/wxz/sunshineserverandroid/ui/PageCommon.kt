package com.wxz.sunshineserverandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wxz.sunshineserverandroid.ServerCore

/**
 * 页面通用刷新计数：日志事件 + 周期轮询都会 tick。
 * 页面销毁时自动注销监听，不会把其它页面的回调顶掉。
 */
@Composable
fun rememberPageTick(periodMs: Long = 2_000L): Int {
    val tick = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { tick.intValue++ }
        ServerCore.addLogListener(listener)
        onDispose { ServerCore.removeLogListener(listener) }
    }
    LaunchedEffect(periodMs) {
        while (true) {
            kotlinx.coroutines.delay(periodMs)
            tick.intValue++
        }
    }
    return tick.intValue
}

/**
 * 页面正文滚动列（参照项目的排版）：
 * 从标题栏下方开始，左右 20dp，底部避开系统导航条但背景一直铺到导航条后面。
 */
@Composable
fun PageScrollColumn(
    padding: PaddingValues,
    spacing: Arrangement.HorizontalOrVertical = Arrangement.spacedBy(24.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding())
            .windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Left + WindowInsetsSides.Right)
            )
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        verticalArrangement = spacing,
        content = content
    )
}
