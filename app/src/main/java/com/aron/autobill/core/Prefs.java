package com.aron.autobill.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用配置（SharedPreferences 存储） */
public class Prefs {

    private static final String FILE = "autobill_prefs";

    /** 自动记账总开关（关闭后无障碍服务不再解析落账） */
    public static boolean autoEnabled(Context c) {
        return sp(c).getBoolean("auto_enabled", true);
    }

    public static void setAutoEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_enabled", v).apply();
    }

    /** 记账提醒顶部通知开关 */
    public static boolean notifyEnabled(Context c) {
        return sp(c).getBoolean("notify_enabled", true);
    }

    public static void setNotifyEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("notify_enabled", v).apply();
    }

    /** 远程更新地址（update.json 的 URL），默认空 = 从未配置过 */
    public static String updateUrl(Context c) {
        return sp(c).getString("update_url", "");
    }

    public static void setUpdateUrl(Context c, String url) {
        sp(c).edit().putString("update_url", url == null ? "" : url).apply();
    }

    // ==================== 应用级读屏开关 ====================

    /**
     * 用户在设置页为某个 App 指定的读屏级别。
     *
     * @return -1 = 未指定（用 ScreenPolicy 的出厂默认）；
     *         0 = 信任，1 = 需凭证，2 = 不读屏
     *
     * 为什么要让用户覆盖：出厂默认只是我的猜测。
     * 用户说「我只用支付宝付钱，别的购物 App 都别读屏」，
     * 那就该按用户说的办 —— 这是最终裁决权。
     */
    public static int screenLevel(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        return sp(c).getInt("screen_lv_" + pkg, -1);
    }

    public static void setScreenLevel(Context c, String pkg, int level) {
        if (pkg == null || pkg.isEmpty()) return;
        if (level < 0) {
            sp(c).edit().remove("screen_lv_" + pkg).apply();
        } else {
            sp(c).edit().putInt("screen_lv_" + pkg, level).apply();
        }
    }

    /** 是否有任何用户覆盖（用于设置页显示「已自定义 N 项」） */
    public static int customScreenCount(Context c) {
        int n = 0;
        for (String k : sp(c).getAll().keySet()) {
            if (k.startsWith("screen_lv_")) n++;
        }
        return n;
    }

    /** 清空全部读屏自定义，恢复出厂默认 */
    public static void clearScreenLevels(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String k : sp(c).getAll().keySet()) {
            if (k.startsWith("screen_lv_")) e.remove(k);
        }
        e.apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
