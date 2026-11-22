package com.aron.autobill;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityManager;

import com.aron.autobill.core.NotifBinder;
import com.aron.autobill.core.NotifLog;

/**
 * 采集链路的健康检查。
 *
 * 自动记账挂在两个系统服务上（通知监听 + 无障碍），这两个都不是 App 自己能保住的：
 * 能被用户关、能被系统清理、也能在重启后直接消失。出问题时现象和「没花钱」一样，
 * 所以必须能单独问出来「现在到底哪一环断了」，而不是等用户发现账本好几天没动。
 *
 * 放在这个包而不是 core/，是因为 core/ 是纯 JVM 层，不许 import android.*。
 */
public final class HealthCheck {

    private HealthCheck() {}

    /** 通知使用权是否被授予（能不能绑上的前提） */
    public static boolean notifGranted(Context c) {
        return NotifBinder.enabled(c);
    }

    /** 通知监听是否真的连着（授权了也可能没连上） */
    public static boolean notifConnected() {
        return NotifLog.started();
    }

    /** 无障碍服务是否在本应用的已启用列表里 */
    public static boolean a11yEnabled(Context c) {
        AccessibilityManager am =
                (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        try {
            for (AccessibilityServiceInfo info :
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                if (c.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 无障碍是否还留在系统设置里。
     *
     * 这和 a11yEnabled() 不是一回事。MIUI 会把这个服务从「已启用列表」里摘掉，
     * 但 Settings.Secure 里那一串还写着它，于是只看设置就显示「已开启 ✓」，
     * 实际早就读不到屏幕了——用户以为在用，其实一条都没记上。
     */
    public static boolean a11ySettingOn(Context c) {
        try {
            String v = android.provider.Settings.Secure.getString(
                    c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.contains(c.getPackageName() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置里开着、系统却不认——就是「无障碍掉线」的现场 */
    public static boolean a11yDropped(Context c) {
        return a11ySettingOn(c) && !a11yEnabled(c);
    }

    /** 是否已在电池优化白名单里（MIUI 的「省电策略：无限制」通常也落在这里） */
    public static boolean batteryUnrestricted(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 应用待机分桶。数值越小越宽松：5=豁免 10=活跃 20=常用 30=偶尔 40=很少 45=受限。
     * MIUI 上见过被压到「受限」的情况，这种状态下后台任务和开机广播基本都会被拦。
     */
    public static int standbyBucket(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1;
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager)
                            c.getSystemService(Context.USAGE_STATS_SERVICE);
            return usm == null ? -1 : usm.getAppStandbyBucket();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 分桶被压到「受限」，基本等于后台全禁 */
    public static boolean standbyRestricted(Context c) {
        return standbyBucket(c) >= 45;
    }

    /** 一句话概括当前链路，给设置页和首页用 */
    public static String summary(Context c) {
        if (!notifGranted(c)) return "通知使用权未开启";
        if (!notifConnected()) return "通知监听未连接";
        if (a11yDropped(c)) return "无障碍服务已掉线";
        if (!a11yEnabled(c)) return "无障碍服务未开启";
        if (standbyRestricted(c)) return "后台被系统限制";
        return "采集链路正常";
    }
}
