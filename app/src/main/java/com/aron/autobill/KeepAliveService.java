package com.aron.autobill;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.aron.autobill.core.NotifLog;
import com.aron.autobill.core.Prefs;

/**
 * 保活前台服务 —— 让通知监听服务能一直在岗。
 *
 * ==================== 为什么非做不可 ====================
 *
 * 真机实测（Xiaomi 2312DRAABC / Android 14 / HyperOS）复现了这个问题：
 *
 *   1. 用 am force-stop 杀掉 App（等价于用户在多任务里划掉、或系统清理内存）
 *   2. 让微信发一条支付通知
 *   3. 结果：进程没有起来、无任何日志、通知彻底丢失
 *
 * 也就是说 `NotificationListenerService` **不会自己复活**。
 * 系统日志里能看到 MIUI 的拦截：
 *
 *   AutoStartManagerServiceStubImpl: MIUILOG- Reject service :
 *     Intent { cmp=com.aron.autobill/.BillNotificationListener } uid : 10264
 *   NotificationListeners: AutStart Unable to bind notification listener service
 *
 * 后果很严重：用户装完 App、付了钱、发现没记账 —— 而 App 表面上是“已开启”的。
 * 这种“静默失效”比直接报错糟糕得多，因为它让用户对整个功能失去信任。
 *
 * ==================== 为什么是前台服务 ====================
 *
 * 候选方案对比：
 *
 *   JobScheduler / WorkManager —— 最小间隔 15 分钟，做不到实时。
 *      记账要的是“付完就记”，15 分钟延迟等于漏记。
 *
 *   START_STICKY 裸 Service —— Android 8 起后台服务被严格限制，
 *      纯后台 Service 活不过几分钟，MIUI 上更短。
 *
 *   前台服务 + 常驻通知 —— 系统视其为“用户可见的正在运行的任务”，
 *      杀掉优先级最低。这是唯一能长期在岗的方式。
 *
 *   代价：通知栏多一个常驻图标。这是必须付的价 ——
 *   要么接受一个图标，要么接受漏记。没有第三种选择。
 *
 * ==================== 关于“无声通知” ====================
 *
 * 用 IMPORTANCE_MIN + setSilent(true)：不出声、不震动、不弹横幅，
 * 只安静地待在通知栏。用户扫一眼知道它在跑，不会被反复打扰。
 *
 * 注意 IMPORTANCE_MIN 在部分系统上会被折叠成一行，
 * 这是好事 —— 折叠了说明系统也在“降级对待”它，更不容易被杀。
 */
public class KeepAliveService extends Service {

    private static final String TAG = "AutoBillKeepAlive";

    /** 前台服务通知的渠道 ID */
    public static final String CHANNEL_ID = "autobill_keepalive";

    /** 通知 ID（固定值：重复 startForeground 不会堆出多个图标） */
    private static final int NOTIF_ID = 1001;

    /**
     * 自检周期：每 5 分钟检查一次监听是否还连着。
     *
     * ==================== 为什么需要周期性自检 ====================
     *
     * 实测（HyperOS / Android 14）踩到的坑：
     *   监听服务被系统解绑后**不会自己回来**，而且 MIUI 的日志里
     *   只有一行 `AutStart Unable to bind notification listener service`，
     *   用户这边完全看不到任何异常 —— 表现就是「付了钱但没记账」。
     *
     * 单次补救不够：解绑可能发生在任何时刻（系统回收、权限变更、
     * 用户在后台设置里动了一下）。只有定期检查才能保证「掉线了能自动接回」。
     *
     * 5 分钟是个折中：
     *   · 够快 —— 掉线后 5 分钟内自动恢复，用户基本无感
     *   · 够省 —— 一天 288 次检查，对电量影响可忽略
     */
    private static final long SELF_CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private final android.os.Handler ticker = new android.os.Handler(
            android.os.Looper.getMainLooper());

    private final Runnable selfCheck = new Runnable() {
        @Override
        public void run() {
            try {
                if (!com.aron.autobill.core.NotifLog.started()) {
                    Log.i(TAG, "周期自检：监听未连接，尝试恢复");
                    rebindListener();
                }
            } catch (Throwable ignored) {}
            try {
                ticker.postDelayed(this, SELF_CHECK_INTERVAL_MS);
            } catch (Throwable ignored) {}
        }
    };

    /** 重新绑定监听服务 */
    private void rebindListener() {
        com.aron.autobill.core.NotifBinder.rebind(this, BillNotificationListener.class);
    }

    // ==================== 启动/停止 ====================

    /**
     * 启动保活服务。幂等：重复调用不会出问题。
     *
     * 调用点：
     *   · MainActivity 的 onResume（用户每次打开 App 都确保在岗）
     *   · BootReceiver 开机后
     *   · BillNotificationListener 的 onListenerConnected（系统绑定成功时）
     */
    public static void start(Context c) {
        if (c == null) return;
        // 用户主动关掉自动记账时不再保活 —— 尊重用户的选择，不偷偷跑
        if (!Prefs.autoEnabled(c)) {
            Log.i(TAG, "自动记账已关闭，不启动保活");
            return;
        }
        try {
            Intent it = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(it);
            } else {
                c.startService(it);
            }
        } catch (Throwable t) {
            // 后台启动限制（Android 12+ 从后台启前台服务会抛异常）
            // 不该让 App 崩，记一笔就行 —— 下次用户打开 App 会再试
            Log.w(TAG, "启动保活服务失败（可能受后台启动限制）", t);
        }
    }

    public static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Throwable ignored) {}
    }

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
        // 启动周期性自检：监听掉线了自动接回来。
        // 这是「监控机制」的最后一环 —— 前台服务保证进程活着，
        // 定时自检保证监听是连着的。两个都做到，记账才算可靠。
        try {
            ticker.postDelayed(selfCheck, SELF_CHECK_INTERVAL_MS);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次启动都重新 startForeground —— 这是“我在干活”的声明
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 必须显式给出类型，否则抛 SecurityException
                startForeground(NOTIF_ID, buildNotification(this),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, buildNotification(this));
            }
        } catch (Throwable t) {
            // 真机踩过的坑：Android 14 上 forgroundServiceType 与声明的权限不匹配会抛
            // SecurityException（例如声明 dataSync 却没申请 FOREGROUND_SERVICE_DATA_SYNC）。
            // 这里退一步：至少把服务以普通方式跑起来，监听仍可能工作，
            // 总比整个进程被判定“未调用 startForeground”而崩溃要好。
            Log.w(TAG, "startForeground 失败，退化为普通服务", t);
            try {
                Notification n = buildNotification(this);
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, n);
            } catch (Throwable ignored) {}
        }

        // 顺便请求一次监听绑定。
        // 为什么需要：保活服务活着 ≠ 监听服务绑着。这两件事在 MIUI 上会脱钩 ——
        // 监听服务被系统解绑后不会自己回来，但保活服务还在跑。
        // 每次保活服务启动都补一次绑定请求，是最低成本的兜底。
        //
        // 注意「撤销→授权」这一步会把用户的授权状态短暂翻转。
        // 如果监听已经连着（NotifLog.started()），就不要去动它 ——
        // 不必要的翻转有让系统重新绑定、把正在工作的服务抖掉的风险。
        // 只有在明确「没连着」时才做。
        try {
            if (!com.aron.autobill.core.NotifLog.started()) {
                Log.i(TAG, "监听未连接，尝试重新绑定");
                rebindListener();
            }
        } catch (Throwable ignored) {}

        // START_STICKY：系统因内存压力杀掉后会自动重建（intent 为 null）。
        // 注意这不保证 MIUI 一定拉起来 —— 所以还需要 BootReceiver 和用户打开 App 时兜底。
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 用户从多任务划掉 App：MIUI 上很可能连带杀掉服务。
        // 这里不试图重启（后台启动限制会拦），只记日志，
        // 真正的复苏靠：通知监听被系统重新绑定、或用户下次打开 App。
        Log.i(TAG, "任务被移除（用户划掉 App）；等待系统或被打开时恢复");
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "保活服务被销毁");
        try {
            ticker.removeCallbacks(selfCheck);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;    // 不需要绑定
    }

    // ==================== 通知 ====================

    /** 创建通知渠道（幂等，重复调用无副作用） */
    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "自动记账运行中", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("保持后台监听，确保支付通知能被及时记账");
        ch.setShowBadge(false);          // 不显示角标 —— 它不是“未读消息”
        ch.setSound(null, null);         // 无声
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);  // 锁屏不显示
        try {
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    /** 构造常驻通知 */
    private static Notification buildNotification(Context c) {
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL_ID);
        } else {
            b = new Notification.Builder(c);
        }

        int acc = NotifLog.accepted();
        String text = acc > 0
                ? "已接收 " + acc + " 条交易通知"
                : "等待支付通知…";

        b.setContentTitle("自动记账运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentIntent(pi)
                .setOngoing(true)              // 不可滑动清除 —— 清掉了服务可能被降级
                .setShowWhen(false)            // 不显示时间，免得看起来像一条“新消息”
                .setPriority(Notification.PRIORITY_MIN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }

    /**
     * 刷新常驻通知上的计数（每次成功入账后调用）。
     *
     * 这一步不只是好看 —— 它是**给用户的可见证据**：
     * 通知栏上的数字在涨，说明监听真的在工作。
     * 反过来，如果付了钱但数字不动，用户立刻就知道有问题，
     * 而不是过一周才发现账本缺了一大截。
     */
    public static void refresh(Context c) {
        if (c == null) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIF_ID, buildNotification(c));
        } catch (Throwable ignored) {}
    }
}
