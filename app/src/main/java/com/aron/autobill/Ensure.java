package com.aron.autobill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** 通知渠道与 builder 的集中封装，避免每处各写一遍。 */
final class Ensure {

    /**
     * 渠道 ID 带 _v2。
     *
     * Android 允许 App 更新已有渠道的名称和描述，但重要性、振动、锁屏可见性这些
     * 一旦渠道建好就冻结了（当作「用户设置」保护）。老用户手机上 autobill_bill
     * 是 enableVibration(false) 建的，改代码也不会让横幅弹出来，只能换个 ID 重建。
     */
    static final String CHANNEL_BILL = "autobill_bill_v2";
    /** 上一版渠道，建新渠道时顺手清掉，避免设置里出现两个「记账提醒」 */
    private static final String CHANNEL_BILL_LEGACY = "autobill_bill";
    static final int BILL_ID = 1001;
    static final int PENDING_ID = 1002;
    /** 待补全提示：微信转账卡片这类「有交易但金额不在通知里」的通知 */
    static final int PLACEHOLDER_ID = 1003;

    private Ensure() {}

    static void channel(Context ctx, NotificationManager nm) {
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_BILL, "记账提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("每记一笔，在屏幕顶部弹出横幅提示详情");
            // 只把渠道标成 HIGH 不够：MIUI 把「无声且不振动」的高优先级通知当静默处理，
            // 记了账什么都不弹，用户以为没生效。给一个短振动，头部横幅才会真的下来。
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 90, 70, 90});
            ch.setShowBadge(true);
            // PRIVATE 在锁屏上只给一个「1 条通知」，看不到金额；记账本来就不怕看。
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            // 旧渠道留着只会在系统设置里多出一条「记账提醒」，删掉。
            try {
                nm.deleteNotificationChannel(CHANNEL_BILL_LEGACY);
            } catch (Throwable ignored) {
            }
        }
    }

    static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(ctx, CHANNEL_BILL);
        }
        return new Notification.Builder(ctx);
    }
}
