/*
 * ============================================================
 * BootReceiver - 开机自启动广播接收器
 * ============================================================
 *
 * 接收系统开机广播（BOOT_COMPLETED），自动启动桌面精灵。
 *
 * 工作流程：
 * 1. 系统启动完成后，Android 发送 BOOT_COMPLETED 广播
 * 2. 本接收器检查用户是否开启了「开机自启」开关
 * 3. 如果开启，自动启动 FloatingAvatarService（桌面精灵前台服务）
 *
 * 注意事项：
 * - Android 10+ 需要 RECEIVE_BOOT_COMPLETED 权限
 * - 部分厂商（小米、华为等）需要用户手动在"自启动管理"中授权
 * - 如果悬浮窗权限（SYSTEM_ALERT_WINDOW）未开启，
 *   FloatingAvatarService 内部会引导用户授权
 *
 * 配置项（SharedPreferences）：
 *   KEY_BOOT_START - 是否开启开机自启（默认 false）
 *   KEY_ENABLED    - 悬浮窗功能是否启用（默认 true）
 *
 * 在 AndroidManifest.xml 中注册：
 *   <receiver android:name=".floating.BootReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *       </intent-filter>
 *   </receiver>
 * ============================================================
 */
package com.kkgo.mindsoul.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启动广播接收器
 *
 * 监听 BOOT_COMPLETED 广播，根据用户设置自动启动桌面精灵。
 * 仅在用户同时满足以下两个条件时才启动：
 *   1. 「开机自启」开关已打开（KEY_BOOT_START = true）
 *   2. 「悬浮窗功能」开关已打开（KEY_ENABLED = true）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 仅处理开机完成广播
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "  收到开机广播，检查自启动配置...")
        Log.i(TAG, "═══════════════════════════════════════")

        // 读取用户配置
        val prefs = context.getSharedPreferences(
            FloatingAvatarService.PREF_NAME,
            Context.MODE_PRIVATE
        )

        val bootStartEnabled = prefs.getBoolean(FloatingAvatarService.KEY_BOOT_START, false)
        val floatingEnabled = prefs.getBoolean(FloatingAvatarService.KEY_ENABLED, true)

        Log.i(TAG, "[配置] 开机自启: $bootStartEnabled, 悬浮窗启用: $floatingEnabled")

        // 两个条件都满足才启动
        if (bootStartEnabled && floatingEnabled) {
            Log.i(TAG, "[启动] 条件满足，自动启动桌面精灵服务")

            try {
                // 使用 FloatingAvatarService 的便捷启动方法
                // 内部会处理前台服务、Android O+ 适配等逻辑
                FloatingAvatarService.start(context)
                Log.i(TAG, "[启动] 桌面精灵服务启动指令已发送")
            } catch (e: Exception) {
                Log.e(TAG, "[错误] 启动桌面精灵服务失败: ${e.message}", e)
            }
        } else {
            val reason = if (!bootStartEnabled) "开机自启未开启" else "悬浮窗功能已禁用"
            Log.i(TAG, "[跳过] $reason，不启动桌面精灵")
        }
    }
}
