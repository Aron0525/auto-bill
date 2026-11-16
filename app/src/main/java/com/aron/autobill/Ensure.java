package com.aron.autobill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** 通知渠道与 builder 的集中封装，避免每处各写一遍。 */
final class Ensure {

    static final String CHANNEL_BILL = "autobill_bill";
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
            ch.enableVibration(false);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
    }

    static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(ctx, CHANNEL_BILL);
        }
        return new Notification.Builder(ctx);
    }
}
