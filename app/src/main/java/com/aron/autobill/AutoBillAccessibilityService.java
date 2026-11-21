package com.aron.autobill;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.core.NotifPolicy;
import com.aron.autobill.core.Prefs;
import com.aron.autobill.core.ScreenPolicy;
import com.aron.autobill.model.Bill;
import com.aron.autobill.model.ParseResult;
import com.aron.autobill.R;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
/**
 * 自动记账无障碍服务 —— 两条采集通道：
 *
 * 1) TYPE_NOTIFICATION_STATE_CHANGED：读通知正文（最重要、最可靠）
 * 2) TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED：抓支付页文本兜底
 *
 * 记账成功后会在屏幕顶部弹一条 heads-up 通知，展示这笔的完整信息。
 */
public class AutoBillAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoBill";

    /** 语义去重时间窗：5 分钟 */
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000L;
    /** 内存指纹窗口：同一条通知文本在 30 分钟内不再重复解析 */
    private static final long FINGERPRINT_TTL_MS = 30 * 60 * 1000L;
    /** 指纹缓存容量（超过则清掉过期项） */
    private static final int FINGERPRINT_MAX = 200;

    private BillStore store;
    private BillParser parser;
    private long lastScreenScan = 0;
    /** 来源包名+通知ID → 上次处理时间；挡住「同一通知被反复投递」 */
    private final java.util.Map<String, Long> noticeKeys = new java.util.HashMap<>();
    /** 正文指纹 → 上次处理时间；挡住「同一文本换了个 NotifID 再来一次」 */
    private final java.util.Map<String, Long> bodyFingerprints = new java.util.HashMap<>();

    // 目标包名白名单：只有这些包的窗口事件才会被处理
    //
    // 注意：这个名单现在**不再是开关**，只是「快速短路表」。
    // 真正的准入判断在 ScreenPolicy（应用级级别 + 商品浏览页识别）。
    // 之所以保留它：无障碍事件频率极高，先在字符串比较层面短路一批，
    // 避免对几百个无关 App 反复做 text 收集（那是真正耗 CPU 的部分）。
    // 名单外的包想读屏，可在设置里显式开启（见 ScreenPolicy.known）。
    private static final Set<String> TARGET_PKGS = new HashSet<>();
    static {
        TARGET_PKGS.add("com.tencent.mm");                   // 微信
        TARGET_PKGS.add("com.eg.android.AlipayGphone");      // 支付宝
        TARGET_PKGS.add("com.sankuai.meituan");              // 美团
        TARGET_PKGS.add("com.sankuai.meituan.takeoutnew");   // 美团外卖
        TARGET_PKGS.add("com.taobao.taobao");                // 淘宝
        TARGET_PKGS.add("com.taobao.idlefish");              // 闲鱼
        TARGET_PKGS.add("com.jingdong.app.mall");            // 京东
        TARGET_PKGS.add("com.xunmeng.pinduoduo");            // 拼多多
        TARGET_PKGS.add("com.ss.android.ugc.aweme");         // 抖音
        TARGET_PKGS.add("com.smile.gifmaker");               // 快手
        TARGET_PKGS.add("com.sdu.didi.psnger");              // 滴滴
        TARGET_PKGS.add("com.MobileTicket");                 // 12306
        TARGET_PKGS.add("com.android.mms");                  // 短信
        TARGET_PKGS.add("com.mipay.wallet");                 // 小米钱包
        TARGET_PKGS.add("com.unionpay.tsmservice.mi");       // 云闪付
        // 注意：不要加 com.android.systemui。
        // 下拉通知栏时它的活动窗口是「整屏通知列表」，collectText 会把所有通知
        // 拼成一大段（实测把「手机可用空间不足3G」「已连接 USB 调试」全吞进原账），
        // 再交给解析器必然错认。通知正文已由 TYPE_NOTIFICATION_STATE_CHANGED 精确取得，
        // 这里不需要这条通道。
        TARGET_PKGS.add("com.aron.autobill");                // 本 App（自检测试通知）
    }

    /** 系统 UI 类包名：只聚合他人内容，永不作为交易来源 */
    private static final Set<String> SYSTEM_UI_PKGS = new HashSet<>();
    static {
        SYSTEM_UI_PKGS.add("com.android.systemui");      // 通知栏/状态栏/锁屏
        SYSTEM_UI_PKGS.add("com.miui.notification");     // MIUI 通知中心
        SYSTEM_UI_PKGS.add("com.android.keyguard");      // 锁屏
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        store = new BillStore(this);
        parser = new BillParser();
        ensureBillChannel();
        Log.i(TAG, "无障碍服务已连接，开始监听交易");
    }

    /** 渠道统一由 Ensure 建，别在这里另建一份：同 ID 两处写法不同会让属性打架 */
    private void ensureBillChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                Ensure.channel(this, nm);
            } catch (Throwable t) {
                Log.w(TAG, "创建通知渠道失败: " + t);
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 保险丝：事件回调里任何未预料的异常都绝不能让服务崩溃
        // （崩一次 = 该服务进入 Crashed 列表 = 之后所有事件全部收不到）
        try {
            dispatch(event);
        } catch (Throwable t) {
            Log.e(TAG, "onAccessibilityEvent 异常: " + t, t);
        }
    }

    private void dispatch(AccessibilityEvent event) {
        int type = event.getEventType();
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();

        // 自己 App 的事件一律忽略（屏幕 + 通知）：
        // 记一笔页/账单列表里就有金额和「支出/收入」字样，
        // 会被解析成假交易（实测把自家界面记成 -18.50 其他）
        if ("com.aron.autobill".equals(pkg)) return;

        // 系统界面一律忽略：通知栏/状态栏/锁屏是「多来源文本的聚合视图」，
        // 里面同时有别人的通知、系统提示、快捷开关名，当成一笔交易解析必错。
        if (SYSTEM_UI_PKGS.contains(pkg)) return;

        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (!TARGET_PKGS.contains(pkg)) return;
            handleNotification(event, pkg);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // 读屏通道的准入：快速表 或 ScreenPolicy 认识的包（银行/运营商等）。
            // 两级判断的理由见 TARGET_PKGS 注释 —— 这里只做廉价短路，
            // 真正的放行/拦截由 ScreenPolicy.decide 决定。
            if (!TARGET_PKGS.contains(pkg) && !ScreenPolicy.known(pkg)) return;
            handleWindow(pkg);
        }
    }

    /** 通知正文是拿到支付通知的最可靠来源 */
    private void handleNotification(AccessibilityEvent event, String pkg) {
        // MIUI 上 getParcelableData() 经常为 null，先尽量拿到完整的 Notification。
        // 拿到与拿不到，走的判定深度不一样（见 deliverNotification）。
        Notification n = null;
        try {
            if (event.getParcelableData() instanceof Notification) {
                n = (Notification) event.getParcelableData();
            }
        } catch (Throwable ignored) {}

        String title = "", text = "", big = "";
        try {
            if (n != null && n.extras != null) {
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                if (t != null) title = t.toString().trim();
                CharSequence b = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (b != null) big = b.toString().trim();
                CharSequence tx = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                if (tx != null) text = tx.toString().trim();
            }
        } catch (Throwable ignored) {}

        String raw = extractNotificationText(event);
        if (raw.isEmpty()) {
            // 兜底：getText()
            StringBuilder sb = new StringBuilder();
            for (CharSequence c : event.getText()) {
                if (c != null) sb.append(c).append(" ");
            }
            raw = sb.toString().trim();
        }
        if (raw.isEmpty()) return;

        long now = System.currentTimeMillis();

        // ---- 第一道闸：通知身份（包名 + notifId + 时间戳/标题）----
        // 同一条通知在通知栏被投递多次（更新进度、点亮屏幕、展开通知）时会重复回调。
        // 用「包名:ID」做键，短时间内重复的直接跳过，连解析都省掉。
        String noteKey = null;
        try {
            if (n != null) {
                CharSequence t = (n.extras == null) ? null
                        : n.extras.getCharSequence(Notification.EXTRA_TITLE);
                noteKey = pkg + ":" + n.when + ":" + (t == null ? "" : t);
            }
        } catch (Throwable ignored) {}
        if (noteKey != null) {
            Long last = noticeKeys.get(noteKey);
            if (last != null && now - last < FINGERPRINT_TTL_MS) {
                Log.i(TAG, "· 跳过重复通知(身份)[" + pkg + "] " + truncate(raw));
                com.aron.autobill.core.NotifLog.record(now, pkg, title, raw,
                        com.aron.autobill.core.NotifLog.DEDUP, "通知身份重复");
                return;
            }
            noticeKeys.put(noteKey, now);
            pruneMap(noticeKeys, now);
        }

        // ---- 第二道闸：正文指纹（同一段文字换 NotifID/换界面再抓一次）----
        String fp = pkg + "|" + raw.replaceAll("\\s+", "");
        Long lastFp = bodyFingerprints.get(fp);
        if (lastFp != null && now - lastFp < FINGERPRINT_TTL_MS) {
            Log.i(TAG, "· 跳过重复通知(指纹)[" + pkg + "] " + truncate(raw));
            com.aron.autobill.core.NotifLog.record(now, pkg, title, raw,
                    com.aron.autobill.core.NotifLog.DEDUP, "正文指纹重复");
            return;
        }
        bodyFingerprints.put(fp, now);
        pruneMap(bodyFingerprints, now);

        Log.i(TAG, "通知[" + pkg + "]: " + raw);
        deliverNotification(pkg, n, title, text, big, raw);
    }

    /**
     * 通知通道的准入与投递。
     *
     * ==================== 为什么必须补这一层 ====================
     *
     * 这个无障碍分支原先绕过了 NotifPolicy，直接把压平文本丢进解析器。
     * 后果是通知监听那边的渠道分流全部作废：
     *   支付宝「双11 消费券已到账 20 元 快去使用」（渠道 100064，策略判丢弃）
     *   在这一路被解析成「收入 ¥20，商户 快去使用，置信度 90」直接入账。
     * 同一条通知，两条通道给出相反结论 —— 这不是精度问题，是记账被写脏。
     *
     * 现在两条通道共用同一套策略与分字段解析：
     *   · 拿得到 Notification 对象 → 完整判断（渠道/组摘要/常驻/会话标题）
     *   · 拿不到（MIUI 常见）→ 至少走包级策略 + 压平文本，并把置信度按策略压低
     */
    private void deliverNotification(String pkg, Notification n, String title,
                                     String text, String big, String raw) {
        long now = System.currentTimeMillis();
        NotifPolicy.Decision d;
        String channelId = "";
        String body = big.isEmpty() ? text : big;

        if (n != null) {
            channelId = BillNotificationListener.channelOf(n);
            // 策略拿的是「标题 + 正文」合并串，与监听通道保持一致
            d = NotifPolicy.decide(pkg, channelId, title,
                    (title + " " + body).trim(),
                    BillNotificationListener.isGroupSummary(n),
                    BillNotificationListener.isOngoing(n),
                    BillNotificationListener.conversationTitle(n));
        } else {
            d = NotifPolicy.decide(pkg, "", "", raw, false, false, "");
        }

        if (d.isSkip()) {
            Log.i(TAG, "· 不记账[" + pkg + "/" + channelId + "] " + d.reason
                    + "  " + truncate(raw));
            com.aron.autobill.core.NotifLog.record(now, pkg, title, raw,
                    com.aron.autobill.core.NotifLog.FILTER, d.reason);
            return;
        }

        if (d.verdict == NotifPolicy.PLACEHOLDER) {
            // 有交易但金额不在通知里（微信转账卡片）：不记假账，提示用户补金额
            Log.i(TAG, "? 待补全[" + pkg + "/" + channelId + "] " + d.reason);
            com.aron.autobill.core.NotifLog.record(now, pkg, title, raw,
                    com.aron.autobill.core.NotifLog.SKIP, "待补全：" + d.reason);
            BillProcessor.notifyPlaceholder(this, pkg, title, raw);
            return;
        }

        com.aron.autobill.core.NotifLog.record(now, pkg, title, raw,
                com.aron.autobill.core.NotifLog.ACCEPT, d.reason);

        if (n != null) {
            // 分字段解析：title 常常就是 App 名，拼进正文会被当成商户
            BillProcessor.processNotification(this, store, parser, pkg,
                    title, text, big, "notification", d.confCap, d.titleAsMerchant);
        } else {
            // 拿不到 Notification：只剩压平文本可解析。
            // 仍走 processNotification，好处是 confCap / titleAsMerchant 能生效，
            // 判定为低可信的通知会被压到门槛以下、进待确认队列而不是直接入账。
            BillProcessor.processNotification(this, store, parser, pkg,
                    "", raw, "", "notification", d.confCap, d.titleAsMerchant);
        }
    }

    /** 清掉过期指纹，避免长跑内存膨胀 */
    private void pruneMap(java.util.Map<String, Long> map, long now) {
        if (map.size() <= FINGERPRINT_MAX) {
            // 小表也顺手清一次过期项（成本极低）
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > FINGERPRINT_TTL_MS) it.remove();
            }
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > FINGERPRINT_TTL_MS) it.remove();
        }
        // 仍然超限：清空重来（宁可多解析一次，也不能让内存无界增长）
        if (map.size() > FINGERPRINT_MAX) map.clear();
    }

    /** 从 Notification.extras 里提取正文（BigText / Title / SubText / TextLines） */
    private String extractNotificationText(AccessibilityEvent event) {
        try {
            if (!(event.getParcelableData() instanceof Notification)) return "";
            Notification n = (Notification) event.getParcelableData();
            StringBuilder sb = new StringBuilder();
            if (n.extras != null && n.extras.getCharSequence(Notification.EXTRA_TITLE) != null) {
                sb.append(n.extras.getCharSequence(Notification.EXTRA_TITLE)).append(" ");
            }
            if (n.extras != null && n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) {
                sb.append(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).append(" ");
            }
            if (n.extras != null && n.extras.containsKey(Notification.EXTRA_TEXT_LINES)) {
                CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) for (CharSequence c : lines) if (c != null) sb.append(c).append(" ");
            }
            if (n.extras != null && n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) != null) {
                sb.append(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).append(" ");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 屏幕采集：抓当前页可见文本（带节流，避免高频卡顿） */
    private void handleWindow(String pkg) {
        long now = System.currentTimeMillis();
        if (now - lastScreenScan < 800) return;   // 800ms 节流
        lastScreenScan = now;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {}
        if (root == null) {
            // 兜底：从窗口列表里取当前活跃窗口
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
                if (wins != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                        if (w != null && w.isActive()) {
                            root = w.getRoot();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (root == null) return;

        // 关键校验：根节点必须真的来自事件所属的包。
        // 否则会出现「事件是 systemui（如通知弹出/Toast），活动窗口却是别的 App」，
        // 把不相干的界面文本当交易解析（实测把自家界面记成"支出 ¥36 淘宝"）。
        try {
            CharSequence rootPkgCs = root.getPackageName();
            String rootPkg = rootPkgCs == null ? "" : rootPkgCs.toString();
            if (!rootPkg.equals(pkg)) {
                try { root.recycle(); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}

        String raw = collectText(root, 0);
        try {
            if (root != null) root.recycle();
        } catch (Throwable ignored) {}

        if (raw.length() < 3) return;

        // ---- 读屏准入：应用级开关 + 商品浏览页识别 ----
        // 这里是「刷一会儿拼多多全是账单」的根治点。
        // 商品列表页每屏几十个「¥9.9」，直接送进解析器必然错记；
        // 再准的解析器也救不了，因为输入本身就是「展示价格」而不是「交易」。
        int userLevel = Prefs.screenLevel(this, pkg);
        ScreenPolicy.Decision sd = ScreenPolicy.decide(pkg, raw, userLevel);
        if (!sd.allow) {
            Log.i(TAG, "· 不读屏[" + pkg + "] " + sd.reason
                    + "  " + raw.substring(0, Math.min(raw.length(), 60)));
            return;
        }

        Log.i(TAG, "屏幕[" + pkg + "] " + sd.reason + " "
                + raw.substring(0, Math.min(raw.length(), 200)));
        tryParseAndStore(pkg, raw, "screen");
    }

    /** 递归收集可见文本（限深度和长度，防止遍历过深） */
    private String collectText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) return "";
        StringBuilder sb = new StringBuilder();
        try {
            CharSequence txt = node.getText();
            if (txt != null && txt.length() > 0) {
                sb.append(txt).append(" ");
            }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) {
                sb.append(desc).append(" ");
            }
            int childCount = 0;
            try {
                childCount = node.getChildCount();
            } catch (Throwable ignored) {}
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    if (child != null) {
                        sb.append(collectText(child, depth + 1));
                    }
                } catch (Throwable t) {
                    // 节点在遍历中失效，忽略该子节点
                } finally {
                    if (child != null) {
                        try { child.recycle(); } catch (Throwable ignored) {}
                    }
                }
                if (sb.length() > 2000) return sb.substring(0, 2000);
            }
        } catch (Throwable t) {
            Log.w(TAG, "collectText 异常: " + t);
        }
        if (sb.length() > 2000) return sb.substring(0, 2000);
        return sb.toString();
    }

    /**
     * 落账：交给共享的 BillProcessor。
     *
     * 为什么抽出去：通知通道（BillNotificationListener）和屏幕通道
     * 的去重、置信度分档、落库、提示必须完全一致，各写一遍迟早走偏。
     * 置信度分档也从「< 50 直接丢弃」改成了「< 50 进待确认队列」。
     */
    private void tryParseAndStore(String pkg, String raw, String channel) {
        if (!Prefs.autoEnabled(this)) return;
        BillProcessor.process(this, store, parser, pkg, raw, channel);
    }

    /**
     * 记账成功提示：仿微信收款到账横幅。
     * 要点（缺一就弹不出横幅）：
     *   1. 渠道 IMPORTANCE_HIGH（Android 8+）
     *   2. PRIORITY_HIGH + 振动/提示音（Android 7-）
     *   3. 通知设置里开启「允许横幅/悬浮通知」——小米默认关，需引导用户
     */
    private void showBillNotification(Bill bill, String category) {
        if (!Prefs.notifyEnabled(this)) return;
        try {
            Intent tap = new Intent(this, MainActivity.class);
            tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, (int) (bill.id & 0xFFFF), tap,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            boolean income = bill.type == 2;
            String merchant = (bill.merchant == null || bill.merchant.isEmpty()
                    || "手动记账".equals(bill.merchant)) ? "未识别商户" : bill.merchant;

            // 标题：仿微信「收到 ¥120.00」/「支出 ¥36.50」
            String title = (income ? "收到 " : "支出 ") + "¥" + fmtYuan(bill.amountFen);

            // 正文：商户 · 分类（一行看完）
            String text = merchant + " · " + category;

            // 展开后：完整明细 + 渠道 + 时间
            StringBuilder big = new StringBuilder();
            big.append(merchant).append(" · ").append(category).append('\n');
            if (bill.note != null && !bill.note.isEmpty() && !"手动记账".equals(bill.note)) {
                big.append("条目：").append(bill.note).append('\n');
            }
            big.append("来源：").append(BillParser.platformName(bill.platform));
            big.append(" · ").append(formatTime(bill.ts)).append('\n');
            big.append("识别度 ").append(bill.confidence).append("% · 已记入账本");

            Notification.Builder b = Ensure.builder(this);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            b.setSmallIcon(R.drawable.ic_notify_bill)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSubText(income ? "收入" : "支出")
                    .setColor(income ? 0xFF27AE60 : 0xFFE74C3C)   // 通知栏着色：绿/红
                    .setStyle(new Notification.BigTextStyle().bigText(big.toString()))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(10000)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setWhen(bill.ts);
            // 提示音 + 振动：微信收款那条的手感
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                b.setDefaults(Notification.DEFAULT_SOUND);
            }

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(2000 + (int) (bill.id & 0x7FF), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "记账提示通知失败: " + t);
        }
    }

    private static String fmtYuan(long fen) {
        long abs = Math.abs(fen);
        long y = abs / 100;
        long f = abs % 100;
        return f == 0 ? String.valueOf(y) : String.format("%d.%02d", y, f);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private String formatTime(long ts) {
        try {
            return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date(ts));
        } catch (Throwable t) {
            return "??:??:??";
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }
}
