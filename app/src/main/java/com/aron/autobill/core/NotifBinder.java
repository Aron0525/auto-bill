package com.aron.autobill.core;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 通知监听服务的绑定修复工具。
 *
 * ==================== 排查结论（2026-11 真机，HyperOS/Android 14） ====================
 *
 * 想在 App 内「强制系统重新绑定监听服务」，试过的路和结果：
 *
 *   1. NotificationListenerService.requestRebind(cn)
 *      → 官方 API，对「绑过又被踢」有效；对「从未绑上」无效（文档写明其语义）。
 *
 *   2. 反射 NotificationManager.setNotificationListenerServiceEnabled(cn, bool)
 *      → 无效。MIUI 的 NotificationManager 上没有这个方法（NoSuchMethodException）。
 *
 *   3. 反射 binder 层的 INotificationManager
 *      → 无效。把 framework.jar 和 services.jar 全部解包（dexdump 132MB+28MB）
 *        确认：MIUI 把带 listener 管理方法的接口拆进了 services.jar，
 *        那个类只有系统进程能加载；App 侧 framework.jar 里的
 *        android.app.INotificationManager 是裁剪版旧接口，
 *        只有 notify/cancel 等十来个方法（已实测列出全部方法签名）。
 *
 *   4. cmd notification allow_listener（shell）
 *      → 有效。日志立即出现「通知监听已连接」。但它需要 shell 权限，App 无法调用。
 *
 * ==================== 最终方案：分层自愈 ====================
 *
 *   层1  onListenerDisconnected → requestRebind
 *        覆盖最常见的运行中掉线（系统回收后解绑）。
 *   层2  KeepAliveService 每 5 分钟自检 → 未连接则 requestRebind。
 *   层3  监控面板一键修复 → requestRebind；若 2 秒后仍未连接，
 *        给出明确指引跳系统设置 toggle（toggle 已验证立即触发绑定）。
 *   层4  BootReceiver 开机自启 + MIUI 自启动引导（解决重启后 MIUI 拒绝拉起）。
 *
 * 前台服务（KeepAliveService）挡住 am kill —— 这消除了掉线的最大来源本身。
 */
public final class NotifBinder {

    private NotifBinder() {}

    private static final String TAG = "AutoBillBinder";

    /**
     * 请求系统重新绑定监听服务。
     *
     * @return true = 已发出请求（不代表系统一定执行，最终以
     *         onListenerConnected / NotifLog.started() 为准）
     */
    public static boolean rebind(Context ctx, Class<?> listenerClass) {
        if (ctx == null || listenerClass == null) return false;
        try {
            ComponentName cn = new ComponentName(ctx, listenerClass);
            android.service.notification.NotificationListenerService.requestRebind(cn);
            Log.i(TAG, "已请求重新绑定 " + listenerClass.getSimpleName());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "requestRebind 失败", t);
            return false;
        }
    }
}
