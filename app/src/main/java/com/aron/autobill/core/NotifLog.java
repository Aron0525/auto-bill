package com.aron.autobill.core;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 通知接收记录 —— 给「通知监控」面板用的运行时账本。
 *
 * ==================== 为什么需要这个东西 ====================
 *
 * 用户的原话是「不能在消息栏里接收微信支付以及支付宝的消息」。
 * 这类问题最难的地方不是修，而是**判断到底修好了没有**：
 *
 *   · 到底有没有收到通知？（可能服务根本没在跑）
 *   · 收到了但被过滤了？（可能渠道 ID 判断错了）
 *   · 收到了、解析了、但没入账？（可能置信度不够进了待确认）
 *
 * 没有记录的话，这三种情况在用户眼里长得一模一样：都是「没记账」。
 * 所以每一层都要留痕，面板才可能把真相说出来。
 *
 * ==================== 设计 ====================
 *
 * 1. **环形缓冲，容量固定**。通知可能很密集（群消息），
 *    不设上限会把内存吃光。丢最老的，保最近的。
 *
 * 2. **纯 Java，不碰 Android API**。这样能在 JVM 自检里跑，
 *    否则「记录逻辑本身有 bug」会成为新的盲点。
 *
 * 3. **刻意不持久化**。进程被杀后记录清零 —— 这不是缺陷，是信号：
 *    面板显示「本次运行已收到 0 条」时，用户立刻知道服务刚重启过。
 *    如果持久化，反而会掩盖「服务一直在挂」这个最重要的事实。
 *    跨重启的累计数据交给 Sqlite 里的账单表，不用这里重复存。
 *
 * 4. **每条都记下判定结果和原因**。被过滤的通知也要留痕，
 *    不然用户问「我明明付款了为什么没记」时无从下手。
 */
public final class NotifLog {

    private NotifLog() {}

    /** 环形缓冲容量。50 条足够看清「最近有没有在用」的实际模式。 */
    public static final int CAPACITY = 50;

    /** 判定结果 */
    public static final int ACCEPT = 0;    // 通过，交给解析器
    public static final int FILTER = 1;    // 被策略过滤（营销、群聊摘要、系统 UI…）
    public static final int DEDUP = 2;     // 重复投递，去重丢弃
    public static final int SKIP = 3;      // 其他原因跳过（太短、空正文、自己的通知）

    public static String verdictName(int v) {
        switch (v) {
            case ACCEPT: return "收下";
            case FILTER: return "过滤";
            case DEDUP:  return "重复";
            case SKIP:   return "跳过";
            default:     return "?";
        }
    }

    /** 一条记录 */
    public static final class Entry {
        public final long time;
        public final String pkg;
        public final String title;
        public final String text;
        public final int verdict;
        public final String reason;

        public Entry(long time, String pkg, String title, String text, int verdict, String reason) {
            this.time = time;
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.verdict = verdict;
            this.reason = reason == null ? "" : reason;
        }

        /** 面板上显示一行用的摘要（正文截断，避免一条通知占满屏） */
        public String summary() {
            String t = (title.isEmpty() ? "" : title + "  ") + text;
            t = t.replace('\n', ' ').trim();
            if (t.length() > 90) t = t.substring(0, 90) + "…";
            return t;
        }
    }

    // ==================== 运行时状态 ====================

    private static final LinkedList<Entry> RING = new LinkedList<>();

    /** 服务启动时刻，面板用来显示「已运行 xx 分钟」 */
    private static long startAt = 0;

    private static int total = 0;        // 本次运行收到的通知总数
    private static int accepted = 0;     // 通过过滤的
    private static int filtered = 0;     // 被过滤的
    private static long lastAt = 0;      // 最后一条通知的时间
    private static String lastPkg = "";

    /** 服务启动时调用，重置本次运行的统计 */
    public static synchronized void markStart(long now) {
        startAt = now;
        total = 0;
        accepted = 0;
        filtered = 0;
        lastAt = 0;
        lastPkg = "";
        RING.clear();
    }

    public static synchronized boolean started() {
        return startAt > 0;
    }

    public static synchronized long startAt() {
        return startAt;
    }

    /** 记一条。线程安全：通知回调可能在任意线程。 */
    public static synchronized void record(long now, String pkg, String title,
                                           String text, int verdict, String reason) {
        if (startAt == 0) startAt = now;       // 还没 markStart 就来了（少见，兜底）

        total++;
        if (verdict == ACCEPT) accepted++;
        else if (verdict == FILTER) filtered++;
        lastAt = now;
        lastPkg = pkg == null ? "" : pkg;

        RING.addFirst(new Entry(now, pkg, title, text, verdict, reason));
        while (RING.size() > CAPACITY) RING.removeLast();
    }

    /** 最近的记录（新的在前） */
    public static synchronized List<Entry> recent() {
        return new ArrayList<>(RING);
    }

    public static synchronized int size() {
        return RING.size();
    }

    public static synchronized int total() {
        return total;
    }

    public static synchronized int accepted() {
        return accepted;
    }

    public static synchronized int filtered() {
        return filtered;
    }

    public static synchronized long lastAt() {
        return lastAt;
    }

    public static synchronized String lastPkg() {
        return lastPkg;
    }

    /** 清空列表但保留计数器（用户看完想重新观察时用） */
    public static synchronized void clearList() {
        RING.clear();
    }

    /** 面板顶部的一句话结论 —— 把「怎么回事」直接说清楚 */
    public static synchronized String healthLine(long now) {
        if (startAt == 0) {
            return "监听服务未启动。请检查通知使用权是否开启。";
        }
        long mins = Math.max(0, (now - startAt) / 60000);
        if (total == 0) {
            return "已运行 " + mins + " 分钟，还没收到任何通知。"
                    + "若是刚重启手机/刚授权，属正常；否则请确认微信/支付宝通知未被系统关闭。";
        }
        if (accepted == 0) {
            return "已收 " + total + " 条，但全部被过滤。"
                    + "可能通知渠道判断有误，请把下面的原文发给开发者。";
        }
        return "已收 " + total + " 条，其中 " + accepted + " 条进入解析。";
    }
}
