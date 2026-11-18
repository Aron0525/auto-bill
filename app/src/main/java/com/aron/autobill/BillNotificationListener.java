package com.aron.autobill;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.NotifPolicy;
import com.aron.autobill.core.Prefs;
import com.aron.autobill.model.Bill;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知监听服务 —— 自动记账的**主数据源**。
 *
 * 为什么必须用这个而不是无障碍的 TYPE_NOTIFICATION_STATE_CHANGED：
 *
 *   能力              无障碍通知事件        NotificationListenerService
 *   ─────────────────────────────────────────────────────────────────
 *   Notification 对象   经常为 null（MIUI）   总是有
 *   EXTRA_BIG_TEXT      常被截断             完整
 *   通知历史            ✗                    ✓ getActiveNotifications()
 *   应用名/图标         ✗                    ✓
 *   MIUI 稳定性         差（易被杀/丢事件）   好
 *   系统标准 API        非本职                ✓ 正是本职
 *   审核风险            无障碍被严查          低
 *
 * 实测教训：真机账本 66 条里唯一的 1 条真机记录来自「整屏通知栏」，
 * 因为无障碍事件拿不到完整 Notification，退化成了读屏文本。
 *
 * 设计要点：
 *   1) 通知来这里，屏幕采集仍留在 AccessibilityService（补商户名）
 *   2) 两者用「方向+金额+时间窗」合并成一条 —— 见 BillStore.findSimilar
 *   3) 低置信度不丢弃，进待确认队列（PendingStore）
 */
public class BillNotificationListener extends NotificationListenerService {

    private static final String TAG = "AutoBillNotif";

    /** 同一通知重复投递的短时去重（30 分钟，与无障碍服务保持一致） */
    private static final long DEDUP_TTL_MS = 30 * 60 * 1000L;

    private BillStore store;
    private com.aron.autobill.core.BillParser parser;
    /** "包名:通知ID:when" → 上次处理时间 */
    private final Map<String, Long> seen = new LinkedHashMap<>();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        store = new BillStore(this);
        parser = new com.aron.autobill.core.BillParser();
        Log.i(TAG, "通知监听已连接，开始监听交易通知");

        // 「服务在岗」的登记。保活服务的 5 分钟自检靠它判断要不要重绑，
        // 主页状态行靠它判断该显示「监听中」还是「未连接」。
        com.aron.autobill.core.NotifLog.markStart(System.currentTimeMillis());

        // 系统把监听绑上了，说明授权链路是通的 —— 顺手把保活服务也拉起来
        // （它可能刚被 MIUI 清掉）。幂等，重复调用无副作用。
        KeepAliveService.start(this);
        KeepAliveService.refresh(this);
    }

    /**
     * 被系统解绑（权限被撤销、或 MIUI 回收了进程）。
     *
     * 这里必须主动请求重绑：实测 HyperOS 上解绑后系统不会自己接回来，
     * 而用户那边只表现为「付了钱但没记账」，没有任何可见异常。
     */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "通知监听已断开 —— 请求系统重新绑定");
        try {
            com.aron.autobill.core.NotifBinder.rebind(this, BillNotificationListener.class);
        } catch (Throwable ignored) {}
        try {
            KeepAliveService.refresh(this);   // 常驻通知立刻变成「监听未连接」
        } catch (Throwable ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Throwable t) {
            // 监听服务崩一次就不再收到任何通知，必须全兜住
            Log.e(TAG, "onNotificationPosted 异常: " + t, t);
        }
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (!Prefs.autoEnabled(this)) return;

        String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        if (pkg.isEmpty()) return;

        // 自己的通知一律忽略（会形成「记账成功→再解析→再记账」的死循环）
        if (getPackageName().equals(pkg)) return;

        // 系统 UI 类：只聚合他人内容，永不作为交易来源
        if (isSystemUi(pkg)) return;

        Notification n = sbn.getNotification();
        if (n == null) return;

        // ---- 第一道闸：通知身份 + 时间戳 ----
        // 同一条通知会因更新进度、点亮屏幕、展开等原因反复投递
        String key = pkg + ":" + sbn.getId() + ":" + n.when;
        long now = System.currentTimeMillis();
        Long last = seen.get(key);
        if (last != null && now - last < DEDUP_TTL_MS) return;
        seen.put(key, now);
        prune(now);

        // ---- 提取字段（这是相对无障碍的最大优势：BIG_TEXT 完整可读）----
        //      注意是**分字段**取，不是拼成一段：title 常是 App 名，
        //      拼进正文会污染商户名。
        Fields f = extractFields(n);
        String text = f.flat;
        if (text.isEmpty() || text.length() < 4) return;

        // ---- 第二道闸：渠道/类型准入（在解析器之前）----
        // 这里是本轮精准度改造的核心：App 自己已经把通知分好类了
        // （支付宝 100064=活动、100062=订阅…），我们不该假装看不见，
        // 更不该把营销推送给普通解析器去撞运气。
        String channelId = channelOf(sbn);
        boolean groupSummary = isGroupSummary(n);
        boolean ongoing = isOngoing(n);
        String convTitle = conversationTitle(n);

        NotifPolicy.Decision d = NotifPolicy.decide(
                pkg, channelId, f.title, text, groupSummary, ongoing, convTitle);

        if (d.isSkip()) {
            Log.i(TAG, "· 跳过[" + pkg + "/" + channelId + "] " + d.reason
                    + "  " + truncate(text));
            return;
        }

        // 占位符必须先于「支付语义预筛」判断。
        // 微信转账卡片的正文就是「[转账]」三个字，本身**没有任何支付语义词**
        // （不含"支付/付款/交易"），却代表一笔真实交易。
        // 先做语义预筛会把它当噪音丢掉 —— 用户那笔钱就永远不会出现在账本上。
        if (d.verdict == NotifPolicy.PLACEHOLDER) {
            Log.i(TAG, "? 待补全[" + pkg + "/" + channelId + "] " + d.reason
                    + "  " + truncate(text));
            BillProcessor.notifyPlaceholder(this, pkg, f.title, text);
            return;
        }

        // 快速预筛：没有支付语义的通知直接跳过，避免把聊天/广告送去解析
        if (!looksLikeTransaction(text) && d.verdict == NotifPolicy.PARSE) {
            Log.i(TAG, "· 非遗漏(无支付语义)[" + pkg + "] " + truncate(text));
            return;
        }

        Log.i(TAG, "通知[" + pkg + "/" + channelId + "] " + d.reason + " " + truncate(text));
        dispatch(pkg, f.title, f.text, f.bigText, d);
    }

    /** 渠道 ID（API 26+ 才有；MIUI 老版本可能返回 null） */
    private static String channelOf(StatusBarNotification sbn) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String id = sbn.getNotification() == null ? null : sbn.getNotification().getChannelId();
                return id == null ? "" : id;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * 通知组摘要：微信「3条新消息」、支付宝「2条新通知」。
     *
     * 这类通知的正文是聚合摘要，不是某一笔交易。让它进解析器，
     * 结果是被当成一笔账 —— 用户看到凭空多出的支出，且金额无从解释。
     * 系统自己用 FLAG_GROUP_SUMMARY 标了出来，直接采信。
     */
    private static boolean isGroupSummary(Notification n) {
        if (n == null) return false;
        try {
            return (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        } catch (Throwable ignored) { return false; }
    }

    /** 常驻通知：正在通话、音乐播放、充电中 —— 永不结束，不可能是交易 */
    private static boolean isOngoing(Notification n) {
        if (n == null) return false;
        try {
            return (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        } catch (Throwable ignored) { return false; }
    }

    /**
     * 会话标题：聊天类通知的「对方名」或「群名」。
     *
     * 为什么必须单独取：微信群里有人发「转账 200」，title 是群名，
     * 若当商户名会得到「工作群」这种无法解释的账目。
     * EXTRA_CONVERSATION_TITLE 是系统给聊天通知打的标记，
     * 有它就说明这是会话，不是交易凭证。
     */
    private static String conversationTitle(Notification n) {
        if (n == null || n.extras == null) return "";
        try {
            CharSequence c = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            return c == null ? "" : c.toString().trim();
        } catch (Throwable ignored) { return ""; }
    }

    /**
     * 提取通知的**结构化**字段，而不是拼成一段。
     *
     * 为什么必须分开：真机 dumpsys 实测，title 往往是 App 名
     *   title = "微信支付"，text = "支付成功 ¥128.50 收款方：星巴克"
     * 一旦拼成「微信支付 支付成功 ¥128.50 收款方：星巴克」，
     * 解析器就会把「微信」当商户 —— 这正是错账的最大来源。
     *
     * 交给 BillParser.parseNotification(title, text, bigText, pkg) 分别处理。
     */
    private static final class Fields {
        String title = "";
        String text = "";
        String bigText = "";
        /** 用于预筛和日志的合并串（不参与解析） */
        String flat = "";
    }

    private Fields extractFields(Notification n) {
        Fields f = new Fields();
        if (n == null || n.extras == null) return f;
        Bundle e = n.extras;

        CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
        if (t != null) f.title = t.toString().trim();

        // BIG_TEXT 是完整正文，优先于 EXTRA_TEXT
        CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.length() > 0) f.bigText = big.toString().trim();

        CharSequence txt = e.getCharSequence(Notification.EXTRA_TEXT);
        if (txt != null) f.text = txt.toString().trim();

        // TEXT_LINES 补充（与 BIG_TEXT 常重复，不重复时才用）
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : lines) if (c != null) sb.append(c).append(' ');
            String ex = sb.toString().replaceAll("\\s+", " ").trim();
            if (!ex.isEmpty()) {
                boolean dup = (!f.bigText.isEmpty() && f.bigText.contains(ex))
                        || (!f.text.isEmpty() && f.text.contains(ex));
                if (!dup) {
                    if (f.bigText.isEmpty()) f.bigText = ex;
                    else f.bigText = f.bigText + " " + ex;
                }
            }
        }

        CharSequence sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (sub != null && f.text.isEmpty()) f.text = sub.toString().trim();

        // 合并串仅供预筛/日志
        String body = !f.bigText.isEmpty() ? f.bigText : f.text;
        f.flat = (f.title + " " + body).replaceAll("\\s+", " ").trim();
        return f;
    }

    /** 兼容旧调用：拼成单串（仅用于预筛/日志，不用于解析） */
    private String extractText(Notification n) {
        return extractFields(n).flat;
    }

    private boolean isBigText(Notification n) {
        return n != null && n.extras != null
                && n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null;
    }

    /**
     * 支付语义预筛。
     * 目的：让通知监听可以「不限包名」——用户装任何新 App 都能自动适配，
     * 不需要改代码发版。靠内容判断而不是包名白名单。
     */
    private static final String[] PAY_WORDS = {
            "支付", "付款", "消费", "扣款", "交易", "到账", "入账", "退款", "实付",
            "订单", "收款", "转账", "付款成功", "已支付", "支出", "收入", "扣费",
            "账单", "充值", "提现", "还款", "红包", "工资", "余额",
    };

    /** 明确不是交易的：验证码、广告、推送 */
    private static final String[] NOT_PAY_WORDS = {
            "验证码", "校验码", "动态码", "登录", "注册", "改密码",
            "抢购", "秒杀", "限时", "领取优惠券", "ad", "广告",
            "升级", "版本更新", "安装", "下载", "点赞", "评论", "关注",
    };

    private boolean looksLikeTransaction(String text) {
        for (String w : NOT_PAY_WORDS) {
            if (text.contains(w)) return false;
        }
        for (String w : PAY_WORDS) {
            if (text.contains(w)) return true;
        }
        // 有金额也值得试（「麦当劳 ¥36.50」这种没关键词的）
        return text.matches(".*[¥￥]\\s*\\d+.*") || text.matches(".*\\d+[.,]\\d{2}\\s*元.*");
    }

    private boolean isSystemUi(String pkg) {
        return pkg.equals("com.android.systemui")
                || pkg.equals("com.miui.notification")
                || pkg.equals("com.android.keyguard")
                || pkg.startsWith("com.miui.security")
                || pkg.equals("com.android.settings")
                || pkg.contains("inputmethod")
                || pkg.contains(".ime");
    }

    private void dispatch(String pkg, String title, String text, String bigText,
                          NotifPolicy.Decision d) {
        BillProcessor.processNotification(this, store, parser, pkg,
                title, text, bigText, "notification",
                d.confCap, d.titleAsMerchant);
    }

    private void prune(long now) {
        if (seen.size() < 100) {
            java.util.Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > DEDUP_TTL_MS) it.remove();
            }
            return;
        }
        java.util.Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUP_TTL_MS) it.remove();
        }
        if (seen.size() > 300) seen.clear();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
