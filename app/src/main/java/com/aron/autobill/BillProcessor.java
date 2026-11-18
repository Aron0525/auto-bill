package com.aron.autobill;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.Category;
import com.aron.autobill.core.PendingStore;
import com.aron.autobill.model.Bill;
import com.aron.autobill.model.ParseResult;

/**
 * 落账核心 —— 通知通道和屏幕通道共用。
 *
 * 抽出来的原因：两条通道的去重、置信度分档、落库、通知用户
 * 逻辑必须完全一致，各写一遍迟早会走偏。
 *
 * 置信度三档（这是「自动记账效果差」的直接药方）：
 *   >= 80        直接入账，顶部横幅提示
 *   50 ~ 79      入账但标记待确认（账本里浅色底）
 *   <  50        **进待确认队列，不入账**，用户在 App 里一键确认
 *
 * 旧逻辑是 < 50 直接 return 丢掉 —— 「宁可不记，不可错记」的
 * 精神没错，但用户完全不知道漏了什么，这才是「效果差」的主观来源。
 */
public final class BillProcessor {

    private static final String TAG = "AutoBillProc";

    /** 入账硬门槛：低于它不入账，但会进待确认队列 */
    public static final int CONF_AUTO = 50;
    /** 高置信度：直接入账且无需用户复核 */
    public static final int CONF_CERTAIN = 80;
    /** 语义去重窗口：同一笔跨通道（通知→屏幕→短信）合并 */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;

    private BillProcessor() {}

    /**
     * 处理一条采集到的文本。
     *
     * @param ctx     上下文
     * @param store   账本
     * @param parser  解析器
     * @param pkg     来源包名
     * @param raw     采集到的原文
     * @param channel 通道名（notification / screen）
     */
    public static void process(Context ctx, BillStore store, BillParser parser,
                               String pkg, String raw, String channel) {
        if (raw == null || raw.trim().isEmpty()) return;
        settle(ctx, store, parser.parse(raw, pkg), raw, pkg, channel,
                System.currentTimeMillis());
    }

    /**
     * 通知通道专用：**分字段**解析，精度高于拼成单串。
     *
     * 真机实测：title = "微信支付"、text = "支付成功 ¥128.50 收款方：星巴克"。
     * 拼成一段后「微信」会被当商户名，分字段则不会。
     * 同一个 App 同一条通知，走这条路的结果严格优于 process()。
     */
    public static void processNotification(Context ctx, BillStore store, BillParser parser,
                                           String pkg, String title, String text,
                                           String bigText, String channel) {
        processNotification(ctx, store, parser, pkg, title, text, bigText, channel, 100, true);
    }

    /**
     * 带策略的通知通道。
     *
     * @param confCap           置信度上限（NotifPolicy 判定为低可信时压到 50 以下，进待确认队列）
     * @param titleAsMerchant   标题能否当商户名（聊天会话/群名一律 false）
     */
    public static void processNotification(Context ctx, BillStore store, BillParser parser,
                                           String pkg, String title, String text,
                                           String bigText, String channel,
                                           int confCap, boolean titleAsMerchant) {
        processNotification(ctx, store, parser, pkg, title, text, bigText, channel,
                confCap, titleAsMerchant, System.currentTimeMillis());
    }

    /**
     * 带交易时间的一条通知。
     *
     * @param ts 交易发生时间。断线补捞时传通知自己的 postTime，
     *           否则昨天 23:58 的付款会被记成今天 00:03（跨天、跨月都算错）。
     */
    public static void processNotification(Context ctx, BillStore store, BillParser parser,
                                           String pkg, String title, String text,
                                           String bigText, String channel,
                                           int confCap, boolean titleAsMerchant, long ts) {
        String body = (bigText != null && !bigText.trim().isEmpty()) ? bigText
                : (text == null ? "" : text);
        String raw = ((title == null ? "" : title) + " " + body).replaceAll("\\s+", " ").trim();
        if (body.trim().isEmpty() && (title == null || title.trim().isEmpty())) return;

        ParseResult r = parser.parseNotification(title, text, bigText, pkg,
                confCap, titleAsMerchant);
        // 标题不可信时（群聊），把标题兜底出来的商户清掉，避免记成「工作群」
        if (!titleAsMerchant && r != null && title != null
                && title.equals(r.merchant)) {
            r.merchant = "";
            r.confidence = Math.min(r.confidence, 45);
        }
        settle(ctx, store, r, raw, pkg, channel, ts);
    }

    /**
     * 占位符：有交易但金额不在通知里。
     *
     * 微信转账/红包的通知栏只有「[转账]」，金额要点进 App 才看得到。
     * 这时不能记账（金额未知），也不能丢弃（用户需要知道这笔钱）。
     * 发一条提示，点开就是微信/支付宝，用户自己看金额后可以补录。
     */
    public static void notifyPlaceholder(Context ctx, String pkg, String title, String text) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Ensure.channel(ctx, nm);

            String app = BillParser.platformName(pkg);
            Intent open = new Intent(ctx, AddBillActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("hint_app", app);
            open.putExtra("hint_text", text == null ? "" : text);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    ctx, 0x706c, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder b = Ensure.builder(ctx)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("有笔" + app + "交易，金额未显示")
                    .setContentText("点此补记金额（通知里只有「" + brief(text) + "」）")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(Ensure.PLACEHOLDER_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "待补全通知失败: " + t);
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 14 ? t.substring(0, 14) + "…" : t;
    }

    /** 解析之后的公共落账路径 —— 两条入口只差「怎么解析」，落账必须完全一致 */
    private static void settle(Context ctx, BillStore store, ParseResult r,
                               String raw, String pkg, String channel, long ts) {
        if (r == null || !r.ok) {
            Log.i(TAG, "✗ 解析失败[" + channel + "] " + truncate(raw));
            return;
        }

        // ---- 低于入账门槛：进待确认队列，不丢弃 ----
        if (r.confidence < CONF_AUTO) {
            PendingStore pending = new PendingStore(ctx);
            long id = pending.add(r, raw, pkg, channel);
            Log.i(TAG, "? 待确认[" + channel + "] 置信度=" + r.confidence
                    + " ¥" + String.format("%.2f", r.amountFen / 100.0)
                    + " " + r.merchant + " → 队列#" + id);
            notifyPending(ctx, r, id);
            return;
        }

        // ts 来自调用方：正常通知是「现在」，断线补捞是通知自己的 postTime
        String category = Category.classifyFor(r, raw);

        // ---- 第三道闸：时间维度去重（跨通道，语义级）----
        Bill similar = store.findSimilar(r.type, r.amountFen, r.merchant,
                ts - DEDUP_WINDOW_MS, ts + 60_000L);
        if (similar != null) {
            if (r.confidence > similar.confidence) {
                store.bumpConfidence(similar.id, r.confidence);
            }
            Log.i(TAG, "· 重复(语义)[" + channel + "] "
                    + (r.type == 2 ? "收入" : "支出")
                    + " ¥" + String.format("%.2f", r.amountFen / 100.0)
                    + " " + r.merchant + " 已存在 #" + similar.id);
            return;
        }

        // ---- 落库 ----
        boolean needConfirm = r.confidence < CONF_CERTAIN;
        Bill bill = new Bill(ts, pkg, r.merchant, r.amountFen, r.type, raw, category,
                r.note == null ? "" : r.note, r.confidence);
        bill.pending = needConfirm ? 1 : 0;
        boolean inserted = store.insert(bill);

        String dir = r.type == 2 ? "收入" : "支出";
        if (inserted) {
            Log.i(TAG, "✔ 记账[" + channel + "] " + dir
                    + " ¥" + String.format("%.2f", r.amountFen / 100.0)
                    + " " + r.merchant + " [" + category + "] 置信度=" + r.confidence
                    + (needConfirm ? " (待确认)" : ""));
            showBillNotification(ctx, bill, category, needConfirm);
            ctx.sendBroadcast(new Intent("com.aron.autobill.BILL_ADDED").setPackage(ctx.getPackageName()));
        } else {
            Log.i(TAG, "· 重复(指纹)[" + channel + "] " + dir + " " + r.merchant);
        }
    }

    /** 低置信度：发一条「发现疑似交易」通知，点开去确认 */
    private static void notifyPending(Context ctx, ParseResult r, long pendingId) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Ensure.channel(ctx, nm);

            Intent open = new Intent(ctx, MainActivity.class);
            open.putExtra("pending_id", pendingId);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    ctx, (int) (pendingId & 0x7fffffff), open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            String dir = r.type == 2 ? "收入" : "支出";
            android.app.Notification.Builder b = Ensure.builder(ctx)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("发现一笔疑似" + dir + "，待确认")
                    .setContentText(String.format("%s ¥%.2f", 
                            r.merchant == null || r.merchant.isEmpty() ? "未识别商户" : r.merchant,
                            r.amountFen / 100.0))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(Ensure.PENDING_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "待确认通知失败: " + t);
        }
    }

    /** 入账成功横幅（仿微信收款到账） */
    private static void showBillNotification(Context ctx, Bill bill, String category, boolean pending) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Ensure.channel(ctx, nm);

            String dir = bill.type == 2 ? "收入" : "支出";
            String title = pending ? "已记账（待确认）" : "已记账";
            String body = String.format("%s ¥%.2f  %s%s", dir, bill.amountFen / 100.0,
                    bill.merchant == null || bill.merchant.isEmpty() ? "未识别商户" : bill.merchant,
                    category == null || category.isEmpty() ? "" : " · " + category);

            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    ctx, 0, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder b = Ensure.builder(ctx)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(Ensure.BILL_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "记账通知失败: " + t);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
