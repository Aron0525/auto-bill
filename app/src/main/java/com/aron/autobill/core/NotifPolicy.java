package com.aron.autobill.core;

/**
 * 通知准入策略 —— 在**解析器之前**先做一道判断。
 *
 * 为什么需要这一层：
 *   解析器再准，也只能在"拿到文本"之后判断。但大量通知**根本不该进解析器**：
 *     · 支付宝的活动通知、订阅消息（纯营销）
 *     · 微信的通知组摘要（「3条新消息」）
 *     · 常驻通知（正在通话、音乐播放器）
 *     · 转账卡片占位符（只有「[转账]」三个字，金额在 App 里）
 *
 *   这些送进去只会得到两种结果：拒收（白跑）或错记（更糟）。
 *   真正危险的是后者 —— 微信群里有人发「转账 200」会被记成一笔账。
 *
 * 渠道 ID 是最可靠的免费信号。真机 dumpsys 实测：
 *
 *   微信 com.tencent.mm：
 *     message_channel_new_id          新消息通知          imp=4
 *     reminder_channel_id             其他通知            imp=2
 *     voip_norify_channel_silent*     音视频通话邀请通知    imp=4
 *
 *   支付宝 com.eg.android.AlipayGphone：
 *     mipush|...|100060   交易与账号安全通知   imp=3   ← 交易
 *     mipush|...|100058   朋友消息提醒        imp=3   ← 聊天
 *     mipush|...|100061   订单物流          imp=3   ← 物流
 *     mipush|...|100062   订阅消息          imp=3   ← 营销
 *     mipush|...|100064   活动通知          imp=3   ← 营销
 *
 *   App 自己已经标好了品类，我们不该假装看不见。
 *
 * 设计原则：**宁可漏，不可错**。
 *   拿不准时给 LOW（解析但压置信度 → 进待确认队列），而不是 PARSE。
 *   只有确证是营销/聚合/占位的才 SKIP。
 */
public final class NotifPolicy {

    private NotifPolicy() {}

    // ==================== 判定结果 ====================

    /** 正常解析，不额外限制 */
    public static final int PARSE = 0;
    /** 解析，但把置信度压到上限之下（进待确认队列，不直接入账） */
    public static final int LOW = 1;
    /** 直接丢弃，连解析都不做 */
    public static final int SKIP = 2;
    /** 有交易发生，但金额不在通知里（转账卡片）—— 提示用户去 App 看 */
    public static final int PLACEHOLDER = 3;

    public static final class Decision {
        public int verdict = PARSE;
        /** 人类可读的原因，写进日志便于排查 */
        public String reason = "";
        /** 置信度上限：LOW 时压到 CONF_AUTO 以下 */
        public int confCap = 100;
        /** 标题能否当商户名（群聊/联系人名一律否） */
        public boolean titleAsMerchant = true;

        Decision(int v, String why) { verdict = v; reason = why; }
        Decision cap(int c) { confCap = Math.min(confCap, c); return this; }
        Decision noTitleMerchant() { titleAsMerchant = false; return this; }

        public boolean shouldParse() { return verdict == PARSE || verdict == LOW; }
        public boolean isSkip() { return verdict == SKIP; }

        @Override public String toString() { return verdictName(verdict) + "(" + reason + ")"; }
    }

    public static String verdictName(int v) {
        switch (v) {
            case PARSE: return "解析";
            case LOW: return "低可信";
            case SKIP: return "丢弃";
            case PLACEHOLDER: return "待补全";
            default: return "?";
        }
    }

    // ==================== 入口 ====================

    /**
     * 决定一条通知怎么处理。
     *
     * @param pkg               来源包名
     * @param channelId         通知渠道 ID（API 26+；老系统传空串）
     * @param title             通知标题
     * @param text              通知正文（bigText 优先）
     * @param groupSummary      是否通知组摘要
     * @param ongoing           是否常驻通知（FLAG_ONGOING_EVENT）
     * @param conversationTitle 会话标题（聊天类通知才有，群名/对方名）
     */
    public static Decision decide(String pkg, String channelId, String title, String text,
                                  boolean groupSummary, boolean ongoing, String conversationTitle) {
        String p = pkg == null ? "" : pkg;
        String ch = channelId == null ? "" : channelId;
        String t = title == null ? "" : title;
        String body = text == null ? "" : text;
        String conv = conversationTitle == null ? "" : conversationTitle;
        String all = (t + " " + body).trim();

        // ---- ① 通知组摘要：它只是别人通知的聚合，本身没有新信息 ----
        // 微信「3条新消息」、支付宝「2条新通知」都会走这里。
        // 让它进解析器的后果是被当成一笔账 —— 用户看到凭空多出的支出。
        if (groupSummary) {
            return new Decision(SKIP, "通知组摘要").noTitleMerchant();
        }

        // ---- ② 常驻通知：正在通话/音乐播放/充电中，不是交易 ----
        if (ongoing) {
            return new Decision(SKIP, "常驻通知").noTitleMerchant();
        }

        // ---- ③ 聊天会话：标题是人名/群名，绝不能当商户 ----
        // 微信群里「张三: 转你200」如果被记成一笔账，
        // 商户名会变成「工作群」—— 这类错账最难解释。
        boolean isChat = !conv.isEmpty();
        Decision d;

        if (!ch.isEmpty()) {
            d = byChannel(p, ch, all);
            if (d == null) d = byPackage(p, all);
        } else {
            // 老系统没有渠道 ID，只能靠包名 + 文本
            d = byPackage(p, all);
        }

        // ---- ③b 没有会话标题也要能认出来 ----
        // 【真机教训】EXTRA_CONVERSATION_TITLE 不是可靠信号：
        //   · MIUI 部分版本不填充这个字段
        //   · 用 `cmd notification post` 发的通知根本没有
        //   · 微信某些通知类型（群消息折叠后）也可能不带
        //
        // 实测翻车记录：标题「工作群」+ 正文「张三: 已转账 200.00元」
        // 最终记成 商户=工作群 conf=98 直接入账 —— 账本被写脏了。
        //
        // 所以必须补一条**基于文本结构**的判断：
        // 「发送者: 内容」这种格式是聊天消息的铁证，支付凭证绝不会长这样。
        boolean looksLikeChatMsg = looksLikeChatMessage(body, t);

        if (isChat || looksLikeChatMsg) {
            // 聊天类通知即便含支付语义，也多半是**好友描述**而非真实交易。
            // 真交易（微信支付官方号）不会有 conversationTitle。
            //
            // 注意：不能只在 verdict==PARSE 时才压低。
            // byPackage 对「张三: 转你200」这类文本本就返回 LOW（没命中"转账"二字），
            // 若只在 PARSE 分支处理，confCap 会一直停在 100 —— 聊天内容照样能直接入账。
            // 只要不是 SKIP/PLACEHOLDER，聊天会话一律压到 CONF_AUTO 以下。
            if (d.verdict == PARSE || d.verdict == LOW) {
                String why = isChat ? ("聊天会话(" + conv + ")") : "疑似聊天消息";
                d = new Decision(LOW, why).cap(50).noTitleMerchant();
            }
        }

        // ---- ④ 占位符：有交易但没有金额 ----
        // 微信转账卡片通知栏只显示「[转账]」，金额必须进 App 才看得到。
        // 这种情况不能假装记成功，也不能假装没发生 —— 转成「待补全」。
        //
        // 注意 body 可能是剥过标题的纯正文，也可能是「李四 [转账]」这种拼接结果
        // （调用方传的是 f.flat）。两种都必须认出来，否则真机上恰好走到拼接路径就漏了。
        if (d.verdict != SKIP && isPlaceholder(stripSpeaker(body, t))) {
            return new Decision(PLACEHOLDER, "金额不在通知里").noTitleMerchant();
        }

        return d;
    }

    // ==================== 渠道级规则 ====================

    /** 已知渠道的白名单/黑名单。返回 null = 该包没有渠道级规则，退回包级判断 */
    private static Decision byChannel(String pkg, String ch, String all) {
        if (pkg.equals("com.eg.android.AlipayGphone")) {
            if (ch.contains("100060")) {
                // 交易与账号安全：支付宝明确标注的交易渠道
                return new Decision(PARSE, "支付宝-交易渠道");
            }
            if (ch.contains("100062")) {
                // 订阅消息：营销推送，永远不是交易
                return new Decision(SKIP, "支付宝-订阅消息");
            }
            if (ch.contains("100064")) {
                // 活动通知：双11、领券、满减，误记的高发区
                return new Decision(SKIP, "支付宝-活动通知");
            }
            if (ch.contains("100058")) {
                // 朋友消息：聊天，金额是聊天内容不是交易
                return new Decision(SKIP, "支付宝-朋友消息").noTitleMerchant();
            }
            if (ch.contains("100061")) {
                // 订单物流：可能有金额（运费），但它是订单进度不是扣款
                return new Decision(LOW, "支付宝-订单物流").cap(45);
            }
            return null;   // 未知渠道，交给包级判断
        }

        if (pkg.equals("com.tencent.mm")) {
            if (ch.contains("voip")) {
                // 音视频通话邀请，和钱无关
                return new Decision(SKIP, "微信-通话邀请");
            }
            if (ch.contains("reminder_channel")) {
                // 「其他通知」：微信的低优先提醒，多为运营消息
                return new Decision(LOW, "微信-其他通知").cap(45);
            }
            if (ch.contains("message_channel")) {
                // 新消息通知：微信唯一的主渠道，支付凭证和聊天都走这里。
                // **无法靠渠道区分**，只能看文本（下面 byPackage 会处理）。
                return null;
            }
            return null;
        }

        return null;
    }

    // ==================== 包级规则 ====================

    private static Decision byPackage(String pkg, String all) {
        // 微信/支付宝是用户指定的主渠道，只要文本有交易语义就放行，
        // 交给解析器 + 置信度分档去把关。
        if (pkg.equals("com.tencent.mm")) {
            return new Decision(hasPaySemantic(all) ? PARSE : LOW, "微信-文本判定");
        }
        if (pkg.equals("com.eg.android.AlipayGphone")) {
            return new Decision(hasPaySemantic(all) ? PARSE : LOW, "支付宝-文本判定");
        }
        // 其他 App：没有渠道信息时保守处理
        return new Decision(hasPaySemantic(all) ? PARSE : LOW, "通用-文本判定");
    }

    // ==================== 文本工具 ====================

    /** 交易语义词（与 BillNotificationListener 的预筛保持一致，但这里多给 LOW 而不是直接丢） */
    private static final String[] PAY_WORDS = {
            "支付", "付款", "消费", "扣款", "交易", "到账", "入账", "退款", "实付",
            "订单", "收款", "转账", "支出", "收入", "扣费", "账单", "充值", "提现", "还款"
    };

    static boolean hasPaySemantic(String s) {
        for (String w : PAY_WORDS) if (s.contains(w)) return true;
        return hasAmount(s);
    }

    /** 文本里有没有像金额的东西 */
    static boolean hasAmount(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.matches(".*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?.*")
                || s.matches(".*\\d+(?:\\.\\d{1,2})?\\s*元.*")
                || s.matches(".*\\d+\\.\\d{2}.*");
    }

    /**
     * 剥掉拼在正文前面的标题，拿到真正的消息体。
     *
     * 调用方（BillNotificationListener）传进来的是 f.flat = 「标题 + 正文」，
     * 于是「李四」+「[转账]」变成「李四 [转账]」，占位符正则就匹配不上了。
     * 真机实测：正是这个原因导致转账卡片没有走「待补全」，白白丢了一条交易提醒。
     */
    static String stripSpeaker(String body, String title) {
        if (body == null) return "";
        String s = body.trim();
        if (title == null) return s;
        String t = title.trim();
        if (t.isEmpty()) return s;
        // 只有「标题 + 空白 + 剩余」这种结构才剥，避免误伤
        if (s.startsWith(t)) {
            String rest = s.substring(t.length()).trim();
            if (!rest.isEmpty()) return rest;
        }
        return s;
    }

    /**
     * 占位符正文判定。
     *
     * 微信转账/红包的通知栏长这样：
     *   text = "[转账]"
     *   bigText = "[转账] 请收款"
     *
     * 共同特征：短、被方括号包住、**没有金额**。
     * 这种通知代表"有一笔交易发生了"，但金额不在这里，
     * 所以不能当普通账单解析（会解析失败或金额错），也不能丢弃（用户要知道）。
     */
    static boolean isPlaceholder(String body) {
        if (body == null) return false;
        String s = body.trim();
        // 已经带金额的不算占位符
        if (hasAmount(s)) return false;
        // 形如「[转账]」「[微信红包]」「[转账] 请收款」「[图片]」
        return s.matches("^\\[[^\\]]{1,12}\\](?:\\s*[\\u4e00-\\u9fa5]{0,10})?$")
                // 纯符号 + 极短中文也常是占位
                || (s.length() <= 10 && s.matches("^[\\[\\]【】\\u4e00-\\u9fa5]{2,10}$")
                    && containsPlaceholderWord(s));
    }

    private static final String[] PLACEHOLDER_WORDS = {
            "转账", "红包", "语音", "图片", "视频", "文件", "位置", "名片", "动画表情", "链接"
    };

    private static boolean containsPlaceholderWord(String s) {
        for (String w : PLACEHOLDER_WORDS) if (s.contains(w)) return true;
        return false;
    }

    /**
     * 聊天消息的文本结构识别。
     *
     * 微信/支付宝的聊天通知正文是「发送者: 内容」：
     *   张三: 已转账 200.00元
     *   李四：晚上一起吃饭吗
     *   王五: [转账]
     *
     * 而支付凭证**绝不会**有这个前缀，它长这样：
     *   支付成功 ¥128.50 收款方：星巴克咖啡
     *   付款成功 -56.80元 付款给 麦当劳
     *
     * 判别要点：
     *   1. 冒号（中英文都算）在**前 12 个字符内**
     *   2. 冒号左边是一个短的、不含金额的名字
     *   3. 冒号左边不含「收款方/付款给/商户」等凭证标签
     *
     * 第 3 点很关键：「收款方：星巴克」也有冒号，但左边是标签不是人名。
     * 漏掉这一条会把正常支付凭证全判成聊天，那就从"记错"变成"不记"了。
     */
    static boolean looksLikeChatMessage(String body, String title) {
        if (body == null) return false;
        String s = body.trim();
        if (s.isEmpty()) return false;

        int colon = -1;
        for (int i = 0; i < s.length() && i < 16; i++) {
            char c = s.charAt(i);
            if (c == ':' || c == '：') { colon = i; break; }
        }
        if (colon <= 0) return false;

        String speaker = s.substring(0, colon).trim();
        // 发送者名：1~12 个字，不能含数字/金额符号（含了就多半是凭证标签）
        if (speaker.isEmpty() || speaker.length() > 12) return false;
        if (speaker.matches(".*[\\d¥￥%.].*")) return false;

        // 冒号左边是凭证标签 → 不是聊天
        for (String tag : VOUCHER_LABELS) {
            if (speaker.contains(tag)) return false;
        }
        // 标题本身就是 App 名/支付渠道 → 是官方通知，不是聊天
        if (title != null) {
            String t = title.trim();
            for (String app : APP_LIKE_TITLES) {
                if (t.contains(app)) return false;
            }
        }
        return true;
    }

    /** 支付凭证里的「标签：值」结构，左边这些词说明冒号不是聊天分隔符 */
    private static final String[] VOUCHER_LABELS = {
            "收款方", "付款方", "商户", "订单", "商品", "金额", "时间", "支付方式",
            "付款给", "收款人", "交易", "账号", "尾号", "卡号", "备注", "说明"
    };

    /** App 名 / 官方支付渠道名：这些标题出现时，正文是官方凭证不是聊天 */
    private static final String[] APP_LIKE_TITLES = {
            "微信支付", "支付宝", "微信", "财付通", "银联", "云闪付"
    };
}
