package com.aron.autobill.core;

import com.aron.autobill.model.ParseResult;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交易文本解析引擎
 *
 * 严格按《记账信息处理标准 v1》（docs/记账信息处理标准.md）实现：
 *   归一化 → 噪音拦截 → 否定语境 → 金额提取(带校验) → 方向判定
 *   → 商户提取 → 条目提取 → 置信度
 *
 * 铁律：宁可不记，不可错记。
 */
public class BillParser {

    // ==================== ① 归一化 ====================

    static String normalize(String s) {
        if (s == null) return "";
        // 异体字/繁体归一必须**最先**做：
        // 短信和 App 通知里常见繁体或异体字（「余額宝」「支付金颉」），
        // 不归一的话渠道词、标签词全都匹配不上，
        // 真机实测「微信支付 商戶全称 星巴克 支付金颉 128.50 余額宝」
        // 商户会退化取到「余額宝」而不是星巴克。
        s = normalizeVariant(s);
        s = s.replace('，', ',').replace('。', '.').replace('：', ':')
             .replace('；', ';').replace('（', '(').replace('）', ')')
             .replace('￥', '¥')
             .replaceAll("[\\u200B-\\u200F\\uFEFF]", "");
        // 千分位：1,280.00 → 1280.00（仅当逗号后正好 3 位数字且前面也是数字）
        s = s.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** 复用 OcrTemplates 的异体字表，避免两处维护两份映射 */
    private static String normalizeVariant(String s) {
        return OcrTemplates.norm(s);
    }

    // ==================== ② 噪音拦截 ====================

    /** 硬噪音：命中即丢弃 */
    private static final String[] BLOCK_KEYWORDS = {
            "验证码", "校验码", "动态码", "登录", "注册", "密码", "安全提示",
            "抽奖", "广告", "关注", "点赞", "砍价", "助力", "返现活动",
            "满减", "券后", "限时优惠", "立即领取", "可提现", "奖励已到账", "恭喜您", "恭喜你",
            "签到得", "新人礼", "红包雨", "抽奖赢", "免费领",
            "系统更新", "升级", "安装", "步行", "运动步数", "天气", "新闻", "资讯", "头条",
            "贷款", "额度", "征信", "赚钱", "兼职", "相亲",
            // 营销奖励/活动文案（真实交易不会这样说；实测中行短信「首笔消费至高返5美元」被误记 5 元）
            "至高", "享双重礼", "笔笔返", "返现", "活动需要", "先报名",
            "回复R", "拒收请", "退订", "绑卡", "首笔", "赠", "领取资格", "限时领",
            // 抽奖类：真机验证「恭喜抽中88元红包，点击查看」被记成 88 元支出。
            // 已有的「抽奖」拦不住「抽中」这个变体 —— 营销话术会换词，规则得跟上。
            // 这两个词在真实账单里出现概率极低，直接整条拦。
            "抽中", "中奖", "抽到", "摇一摇"
    };

    /**
     * 需要上下文判断的拦截词：用正则避免误伤正常表述。
     * 「消费金」是营销词（返1%消费金），但「消费金额36.50元」是正常账单，
     * 直接放 BLOCK_KEYWORDS 会把大量真实通知整条丢掉。
     */
    private static final Pattern[] BLOCK_PATTERNS = {
            Pattern.compile("消费金(?!额)"),
            // 「优惠券」不能无条件拦：真机实测
            //   「订单支付成功 商品总价 599.00 优惠券 -100.00 实付款 499.00 优衣库」
            // 是真实订单，却因含「优惠券」被整条丢掉（未解析）。
            // 只有「领券/抢券/送券」这类纯营销语境才拦。
            //
            // 注意「送」必须排除「发送/寄送」：
            // 真机实测「你已成功发送红包 金额 ¥88.00 收款人 张小明」是**真实支出**，
            // 却被「发送红包」里的「送红包」误拦（规则基线从 8/8 掉到 7/8）。
            Pattern.compile("(?:领|抢|抽取|赠|瓜分|免费领取|领取)\\s*[\\u4e00-\\u9fa5]{0,4}(?:券|红包|礼包)"),
            Pattern.compile("(?<!发|寄|投)(?:送)\\s*[\\u4e00-\\u9fa5]{0,2}(?:券|礼包)"),
            Pattern.compile("(?:券|红包)(?:即将)?(?:过期|到期|可领|待领|使用)"),
            // 优惠券/积分/奖励的**发放**通知：没有资金流动，不该记账。
            // 注意不含「红包」——「红包 88 元已到账」是真实的钱进来。
            Pattern.compile("(?:优惠券|代金券|积分|奖励|礼包)[^\\n]{0,10}(?:已发放|已到账|可领取|已入账)"),
    };

    /** 噪音拦截：关键词 + 上下文正则 */
    static boolean blocked(String s) {
        if (containsAny(s, BLOCK_KEYWORDS)) return true;
        for (Pattern p : BLOCK_PATTERNS) if (p.matcher(s).find()) return true;
        return false;
    }

    // ==================== ③ 否定 / 反向语境 ====================

    /** 命中即判定「不是用户支出」，整条丢弃 */
    private static final String[] DROP_CONTEXTS = {
            "由商家承担", "商家承担", "由平台承担", "平台承担", "由卖家承担",
            "代付", "代收", "垫付",
            "已取消", "订单取消", "支付失败", "交易失败", "支付超时", "订单已关闭",
            "红包过期", "红包已退回"
    };

    /** 退款/退回语境：钱回来了 → 收入 */
    private static final String[] REFUND_WORDS = {
            "退回", "退还", "返还", "原路退回", "退款"
    };

    // ==================== ④ 金额提取 ====================

    private static final Pattern P_CURRENCY = Pattern.compile("[¥]\\s*(\\d{1,7}(?:[.,]\\d{1,2})?)");
    // 前缀词与金额之间的间隔：OCR 的多列对齐会用大量空格补位（实测「实付」到「99.92」
    // 之间有 16 个空格），所以不能限制成 {0,8}，但也不能无限放宽——
    // 用「非数字且不含换行」的 0~30 个字符，既容纳对齐空白，又不会跨行误吞。
    private static final Pattern P_PREFIX = Pattern.compile(
            "(?:金额|消费|支付|付款|扣款|支出|到账|收款|收款金额|退款|入账|转入|转出|应付|实付|" +
            "合计|总计|小计|实付款|共|总计金额|交易金额|订单金额)" +
            "[^0-9¥\\-\\n]{0,30}([¥]?\\d{1,7}(?:[.,]\\d{1,2})?)");
    private static final Pattern P_UNIT = Pattern.compile("(\\d{1,7}(?:[.,]\\d{1,2})?)\\s*(?:元|块)");
    private static final Pattern P_NEG = Pattern.compile("[¥]?\\s*-\\s*(\\d{1,7}(?:[.,]\\d{1,2})?)");

    /**
     * 商户标签，**必须按长度降序**。
     * 否则 indexOf("商户") 会先命中「商户全称 华莱士」，剥出「全称 华莱士」。
     * 真机实测的三类错账都在这个表里修好：
     *   「商户名称 京东商城」→ 京东商城（旧逻辑剥出「名称」）
     *   「收款人 张小明」    → 张小明  （旧逻辑漏了「收款人」）
     *   「商户全称 华莱士」  → 华莱士  （旧逻辑剥出「全称 华莱士」）
     */
    private static final String[] MERCHANT_LABELS = {
            "商户全称", "商户名称", "收款方名称", "收款方", "收款人", "付款方", "付款人",
            "交易对方", "对方户名", "商户", "商家", "店铺", "门店", "卖家", "店铺名称",
            "对方", "户名", "收款单位", "收款账户",
    };

    /**
     * 银行/机构名提取。
     * 治疗「【招商银行】您账户1234于…入账人民币12000.00元」这种整句短信：
     * 旧逻辑把整句当候选，因超过 30 字被 plausibleMerchantLine 拒掉，
     * 最后退化到平台名「短信」。
     */
    private static final Pattern P_BANK = Pattern.compile(
            "[【\\[]([^】\\]]{2,12}(?:银行|信用社|支付宝|微信|财付通))[】\\]]");
    private static final Pattern P_BANK_PLAIN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8}(?:银行|信用社))");

    /**
     * 【机构名】前缀里也常放**业务名**而非机构名：
     *   「【招商银行信用卡】本期账单已还清」
     *   「【光大银行信用卡】消费提醒」
     * 这些也要能剥出「招商银行」而不是整段或不剥。
     */
    private static final Pattern P_BANK_CARD = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,8}(?:银行|信用社))(?:信用卡|储蓄卡|借记卡|卡)?");

    /**
     * 把「不可能是金额」的数字先遮盖掉，避免订单号/日期/验证码被当成钱。
     * 这一步是误判率最大的来源，必须放在金额提取之前。
     */
    static String maskNonAmount(String s) {
        // 日期/时间（必须最先遮掉，否则 11-08 / 20:43 会被当金额）
        s = s.replaceAll("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}\\s*日?", " [D] ");
        s = s.replaceAll("\\d{1,2}月\\d{1,2}日", " [D] ");
        s = s.replaceAll("\\d{1,2}[-/]\\d{1,2}(?!\\d)", " [D] ");
        s = s.replaceAll("\\d{1,2}:\\d{2}(?::\\d{2})?", " [T] ");
        s = s.replaceAll("(?:订单号|订单|单号|流水号|交易号|编号|卡号|尾号)[:：]?\\s*[A-Za-z0-9]+", " [NO] ");
        s = s.replaceAll("验证码\\s*\\d{4,8}", " [CODE] ");
        s = s.replaceAll("1[3-9]\\d{9}", " [PHONE] ");
        s = s.replaceAll("\\d+\\s*(?:个|条|件|人|名|位|次|天|小时|分钟|秒|岁|张|台|份|杯|瓶|页|楼|层)", " [CNT] ");
        // 「共2笔交易」「分3期」「剩余6期」——这些数字是计数不是金额。
        // 真机实测：工商银行短信「共2笔交易：10:23消费支出58.00元」
        // 旧逻辑把「2」当成了本笔金额，账记成 2.00 元（金额全错）
        s = s.replaceAll("共\\s*\\d+\\s*(?:笔|次|条|期)", " [CNT] ");
        s = s.replaceAll("\\d+\\s*(?:笔|期)\\s*(?:交易|账单|还款)?", " [CNT] ");
        s = s.replaceAll("(?:剩余|还有|分)\\s*\\d+\\s*期", " [CNT] ");
        s = s.replaceAll("第\\s*\\d+\\s*期", " [CNT] ");
        s = s.replaceAll("\\d+%", " [PCT] ");
        s = s.replaceAll("满\\d+减\\d+", " [PROMO] ");
        s = s.replaceAll("\\d{8,}", " [LONGNUM] ");
        return s;
    }

    private static Long extractAmount(String masked) {
        // P_PREFIX 最先：「确认金额99.92元」「消费1,280.00元」这类带前缀的金额
        // 优先于裸的「¥/元」匹配，避免先撞上时间/序号里的数字
        for (Pattern p : new Pattern[]{P_PREFIX, P_CURRENCY, P_UNIT, P_NEG}) {
            Matcher m = p.matcher(masked);
            if (m.find()) {
                Long fen = toFen(m.group(1));
                if (fen != null && fen > 0 && fen <= 100_000_000L) return fen;
            }
        }
        return null;
    }

    /**
     * 一个候选金额：值 + 紧邻它的标签文字。
     */
    private static final class AmtCand {
        final long fen;
        final String lead;      // 金额**前面**紧邻的几个字，用于识别「实付/小计/优惠」标签
        AmtCand(long fen, String lead) { this.fen = fen; this.lead = lead; }
    }

    /**
     * 紧邻金额的标签词表。只认**紧贴**在金额前面（6 字内）的标签，
     * 不做整句扫描 —— 否则邻居的「实付」会污染「配送费 4.00」的评分。
     *
     * 分级依据：这是「这笔钱最终从/到用户账户流了多少」，
     * 而不是「订单原价多少」。
     */
    /** 最高优先：钱**最终**从/到用户账户的金额。基金场景尤其关键—— */
    /** 「赎回金额 5000」是名义本金，「实际到账 4995」才是真流向。 */
    private static final String[] AMT_TOP = {
            "实付", "实际支付", "实付款", "实际到账", "实收", "实际付款",
    };

    private static final String[] AMT_STRONG = {
            // 最强：最终支付/到账金额
            "实付", "实际支付", "实付款", "实际到账", "实收", "实际付款",
            "到账", "入账", "收款", "本次支付", "已支付",
            // 次强：订单级金额
            "应付", "合计", "总计", "支付金额", "付款金额", "订单金额",
            "交易金额", "支付", "消费", "支出", "扣款", "扣除", "金额",
    };
    private static final String[] AMT_WEAK = {
            "小计", "商品金额", "商品总价", "总价", "配送费", "运费", "优惠",
            "折扣", "红包", "积分", "手续费", "服务费", "包装费", "餐盒费",
            "原价", "单价", "数量", "余额", "剩余", "可用", "额度",
            // 赎回/申购本金：真正到账才是流向，本金只是名义金额
            "赎回金额", "申购金额", "本金",
    };

    /**
     * 收集文本里**所有**候选金额，按业务优先级择优。
     *
     * 为什么必须这样：旧实现 extractAmount 只返回**第一个**匹配，
     * 真机实测踩过两次严重的金额错账：
     *   「商品小计 47.20 配送费 4.00 优惠 -8.00 实付 39.20」
     *     → 取到 47.20（小计），真正的支付金额是 39.20
     *   「淘宝 实付款 268.00 商品总价 328.00」
     *     → 若「商品总价」在前就会取到 328.00
     *
     * 金额记错比商户记错严重得多 —— 账本直接失真。所以逐个候选打分。
     *
     * 候选资格（必须有货币证据，避免银行卡尾号/序号混进来）：
     *   · 带 ¥ / ￥ 前缀，或
     *   · 带「元/块」后缀，或
     *   · 前面 6 字内紧贴交易标签（「实付 39.20」这种没有¥和元的）
     */
    private static AmtCand pickAmount(String masked) {
        java.util.List<AmtCand> cands = new java.util.ArrayList<>();
        java.util.regex.Pattern all = java.util.regex.Pattern.compile(
                "[¥￥]\\s*(-?\\s*\\d{1,7}(?:[.,]\\d{1,2})?)"
                        + "|(-?\\s*\\d{1,7}(?:[.,]\\d{1,2})?)\\s*(?:元|块)"
                        + "|(-?\\s*\\d{1,7}[.,]\\d{1,2})");
        Matcher m = all.matcher(masked);
        while (m.find()) {
            String numStr = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            Long fen = toFen(numStr);
            if (fen == null || fen <= 0 || fen > 100_000_000L) continue;

            int leadStart = Math.max(0, m.start() - 6);
            String lead = masked.substring(leadStart, m.start()).trim();
            boolean hasCurrency = m.group(1) != null
                    || (m.group(2) != null && masked.substring(m.start(), m.end()).contains("元"))
                    || masked.substring(m.start(), m.end()).contains("块");
            boolean hasLabel = false;
            for (String w : AMT_STRONG) if (lead.contains(w)) { hasLabel = true; break; }
            if (!hasLabel) for (String w : AMT_WEAK) if (lead.contains(w)) { hasLabel = true; break; }

            // 既无货币标记又无紧邻标签 → 是序号/尾号，不是金额
            if (!hasCurrency && !hasLabel) continue;

            cands.add(new AmtCand(fen, lead));
        }
        if (cands.isEmpty()) return null;

        AmtCand best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < cands.size(); i++) {
            AmtCand c = cands.get(i);
            int sc = 50;
            // 最高优先：最终支付/到账金额（实付、实际到账、实收…）
            for (String w : AMT_TOP) {
                if (c.lead.contains(w)) { sc = 130; break; }
            }
            if (sc < 130) {
                for (String w : AMT_STRONG) if (c.lead.contains(w)) { sc = 100; break; }
            }
            if (sc < 100) {
                for (String w : AMT_WEAK) if (c.lead.contains(w)) { sc = 15; break; }
            }
            // 同分时信靠前的（正文通常先写本笔金额，后写余额/明细）
            sc = sc * 100 - i;
            if (sc > bestScore) { bestScore = sc; best = c; }
        }
        return best;
    }

    // ==================== ④.4 OCR 小数点修复 ====================

    /**
     * OCR 专用金额提取：容忍「光秃秃的数字」。
     *
     * 通知栏文本里金额几乎总带 ¥ / 元 / 前缀词，但 OCR 出来后经常只剩一个数字：
     * 微信凭证的「¥36.50」被拆成单独一行「36.50」，支付宝的「支付金额」换行写「36.50」。
     * 通用 extractAmount 要求必须带货币标记或前缀词，这类就全漏了。
     *
     * 规则（严格限制，避免误伤）：
     *   - 必须是带 1~2 位小数的数字（36.50 / 1,280.00），纯整数不认
     *     （纯整数可能是数量、序号、页码，风险太高）
     *   - 整行只允许这个数字和少量符号，不能夹中文
     *   - 值必须落在合理金额区间 0.01 ~ 1,000,000
     */
    private static final Pattern P_OCR_BARE = Pattern.compile(
            "^[\\s¥￥:：]*([1-9]\\d{0,6}(?:[.,]\\d{1,2}))[\\s元]*$");

    static Long extractOcrAmount(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;
        // 先走通用规则（带 ¥/元/前缀词的）
        Long normal = extractAmount(maskNonAmount(t));
        if (normal != null && normal > 0) return normal;
        // 再试「裸小数」：整行只有一个带小数的数字
        Matcher m = P_OCR_BARE.matcher(t);
        if (m.find()) {
            Long fen = toFen(m.group(1));
            if (fen != null && fen > 0 && fen <= 100_000_000L) return fen;
        }
        return null;
    }

    private static final Pattern P_OCR_AMOUNT = Pattern.compile(
            "(金额|实付|应付|合计|总计|支付|付款|收款|消费|扣款|到账|退款|小计|订单金额|交易金额)" +
            "([^0-9]{0,6})([1-9]\\d{2,7})(\\s*)(元|¥|￥|$)");

    /**
     * OCR 场景的金额修复：把被吞掉 / 认错的小数点补回来。
     *
     * 实测 ML Kit 最常见的三种错法（正确金额 → 识别结果）：
     *   99.92  →  9992    小数点被完全吞掉（数字粘连）
     *   99.92  →  99,92   小数点被认成逗号
     *   99.92  →  99 .92  小数点前后被拆开
     *
     * 策略（按可信度从高到低）：
     *   1) 金额前缀词 + 纯整数 + 元/¥/行尾 → 末两位是分，补小数点
     *   2) 逗号后正好 1~2 位 → 视为小数点（但不碰 1,280.00 这种千分位）
     *   3) 小数点两侧被空格拆开 → 拼接
     *
     * 关键约束：只对「明确是金额的位置」修复，绝不全局补点。
     * 把订单号 20261106 补成 202611.06 是灾难，所以必须有前缀词或「元」兜底。
     */
    public static String fixOcrAmount(String s) {
        if (s == null || s.isEmpty()) return "";

        Matcher m = P_OCR_AMOUNT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String gap = m.group(2);
            String digits = m.group(3);
            String space = m.group(4);
            String unit = m.group(5);
            if (digits.contains(".")) {   // 已带小数，不动
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            // 末两位是分：9992 → 99.92
            String fixed = digits.substring(0, digits.length() - 2) + "."
                    + digits.substring(digits.length() - 2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    prefix + gap + fixed + space + unit));
        }
        m.appendTail(sb);
        s = sb.toString();

        // (1b) 「¥9992」这种带货币符号、无前缀词的纯整数 → ¥99.92
        //      有 ¥ 就是明确的钱，可以放心按「末两位是分」还原。
        //      长度 ≥4 才处理：¥999 更可能是 ¥999 整，而不是 ¥9.99
        java.util.regex.Matcher mc = java.util.regex.Pattern.compile(
                "([¥])\\s*([1-9]\\d{3,7})(?!\\d|\\.)").matcher(s);
        StringBuffer sb2 = new StringBuffer();
        while (mc.find()) {
            String digits = mc.group(2);
            String fixed = digits.substring(0, digits.length() - 2) + "."
                    + digits.substring(digits.length() - 2);
            mc.appendReplacement(sb2, Matcher.quoteReplacement(mc.group(1) + fixed));
        }
        mc.appendTail(sb2);
        s = sb2.toString();

        // 逗号当小数点：「99,92」→「99.92」。
        // 负向前后夹紧，确保不是千分位：1,280.00 里逗号后是 3 位，不会被命中
        s = s.replaceAll("(?<=\\d)[,，](?=\\d{1,2}(?!\\d))", ".");

        // 小数点两侧被空格拆开：「99 . 92」→「99.92」
        s = s.replaceAll("(\\d)\\s*\\.\\s*(\\d)", "$1.$2");
        // 「99 92 元」这种整体被拆成两段、且后段正好 2 位 → 拼成 99.92
        s = s.replaceAll("(\\d{1,6})\\s+(\\d{2})(\\s*元)", "$1.$2$3");

        return s;
    }


    public static Long toFen(String s) {
        if (s == null || s.isEmpty()) return null;
        s = s.replace(",", "").replace("¥", "").trim();
        try {
            if (s.contains(".")) {
                String[] p = s.split("\\.");
                String ip = p[0].isEmpty() ? "0" : p[0];
                String fp = (p.length > 1 ? p[1] : "") + "00";
                fp = fp.substring(0, 2);
                return Long.parseLong(ip) * 100 + Long.parseLong(fp);
            }
            return Long.parseLong(s) * 100;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== ④.5 子句切割（通知栏文本核心） ====================

    /**
     * 金额在原句里的可能写法：99.92 / 99.9 / 100。
     * 注意 25.00 要去零成 "25"，但不能把 10.00 削成 "1"。
     */
    static String[] amountKeys(long fen) {
        String a = String.format(java.util.Locale.US, "%.2f", fen / 100.0);
        int dot = a.indexOf('.');
        String intPart = a.substring(0, dot);
        String frac = a.substring(dot + 1).replaceAll("0+$", "");
        String b = frac.isEmpty() ? intPart : intPart + "." + frac;
        String c = intPart;
        return new String[]{a, b, c};
    }

    /**
     * 定位「所选金额所在的子句」。
     * 通知文本通常是：标题 + 时间 + 正文（确认金额99.92元，手续费0.10元）。
     * 商户 / 条目 / 渠道必须只看这一句，否则会把别处的词（手续费）安到本笔头上。
     */
    static String focusClause(String raw, long fen) {
        String[] keys = amountKeys(fen);
        String[] parts = raw.split("(?<=[，。；,;])");
        // 按金额写法精度分层：先找「99.92」这种完整写法，再退到「99.9」「99」，
        // 避免短数字（99）先撞上无关子句
        for (String k : keys) {
            if (k.length() < 2) continue;
            for (String p : parts) {
                String t = p.trim();
                if (!t.isEmpty() && t.contains(k)) {
                    // 子句退化（只有数字、没有实体词）时改用金额周边的窗口。
                    // 真机实测：「商品小计 47.20 配送费 4.00 … 实付 39.20 华莱士」
                    // 按空格切出来的「39.20」不含任何商户，商户就退化成平台名了。
                    if (isDegenerate(t)) {
                        String w = windowAround(raw, k);
                        if (w != null) return w;
                    }
                    return t;
                }
            }
        }
        // 无标点时退回按空格切
        String[] bySpace = raw.split("\\s+");
        for (String k : keys) {
            if (k.length() < 2) continue;
            for (String p : bySpace) {
                if (p.contains(k)) {
                    if (isDegenerate(p)) {
                        String w = windowAround(raw, k);
                        if (w != null) return w;
                    }
                    return p;
                }
            }
        }
        return raw;
    }

    /** 子句是否退化：长度太短，或去掉数字/符号后没有实体文字 */
    private static boolean isDegenerate(String t) {
        if (t == null) return true;
        String s = t.replaceAll("[\\d\\s.,¥￥:：·•*\\-—+%]", "");
        return s.length() < 2;
    }

    /** 取金额前后各 40 字的窗口，并在标点处截断，避免跨句串味 */
    private static String windowAround(String raw, String key) {
        int pos = raw.indexOf(key);
        if (pos < 0) return null;
        int st = Math.max(0, pos - 40);
        int en = Math.min(raw.length(), pos + key.length() + 40);
        String w = raw.substring(st, en);
        // 在窗口两端最近的标点处收口
        int lastP = -1;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c == '，' || c == '。' || c == '；' || c == ',' || c == ';') lastP = i;
        }
        if (lastP >= 0) {
            int posInW = pos - st;
            if (lastP < posInW) w = w.substring(lastP + 1);
        }
        return w.trim();
    }

    // ==================== ④.6 付款渠道 ====================

    private static final String[] CHANNEL_WORDS = {
            "余额宝", "零钱通", "花呗", "月付", "白条", "零钱", "储蓄卡", "借记卡",
            "信用卡", "银行卡", "微信支付", "支付宝", "云闪付", "数字人民币"
    };

    /** 从文本判付款渠道：银行+尾号 > 尾号 > 渠道词 */
    static String extractChannel(String raw) {
        Matcher m = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z]{2,10}银行)[^\\d]{0,6}(\\d{4})").matcher(raw);
        if (m.find()) return cleanBank(m.group(1)) + "(尾号" + m.group(2) + ")";
        m = Pattern.compile("(?:尾号|卡号尾数)[^\\d]{0,4}(\\d{4})").matcher(raw);
        if (m.find()) return "银行卡(尾号" + m.group(1) + ")";
        for (String w : CHANNEL_WORDS) if (raw.contains(w)) return w;
        return "";
    }

    /** 银行名常混着「你已向/从/给」等动词前缀，去掉只留银行名 */
    private static String cleanBank(String s) {
        return s.replaceAll("^(?:你|我|他|她|已|向|从|给|到|在|至|用|通过|的|了)+", "")
                .replaceAll("[的了]+$", "");
    }

    // ==================== ④.7 交易场景 ====================

    private static final String[] SCENE_WORDS = {
            // 投资/理财场景在前：这类文本常同时含「买入/支付/到账」，
            // 必须让「申购/赎回」先命中，方向才不会被消费词带偏
            "基金申购", "基金认购", "基金定投", "基金赎回", "申购", "认购", "定投", "赎回",
            "买入", "卖出", "收益到账", "分红",
            "转账", "红包", "退款", "还款", "充值", "缴费", "工资", "消费", "付款", "支付", "扣款"
    };

    static String extractScene(String raw) {
        for (String w : SCENE_WORDS) if (raw.contains(w)) return w;
        return "";
    }

    /** 投资品名：买入/申购/赎回 后面的「××基金/理财/黄金」 */
    private static final Pattern P_INVEST_PRODUCT = Pattern.compile(
            "(?:买入|申购|认购|定投|赎回|卖出|转入|转出)[\\s:：]{0,3}" +
            "([\\u4e00-\\u9fa5A-Za-z0-9（）()\\-·]{3,30}?(?:基金|理财产品|理财|黄金|债券|证券|份额|组合))");

    static String investProduct(String raw) {
        Matcher m = P_INVEST_PRODUCT.matcher(raw);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 投资场景的方向。
     * 站在用户账本角度：申购/买入/定投/转入 = 钱从口袋出去（支出）；
     * 赎回/卖出/转出/分红/收益到账 = 钱回到口袋（收入）。
     */
    static int investType(String scene) {
        if (scene == null) return 0;
        if (scene.contains("申购") || scene.contains("认购") || scene.contains("定投")
                || scene.contains("买入") || scene.contains("转入")) return 1;
        if (scene.contains("赎回") || scene.contains("卖出") || scene.contains("转出")
                || scene.contains("分红") || scene.contains("收益到账")) return 2;
        return 0;
    }

    // ==================== ⑤ 方向判定 ====================

    private static final String[] INCOME_WORDS = {
            "到账", "收款到账", "收款成功", "已收款", "入账", "转入", "收入", "退款", "退回", "退还", "返还", "返现",
            "提现", "存入零钱", "已存入", "对方已收", "领取红包", "红包到账", "群收款",
            "转账给你", "收到", "已收红包", "收到红包", "转账已到账",
            "已被领取", "领取了你的红包", "领取"
    };
    /**
     * 「收款」这个词有歧义：
     *   「收款 250 元」        → 我收到钱（收入）
     *   「¥36 收款方：华莱士」 → 「收款方」只是商户的标签（支出）
     * 所以不能简单塞进 INCOME_WORDS，用正则排除「收款方/收款人/收款账户」。
     */
    private static final Pattern P_INCOME_RECEIPT = Pattern.compile(
            "收款(?!方|人|账户|账号|卡号|码|单位|名称)");
    private static final String[] EXPENSE_WORDS = {
            "支付", "付款", "消费", "扣款", "支出", "转出", "购买", "交易成功", "已付款",
            "花费", "还款", "转账给", "已转账", "发了红包", "发出红包", "发出", "已发出",
            "发起了群收款", "付款成功", "支付成功", "扣款成功", "已扣款", "支付了",
            "转账成功", "已向", "给对方转账", "充值", "缴费", "下单"
    };

    private static int detectType(String raw) {
        // 显式规则优先：「转账给你」是收款，不能因为包含「转账给」被判成支出
        if (raw.contains("转账给你")) return 2;
        // 「来自 X」「收到 X」是明确的**钱进来**信号。
        // 真机实测「微信支付 转账 收款 ¥12,800.00 来自 王五」：
        // 「支付」出现在前，双向词取靠后的规则输给了它，被判成支出。
        if (raw.contains("来自") || raw.contains("收到") || raw.contains("收款到账")) return 2;

        // 收益/利息/分红：钱**进**用户口袋，是收入。
        // 真机实测「余额宝昨日收益0.52元，累计收益128.30元」被判成支出。
        // 注意「收益」常和「投资」同现，这里必须在 INCOME/EXPENSE 词表之前判。
        if (INCOME_STRICT.matcher(raw).find()) return 2;
        // 欠费/应缴/待还：还没发生的钱，不该记成已支出。交由调用方按低置信度处理，
        // 这里返回支出但置信度会被扣分，最终进待确认队列。
        if (EXPENSE_FUTURE.matcher(raw).find()) return 1;

        boolean income = containsAny(raw, INCOME_WORDS) || P_INCOME_RECEIPT.matcher(raw).find();
        boolean expense = containsAny(raw, EXPENSE_WORDS);
        if (income && !expense) return 2;
        if (expense && !income) return 1;
        if (income) {
            // 双向词同时出现：取更靠近文本末尾（通常是结论位置）的那个
            int pi = lastIndexOfAny(raw, INCOME_WORDS);
            int pe = lastIndexOfAny(raw, EXPENSE_WORDS);
            return pi > pe ? 2 : 1;
        }
        return 1;   // 默认支出（低置信度，靠 scoreConfidence 兜底）
    }

    /**
     * 强收入信号：收益/利息/分红/结息 —— 钱进了用户口袋。
     * 必须独立于 INCOME_WORDS：这些词常与「投资/理财」同现，
     * 而「投资」在 EXPENSE_WORDS 里，会互相抵消。
     */
    private static final Pattern INCOME_STRICT = Pattern.compile(
            "(?:收益|利息|分红|结息|派息|返利)(?:已)?(?:到账|入账|发放|为)?\\s*[¥￥]?\\s*\\d"
                    + "|(?:昨日|今日|本月|累计|昨日收益|万份收益)[^\\n]{0,4}收益",
            Pattern.CASE_INSENSITIVE);

    /**
     * 「还没发生的钱」：欠费/应缴/待还/预估。
     *
     * 真机实测两类误记：
     *   1. 「您的话费已欠费12.30元」→ 记了一笔支出，但用户还没交钱
     *   2. 「预估26.00元，实际以行程结束为准」→ 打车预估被当成实付，
     *      等真扣款时再记一次，就成了两笔
     *
     * 这里只降置信度把它们推进待确认队列 —— 「宁可不记，不可错记」，
     * 但不能静默丢弃（用户要知道系统看到了什么）。
     */
    private static final Pattern EXPENSE_FUTURE = Pattern.compile(
            "欠费|应缴|待缴|待还|应付金额"
                    // 「本期账单/本月账单/账单金额」都是**待还**金额，不是已还金额
                    + "|(?:本期|本月|下月|当期)?账单(?:金额|总额|应还|待还)"
                    + "|最低还款|还款日"
                    + "|预估|预计|将扣|待扣|将于.{0,6}扣|以.{0,8}为准");

    // ==================== ⑥ 商户提取 ====================
    private static final Map<String, String> MERCHANT_HINTS = new HashMap<>();
    static {
        String[][] hints = {
                {"华莱士", "华莱士"}, {"肯德基", "肯德基"}, {"麦当劳", "麦当劳"}, {"星巴克", "星巴克"},
                {"瑞幸", "瑞幸咖啡"}, {"蜜雪冰城", "蜜雪冰城"}, {"喜茶", "喜茶"}, {"茶百道", "茶百道"},
                {"饿了么", "饿了么"}, {"美团", "美团"}, {"淘宝", "淘宝"}, {"天猫", "天猫"},
                {"京东", "京东"}, {"拼多多", "拼多多"}, {"闲鱼", "闲鱼"}, {"抖音", "抖音"},
                {"滴滴", "滴滴出行"}, {"盒马", "盒马"}, {"永辉", "永辉超市"}, {"沃尔玛", "沃尔玛"},
                {"12306", "铁路12306"}, {"铁路", "铁路12306"},
                {"医院", "医院"}, {"药店", "药店"}, {"便利店", "便利店"}, {"超市", "超市"},
                {"加油站", "加油站"}, {"快递", "快递"}
        };
        for (String[] h : hints) MERCHANT_HINTS.put(h[0], h[1]);
    }

    /**
     * 品牌词必须**精确**出现在商户位置才认，不能用 contains 兜底。
     *
     * 真机实测：「您已使用小米公交卡刷卡，扣款2.00元」
     * 因为含「小米」，被 MERCHANT_HINTS 配成「小米之家」—— 用户根本没去过小米之家。
     * 「苹果」同理（可能是水果，也可能是 Apple Store）。
     */
    private static final Map<String, String> MERCHANT_EXACT = new HashMap<>();
    static {
        MERCHANT_EXACT.put("小米之家", "小米之家");
        MERCHANT_EXACT.put("Apple Store", "Apple");
        MERCHANT_EXACT.put("苹果零售店", "Apple");
        MERCHANT_EXACT.put("华为体验店", "华为");
    }

    private static final String[] CHANNEL_NAMES = {
            "微信", "支付宝", "银行", "零钱", "系统", "钱包", "账户", "云闪付"
    };

    private static String detectMerchant(String raw, String platform) {
        // 0) 「来自 X」「X 转账给你」「收款人 X」这类明确的主体优先。
        //    真机实测「微信支付 转账 收款 ¥12,800.00 来自 王五」：
        //    旧逻辑把「微信」当商户（平台名），而正确答案是「王五」。
        Matcher mFrom = Pattern.compile(
                "来自\\s*([\\u4e00-\\u9fa5A-Za-z0-9·]{2,12})").matcher(raw);
        if (mFrom.find()) {
            String c = mFrom.group(1).trim();
            if (!c.isEmpty() && !containsAny(c, CHANNEL_NAMES)
                    && !containsAny(c, ENTITY_BAD_CONTENT)) return c;
        }
        // 1) 转账人名：「向 X 转账」「给 X 转账」「X 转账给你」
        Matcher mA = Pattern.compile(
                "(?:向|给)\\s*([\\u4e00-\\u9fa5A-Za-z0-9·]{2,12}?)\\s*(?:转账|支付|付款|收款|的红包)").matcher(raw);
        if (mA.find()) {
            String c = mA.group(1);
            if (!containsAny(c, CHANNEL_NAMES)) return c;
        }
        Matcher mA2 = Pattern.compile(
                "([\\u4e00-\\u9fa5A-Za-z0-9·]{2,12}?)\\s*转账给你").matcher(raw);
        if (mA2.find()) {
            String c = mA2.group(1);
            if (!containsAny(c, CHANNEL_NAMES)) return c;
        }
        // 2) 转账给 X
        Matcher mB = Pattern.compile(
                "转账给\\s*([\\u4e00-\\u9fa5A-Za-z0-9·]{2,12}?)\\s*(?:[¥]|请|已|,|\\.|$)").matcher(raw);
        if (mB.find()) {
            String c = mB.group(1);
            if (!containsAny(c, CHANNEL_NAMES)) return c;
        }
        // 3) 收款方 / 商户
        Matcher mC = Pattern.compile(
                "(?:收款方|商户|商家|交易对象)[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9·]{2,16})").matcher(raw);
        if (mC.find()) {
            String c = mC.group(1);
            if (!containsAny(c, CHANNEL_NAMES)) return c;
        }
        // 4) 金额之后紧跟的实体名。
        //    「… 实付 39.20 华莱士」—— 华莱士才是商户，「饿了么」只是平台。
        //    平台名压过真实商户名是错账的另一大来源，所以这一步要排在商户词表前。
        String afterAmt = entityAfterAmount(raw);
        if (!afterAmt.isEmpty() && !containsAny(afterAmt, CHANNEL_NAMES)) return afterAmt;

        // 5) 商户词表。品牌词先试精确匹配，再用 contains 兜底。
        //    精确匹配存在的理由：真机实测「您已使用小米公交卡刷卡，扣款2.00元」
        //    因含「小米」被配成「小米之家」，而用户只是刷了公交卡。
        for (Map.Entry<String, String> e : MERCHANT_EXACT.entrySet()) {
            if (raw.contains(e.getKey())) return e.getValue();
        }
        // 「XX卡/钱包/账户」语境下，品牌词不当作商户
        boolean cardContext = Pattern.compile(
                "(?:公交卡|交通卡|地铁卡|门禁卡|月卡|会员卡|储值卡|卡余额|钱包|账户)")
                .matcher(raw).find();
        if (!cardContext) {
            for (Map.Entry<String, String> e : MERCHANT_HINTS.entrySet()) {
                if (raw.contains(e.getKey())) return e.getValue();
            }
        }
        // 6) 平台兜底
        return platformName(platform);
    }

    /**
     * 取「金额之后」紧跟的实体名。
     *
     * 真实通知的常见排版是「标签 金额 商户名」：
     *   「实付 39.20 华莱士」  → 华莱士
     *   「支付成功 ¥128.50 星巴克咖啡」 → 星巴克咖啡
     *
     * 只在金额后面找，是因为前面的位置常被平台名/标签占据。
     * 但紧跟金额的也可能是动词（付款给）或渠道（余额宝/零钱），必须滤掉。
     */
    private static final String[] ENTITY_BAD_PREFIX = {
            "付款给", "收款方", "收款人", "付款方", "转账给", "已存入", "已到账",
            "到账", "存入", "支付给", "退款", "收取", "扣款", "请", "共", "合",
            "来自", "由", "于", "在", "向", "从", "给", "至",
    };
    private static final String[] ENTITY_BAD_CONTENT = {
            "余额宝", "零钱通", "零钱", "花呗", "月付", "白条", "储蓄卡", "借记卡",
            "信用卡", "银行卡", "支付方式", "优惠", "折扣", "红包", "积分",
            "本金", "利息", "手续费", "服务费", "运费", "配送费",
            // 各类费用/辅料：真机实测「包装费」「餐盒费」被当成商户名，账本里全是费用词
            "包装费", "餐盒费", "打包费", "小费", "税费", "保价费", "加价",
            "小计", "合计", "总计", "商品", "总价", "单价", "数量",
            // 动作/状态
            "已存入", "已到账", "实际到账", "到账", "成功", "完成", "确认",
            "支付成功", "付款成功", "还款成功", "收款成功",
            // 退款/支付的**动作描述**，不是商户名。
            // 真机实测：
            //   「退款18.60元已原路退回」→ 商户「已原路退回」
            //   「车费32.50元已自动支付」→ 商户「已自动支付」
            //   「退款将原路退回」       → 商户「将原路退回」
            "原路退回", "自动支付", "自动扣款", "自动续费", "请注意查收",
            "已退款", "待收货", "已发货", "已签收", "已确认收货",
    };

    /** 以这些词开头的候选，一律不是商户名（动词/状态前缀） */
    private static final String[] ENTITY_BAD_LEAD = {
            "已", "将", "会", "正在", "正在为您", "请", "共", "合", "为", "是",
    };

    private static String entityAfterAmount(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        Matcher m = Pattern.compile(
                "(?:[¥￥]\\s*\\d{1,7}(?:[.,]\\d{1,2})?|\\d{1,7}[.,]\\d{1,2}\\s*元?|\\d{1,7}\\s*元)"
                        + "\\s*([\\u4e00-\\u9fa5][\\u4e00-\\u9fa5A-Za-z0-9（）()·\\-]{1,18})").matcher(raw);
        String best = "";
        while (m.find()) {
            String c = m.group(1).trim();
            c = c.replaceAll("[的了和与及，。；,;！!？?]+$", "").trim();
            // 「付款给 麦当劳」→ 剥掉动词前缀，留下麦当劳
            for (String p : ENTITY_BAD_PREFIX) {
                if (c.startsWith(p)) { c = c.substring(p.length()).trim(); break; }
            }
            if (c.length() < 2) continue;
            if (isLabelWord(c)) continue;
            if (containsAny(c, ENTITY_BAD_CONTENT)) continue;
            if (containsAny(c, CHANNEL_NAMES)) continue;
            if (containsAny(c, ENTITY_BAD_LEAD)) continue;
            if (isPackageLike(c)) continue;
            // 括号必须成对：normalize 会把全角括号转半角，
            // 「星巴克咖啡(万象城店)」若被截成「星巴克咖啡(万象城店」就不自然了
            int open = c.indexOf('(');
            if (open < 0) open = c.indexOf('（');
            if (open >= 0) {
                int close = c.lastIndexOf(')');
                if (close < 0) close = c.lastIndexOf('）');
                if (close > open) c = c.substring(0, close + 1);
                else c = c.substring(0, open).trim();   // 括号没闭合，去掉半截
            }
            if (c.isEmpty()) continue;
            // 越靠后的越可能是真正的商户（正文常先写平台/订单，最后写商户）
            if (c.length() > best.length()) best = c;
        }
        return best;
    }

    /**
     * 候选是不是包名形态（com.xxx.yyy / a.b.c.d）。
     * 真机实测有包名漏进账本，成因是某个提取分支拿到了包名字符串。
     * 这类字符串**任何情况下**都不能当商户名。
     */
    static boolean isPackageLike(String s) {
        if (s == null || s.length() < 5) return false;
        if (s.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){2,}$")) return true;
        // 「com.xxx」+ 中文尾巴
        return s.matches("^com\\.[a-zA-Z0-9_.]+.*");
    }

    /**
     * 修正商户名里的半截括号。
     *
     * 真机实测：正文「星巴克咖啡(万象城店)」在多个提取分支里被不同的正则
     * 按不同长度截断，最终落到账本上是「星巴克咖啡(万象城店」——缺少右括号，
     * 界面上看很不像话。这里统一收口：
     *   有左无右 → 补上右括号（内容完整，只是被切掉了尾巴）
     *   有右无左 → 去掉右括号
     * 注意全角/半角混用也一并归一成半角。
     */
    static String fixBrackets(String s) {
        if (s == null || s.isEmpty()) return s;
        String v = s.replace('（', '(').replace('）', ')');
        while (v.contains("((")) v = v.replace("((", "(");
        while (v.contains("))")) v = v.replace("))", ")");
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (open >= 0 && close < open) {
            v = v.substring(0, open + 1) + v.substring(open + 1) + ")";
        } else if (open < 0 && close >= 0) {
            v = v.replace(")", "").trim();
        }
        return v.trim();
    }

    // ==================== ⑦ 条目提取（买了什么） ====================

    private static final String[] FEE_WORDS = {
            "运费", "配送费", "服务费", "餐盒费", "包装费", "手续费", "小费"
    };

    private static String extractItems(String raw) {
        String hit = extractItemsRaw(raw);
        return cleanItems(hit);
    }

    private static String extractItemsRaw(String raw) {
        // 1) 明确的商品描述
        Matcher m = Pattern.compile(
                "(?:您购买了|你购买了|购买[:：]?\\s*|商品[:：]?\\s*)([^,;.。!?\\s]{2,20})").matcher(raw);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) return v;
        }
        // 2) 订单号后的中文片段（如「订单SLAMBLL…运费」）
        m = Pattern.compile("订单\\s*[A-Za-z0-9]*\\s*([\\u4e00-\\u9fa5]{2,16})").matcher(raw);
        if (m.find()) {
            String v = m.group(1);
            if (!v.startsWith("已") && !v.startsWith("支付") && !v.startsWith("成功")) return v;
        }
        // 3) 费用类型
        for (String f : FEE_WORDS) {
            if (raw.contains(f)) return f;
        }
        return "";
    }

    // ==================== ⑧ 置信度 ====================

    private static boolean isPaymentSource(String platform) {
        return "com.tencent.mm".equals(platform) || "com.eg.android.AlipayGphone".equals(platform)
                || "com.sankuai.meituan".equals(platform) || "com.ss.android.ugc.aweme".equals(platform)
                || "com.jingdong.app.mall".equals(platform) || "com.taobao.taobao".equals(platform)
                || "com.xunmeng.pinduoduo".equals(platform) || "com.android.mms".equals(platform)
                || "com.aron.autobill".equals(platform);
    }

    private static int scoreConfidence(String raw, String platform, String merchant, int type) {
        int s = 0;
        boolean hasAction = containsAny(raw, EXPENSE_WORDS) || containsAny(raw, INCOME_WORDS);
        if (hasAction) s += 40;
        if (raw.contains("¥") || raw.contains("元") || raw.contains("块")) s += 20;
        boolean merchantOk = merchant != null && !"未识别".equals(merchant)
                && !containsAny(merchant, CHANNEL_NAMES);
        if (merchantOk) s += 20;
        else s -= 10;
        if (isPaymentSource(platform)) s += 15;
        if (raw.length() >= 10 && raw.length() <= 200) s += 5;
        if ("com.android.systemui".equals(platform)) s -= 15;   // 通知栏兜底文本，可能混入系统 UI
        // 双向方向冲突
        if (containsAny(raw, EXPENSE_WORDS) && containsAny(raw, INCOME_WORDS)) s -= 20;

        // 商户名不可信（包名/动作词/费用词）+ 未知包名：大幅降分，推进待确认队列。
        // 「宁可不记，不可错记」—— 但不能静默丢，用户在队列里能捞回来。
        if (merchant == null || merchant.trim().isEmpty() || "未识别".equals(merchant)) {
            s -= 15;
        } else if (isPackageLike(merchant) || containsAny(merchant, ENTITY_BAD_CONTENT)
                || containsAny(merchant, ENTITY_BAD_LEAD)) {
            s -= 35;
        }

        // 「还没发生的钱」：欠费/账单/预估。真机实测的重复记账就出在这。
        // 扣分要**足够狠**：这类文本通常动作词齐全（「已欠费」「还款日」），
        // 光是基础分就能堆到 50+，扣 40 分仍会卡在自动入账线上。
        if (EXPENSE_FUTURE.matcher(raw).find()) s -= 60;

        return Math.max(0, Math.min(100, s));
    }

    // ==================== OCR 专用入口 ====================

    /**
     * OCR 文本解析：按「行」归属金额与商户。
     *
     * 为什么必须按行：OCR 出的是一行行独立文本，因果关系只存在于同一行内。
     * 示例（外卖订单详情页）：
     *     华莱士
     *     香辣鸡腿堡 x1        12.90
     *     配送费               3.00
     *     商品小计            15.90
     *     实付                99.92
     * 如果压成一段去猜，很容易把 12.90 或 15.90 当成本笔金额，商户也会抓成「配送费」。
     * 按行处理时：
     *   - 金额用「实付/合计/总计/支付」等强前缀词所在行的数字（优先级最高）
     *   - 商户取金额行往上最近的一行「纯文字行」（通常就是店名），
     *     或同行里「文字 + 金额」结构的文字部分
     *
     * @param lines OCR 行文本（已按视觉顺序排列）
     */
    public ParseResult parseOcr(java.util.List<String> lines, String platform) {
        ParseResult r = new ParseResult();
        if (lines == null || lines.isEmpty()) return r;

        // 全文本（用于场景/渠道/方向判断，这些信息常出现在标题行）
        StringBuilder all = new StringBuilder();
        for (String l : lines) if (l != null && !l.trim().isEmpty()) all.append(l.trim()).append('\n');
        String raw = normalize(all.toString());
        if (raw.isEmpty()) return r;
        if (blocked(raw)) return r;
        if (containsAny(raw, DROP_CONTEXTS)) return r;

        // 1) 归一 OCR 异体字（支付金颉→支付金额、商戶全称→商户全称）
        //    → 修小数点（每行单独修，避免跨行拼接）
        java.util.List<String> fixed = new java.util.ArrayList<>(lines.size());
        for (String l : lines) fixed.add(l == null ? "" : fixOcrAmount(OcrTemplates.norm(l.trim())));

        // 1.5) 版式模板：识别出是哪个 App 的账单页，就用它的固定版式定位。
        //      模板只增强不替代 —— 没取到金额时照样落到下面的通用规则。
        OcrTemplates.Tpl tpl = OcrTemplates.detect(fixed);
        if (tpl != null) {
            ParseResult tr = parseWithTemplate(fixed, tpl, raw, platform);
            if (tr != null && tr.ok) return tr;
        }

        // 2) 找金额：强前缀行 > 「文字 + 金额」行 > 任意带元/¥ 的行
        int amountLine = -1;
        Long fen = null;
        int bestScore = -1;

        for (int i = 0; i < fixed.size(); i++) {
            String line = fixed.get(i);
            if (line.isEmpty()) continue;
            // OCR 专用提取：容忍「36.50」这种光秃秃的数字
            Long f = extractOcrAmount(line);
            if (f == null || f <= 0) continue;

            int sc = amountPriority(line);
            // 裸小数行（没有 ¥/元/标签词）降级，避免「数量 2.00」这种压过真金额
            if (sc == 50 && !hasMoney(line)) sc = 15;
            if (sc > bestScore) {
                bestScore = sc;
                fen = f;
                amountLine = i;
            }
        }
        if (fen == null) return r;

        // 3a) 银行短信优先：整句格式特殊（【招商银行】您账户…入账…），
        //     里面的「摘要/余额」等词会干扰标签提取，先按方括号前缀取来源。
        String merchant = "";
        boolean isSmsLike = raw.contains("【") || raw.contains("】");
        if (isSmsLike) {
            String bank = extractBankName(raw);
            if (!bank.isEmpty()) merchant = bank;
        }
        // 3b) 「标签+值」——真机三类错账的主修点（见 valueAfterLabel 注释）。
        //     「商户名称 京东商城」「收款人 张小明」这类结构，
        //     标签后的值一定比任何猜测都准。
        if (merchant.isEmpty()) {
            String labeled = valueAfterLabel(fixed, MERCHANT_LABELS);
            if (!labeled.isEmpty()) merchant = labeled;
        }
        // 3c) 金额行内的文字部分
        if (merchant.isEmpty() && amountLine >= 0) {
            merchant = merchantFromLine(fixed.get(amountLine));
            if (isLabelWord(merchant)) merchant = "";
        }
        // 3d) 金额行往上最近的非数字行
        if (merchant.isEmpty() && amountLine >= 0) {
            String up = merchantAbove(fixed, amountLine);
            if (!isLabelWord(up)) merchant = up;
        }
        // 3e) 最后才退到平台名
        if (merchant.isEmpty()) {
            merchant = detectMerchant(raw, platform);
        }

        // 4) 场景 / 渠道 / 方向
        String focus = amountLine >= 0 ? fixed.get(amountLine) : raw;
        String scene = extractScene(raw);
        int invType = investType(scene);
        String channel = extractChannel(
                focus.contains("余额宝") || focus.contains("花呗") || focus.contains("零钱")
                        || focus.contains("尾号") ? focus : raw);

        int type;
        if (invType != 0) {
            type = invType;
        } else if (hasDirWord(focus)) {
            type = detectType(focus);
        } else {
            type = detectType(raw);
        }

        // 5) 条目：金额行的商品描述 > 场景
        String items = extractItems(focus);
        if (items.isEmpty()) items = scene;

        // 6) 置信度：OCR 天生比通知栏不可靠，整体打折
        int conf = scoreConfidence(raw, platform, merchant, type);
        conf = conf * 85 / 100;                    // OCR 折减 15%
        if (!scene.isEmpty()) conf += 5;
        if (!channel.isEmpty()) conf += 5;
        if (!merchant.isEmpty() && !merchant.equals(platformName(platform))) conf += 10;

        r.ok = true;
        r.amountFen = fen;
        r.type = type;
        r.merchant = merchant;
        r.scene = scene;
        r.channel = channel;
        r.note = channel.isEmpty() ? items
                : (items.isEmpty() ? channel : items + " · " + channel);
        r.confidence = Math.max(0, Math.min(100, conf));
        r.rawAmount = String.valueOf(fen / 100.0);
        return r;
    }

    /**
     * 按版式模板解析。返回 null 表示模板没能取到金额，调用方应退回通用规则。
     *
     * 定位顺序：
     *   金额：强标签行 → 弱标签行 → 唯一带金额的行（整页只有一处钱，通常就是本笔）
     *   商户：「标签 + 值」同行（商户全称：华莱士）→ 金额上方/下方的纯文字行
     */
    private static ParseResult parseWithTemplate(java.util.List<String> lines,
                                                 OcrTemplates.Tpl tpl,
                                                 String raw, String platform) {
        if (lines == null || lines.isEmpty()) return null;

        // ---------- 金额 ----------
        int amountLine = -1;
        Long fen = null;
        int best = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            // 用 OCR 专用提取：容忍「36.50」这种光秃秃的数字
            Long f = extractOcrAmount(line);
            int sc = 0;

            if (f != null && f > 0) {
                for (String s : tpl.strongAmount) if (line.contains(s)) { sc = 100; break; }
                if (sc == 0) for (String s : tpl.weakAmount) if (line.contains(s)) { sc = 30; break; }
                if (sc == 0 && hasMoney(line)) sc = 10;
                if (sc == 0) sc = 5;          // 裸小数行：可信度低，但聊胜于无
            } else {
                // 标签独占一行、金额在下一行（支付宝「支付金额」换行写金额就是这样）
                boolean isLabelLine = false;
                for (String s : tpl.strongAmount) if (line.contains(s)) { isLabelLine = true; break; }
                if (isLabelLine && i + 1 < lines.size()) {
                    Long f2 = extractOcrAmount(lines.get(i + 1));
                    if (f2 != null && f2 > 0) {
                        sc = 95;          // 跨行取值略低于同行，仍然很高
                        if (sc > best) { best = sc; fen = f2; amountLine = i + 1; }
                        continue;
                    }
                }
            }

            if (sc > best) {
                best = sc;
                fen = f;
                amountLine = i;
            }
        }
        if (fen == null) return null;

        // ---------- 商户 ----------
        String merchant = merchantByTemplate(lines, tpl, amountLine);

        // ---------- 条目：商品说明 / 商品 / 转账说明 ----------
        String items = itemsByTemplate(lines, tpl);

        // ---------- 场景 / 渠道 / 方向 ----------
        String scene = extractScene(raw);
        int invType = investType(scene);
        String channel = tpl.channelName();
        if (channel.isEmpty()) channel = extractChannel(raw);

        int type;
        if (invType != 0) {
            type = invType;
        } else {
            type = detectType(raw);
        }
        // 微信/支付宝凭证里「退款」方向容易看错，用整段再确认一次
        if (containsAny(raw, REFUND_WORDS) && !raw.contains("退款失败")) type = 2;

        int conf = scoreConfidence(raw, platform, merchant, type);
        conf = conf * 85 / 100;              // OCR 折减
        conf += 8;                           // 版式命中，可信度更高
        if (!scene.isEmpty()) conf += 3;
        if (!channel.isEmpty()) conf += 3;
        if (!merchant.isEmpty()) conf += 6;
        // 版式指纹 + 金额锚点 + 商户锚点全部命中的账单，是模板里
        // 逐字段核对过的版式，置信度给 55 兜底 —— 否则 raw 里缺少
        // 「支付/消费」类关键词时（如纯订单详情页）总分会被 50 分
        // 门槛拦下，明明三处都定位对了却不给用户看结果。
        if (!merchant.isEmpty() && conf < 55) conf = 55;

        ParseResult r = new ParseResult();
        r.ok = true;
        r.amountFen = fen;
        r.type = type;
        r.merchant = merchant;
        r.scene = scene;
        r.channel = channel;
        r.note = channel.isEmpty() ? items
                : (items.isEmpty() ? channel : items + " · " + channel);
        r.confidence = Math.max(0, Math.min(100, conf));
        r.rawAmount = String.valueOf(fen / 100.0);
        return r;
    }

    /**
     * 模板化的商户定位。
     * 「商户全称 华莱士」这种标签同行最准；否则按模板方向找纯文字行。
     */
    private static String merchantByTemplate(java.util.List<String> lines,
                                             OcrTemplates.Tpl tpl, int amountLine) {
        // 1) 「标签 + 值」同行，或「标签行」的下一行（微信凭证常拆成两行）
        //    标签按长度降序试：「商户全称」必须先于「商户」，
        //    否则 indexOf("商户") 会先把「商户全称 华莱士」剥成「全称 华莱士」。
        String[] labels = tpl.merchantInline.clone();
        java.util.Arrays.sort(labels, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) { return b.length() - a.length(); }
        });
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String label : labels) {
                if (!line.contains(label)) continue;
                // 同行有值：「商户全称 华莱士」「收款方：华莱士」
                // 值也过一遍商户合理性校验 —— 否则「商品小计 47.20」
                // 会把「小计 47.20」当成商户名
                String v = stripLabel(line, label);
                if (!v.isEmpty() && plausibleMerchant(tpl, v)) return v;
                // 标签独占一行，取下一行；下一行同样要过合理性校验
                // （真机实测「收款方」的下一行是「支付方式」这种噪声行）
                if (i + 1 < lines.size()) {
                    String nx = lines.get(i + 1).trim();
                    if (plausibleMerchant(tpl, nx)) {
                        return nx.length() > 20 ? nx.substring(0, 20) : nx;
                    }
                }
            }
        }
        // 2) 按模板方向找纯文字行
        int step = tpl.merchantBelow ? 1 : -1;
        int start = amountLine + step;
        for (int n = 0; n < 5; n++) {
            int i = start + step * n;
            if (i < 0 || i >= lines.size()) break;
            String t = lines.get(i).trim();
            if (plausibleMerchant(tpl, t)) return t.length() > 20 ? t.substring(0, 20) : t;
        }
        // 3) 兜底：店名常常写在页面最上方（「订单详情」的下一行），
        //    金额行往上回溯不够时，从页面顶部再扫一遍
        for (int i = 0; i < lines.size() && i < 8; i++) {
            String t = lines.get(i).trim();
            if (plausibleMerchant(tpl, t)) return t.length() > 20 ? t.substring(0, 20) : t;
        }
        return "";
    }

    /** 这一行能不能当商户名（模板版） */
    private static boolean plausibleMerchant(OcrTemplates.Tpl tpl, String t) {
        if (t.length() < 2 || t.length() > 30) return false;
        if (t.matches("^[\\d\\s\\-—:：./%]+$")) return false;
        if (t.matches("^\\d{4}[-/年].*")) return false;
        if (t.matches("^\\d{1,2}[:：]\\d{2}.*")) return false;
        if (hasMoney(t)) return false;
        if (isLabelWord(t)) return false;
        for (String w : tpl.noise) if (t.contains(w)) return false;
        return t.matches(".*[\\u4e00-\\u9fa5A-Za-z]{2,}.*");
    }

    /** 剥掉标签前缀，返回后面的值 */
    private static String stripLabel(String line, String label) {
        int p = line.indexOf(label);
        if (p < 0) return "";
        String v = line.substring(p + label.length())
                .replaceAll("^[\\s:：·•\\-—]+", "")
                .replaceAll("[\\s:：·•\\-—]+$", "")
                .trim();
        if (v.length() < 2) return "";
        // 「商户全称 华莱士 ¥36.50」→ 去掉金额尾巴
        v = v.replaceAll("[¥￥]\\s*\\d+[.,]?\\d*", "").trim();
        v = v.replaceAll("\\d+[.,]\\d{1,2}\\s*元", "").trim();
        return v.length() > 20 ? v.substring(0, 20).trim() : v;
    }

    /** 模板化的条目定位：商品说明 / 商品 / 转账说明 */
    private static String itemsByTemplate(java.util.List<String> lines, OcrTemplates.Tpl tpl) {
        String[] itemLabels = {"商品说明", "商品名称", "商品", "转账说明", "备注", "宝贝"};
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String label : itemLabels) {
                if (!line.contains(label)) continue;
                String v = stripLabel(line, label);
                if (!v.isEmpty() && !isLabelWord(v)) return v;
                if (i + 1 < lines.size()) {
                    String nx = lines.get(i + 1).trim();
                    if (nx.length() >= 2 && !hasMoney(nx) && !isLabelWord(nx)) {
                        return nx.length() > 20 ? nx.substring(0, 20) : nx;
                    }
                }
            }
        }
        return "";
    }

    /**
     * 是不是「金额标签词」而不是商户名。
     * 「实付 / 合计 / 总计 / 应付 / 小计」这些是价目表的列名，
     * 从金额行剥出来之后会剩下它们，绝不能当成商户。
     */
    private static boolean isLabelWord(String s) {
        if (s == null || s.isEmpty()) return true;
        // 先剥掉数字和符号：「实付 99.92」→「实付」再判断
        // （merchantFromLine 剥金额时正则可能没吃掉，这里兜一层）
        String t = s.replaceAll("[\\d\\s.,¥￥:：·•*\\-—%]", "").trim();
        if (t.isEmpty()) return true;
        String[] labels = {"实付", "应付", "合计", "总计", "小计", "支付", "付款", "消费",
                "扣款", "到账", "收款", "退款", "金额", "订单", "交易", "原价", "单价",
                "优惠", "折扣", "运费", "配送费", "服务费", "手续费", "数量", "共", "需支付",
                "商品", "实收", "找零", "抹零", "打包费", "餐盒费",
                // 真机实测：这些是「标签」不是「值」——
                // 「商户名称 京东商城」剥出「名称」→ 被当成商户名（实测错账）
                "名称", "商户", "商家", "收款方", "收款人", "付款方", "对方",
                "摘要", "备注", "说明", "用途", "类型", "分类", "时间", "日期",
                "单号", "订单号", "交易号", "流水号", "卡号", "尾号", "账户", "银行",
                "短信", "通知", "消息", "提醒", "服务"};
        for (String l : labels) if (t.equals(l)) return true;
        return false;
    }

    /**
     * 从「标签 + 值」结构里取值。真机三类错账的共同病根。
     *
     * 实测三个案例：
     *   退款  「商户名称 京东商城」     → 剥出「名称」，被当商户    （应得 京东商城）
     *   工资  「【招商银行】您账户…」  → 整句太长，判为不合理      （应得 招商银行 / 工资）
     *   红包  「收款人 张小明」        → isLabelWord 未含「收款人」，取值失败
     *
     * 策略：按标签长度降序试（「商户全称」先于「商户」），
     * 命中后只取标签后面的部分，剥掉冒号/空格等分隔符。
     */
    private static String valueAfterLabel(String line, String[] labels) {
        if (line == null || line.isEmpty()) return "";
        for (String label : labels) {
            int idx = line.indexOf(label);
            if (idx < 0) continue;
            String v = line.substring(idx + label.length());
            // 剥掉分隔符
            v = v.replaceAll("^[\\s:：=＝\\-—、,，.。]+", "");
            // 关键：在下一个「标签词」处截断。
            // 真机实测：「商户名称 京东商城 退款时间 2026-11-11」
            // 不截断就会把「退款时间…」一起吞进商户名。
            v = cutAtNextLabel(v, labels);
            v = v.replaceAll("[¥￥]\\s*\\d+[.,]?\\d*", " ");
            v = v.replaceAll("\\d+[.,]\\d{1,2}\\s*元", " ");
            // 「退款时间 2026-11-11」这类被截断后可能残留的尾段时间
            v = v.replaceAll("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}\\s*日?", "");
            v = v.replaceAll("\\d{1,2}月\\d{1,2}日", "");
            v = v.replaceAll("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b", "");
            v = v.replaceAll("\\s+", " ").trim();
            // 去掉首尾引号括号
            v = v.replaceAll("^[【\\[（(「『\"']+|[】\\]）)」』\"']+$", "").trim();
            // 值里还夹着金额 → 把金额连同它前面的标签一起剥掉，留下纯名字。
            // 不能直接丢弃整条：真机实测「商户全称 星巴克 支付金额 128.50 余额宝」
            // 剥出「星巴克 支付金额 128.50 余额宝」，旧逻辑因 hasMoney 直接跳过，
            // 商户就退化成「全称」。
            if (hasMoney(v)) {
                v = v.replaceAll("(?:支付金额|交易金额|付款金额|金额|实付|应付|合计|总计)"
                        + "\\s*[¥￥]?\\s*\\d{1,7}(?:[.,]\\d{1,2})?\\s*(?:元|块)?", " ");
                v = v.replaceAll("[¥￥]\\s*\\d{1,7}(?:[.,]\\d{1,2})?", " ");
                v = v.replaceAll("\\d{1,7}[.,]\\d{1,2}\\s*(?:元|块)?", " ");
                v = v.replaceAll("\\s+", " ").trim();
            }
            if (v.isEmpty() || isLabelWord(v)) continue;
            if (v.matches("^[\\d\\s\\-—:：./%]+$")) continue;
            if (hasMoney(v)) continue;
            // 费用/动作词不是商户名（「实际到账」「手续费」「包装费」）
            if (containsAny(v, ENTITY_BAD_CONTENT)) continue;
            if (containsAny(v, ENTITY_BAD_LEAD)) continue;
            if (isPackageLike(v)) continue;
            if (v.matches("^(?:来自|由|于|在|向|从|给|至).*")) continue;
            return v.length() > 20 ? v.substring(0, 20) : v;
        }
        return "";
    }

    /**
     * 在下一个标签词处截断。
     * 例：「京东商城 退款时间 2026-11-11」→「京东商城」
     */
    private static String cutAtNextLabel(String v, String[] labels) {
        int cut = v.length();
        for (String l : labels) {
            if (l.isEmpty()) continue;
            int i = v.indexOf(l);
            if (i > 0 && i < cut) cut = i;
        }
        // 也按常见字段名截断（这些不一定在 MERCHANT_LABELS 里）
        String[] others = {"退款时间", "支付方式", "交易时间", "到账时间", "订单时间",
                "退款金额", "交易金额", "订单编号", "交易单号", "余额", "摘要"};
        for (String l : others) {
            int i = v.indexOf(l);
            if (i > 0 && i < cut) cut = i;
        }
        return v.substring(0, cut).trim();
    }

    /** 跨行版：先逐行试，全部落空再对整段试（短信是一行，必须走整段） */
    private static String valueAfterLabel(java.util.List<String> lines, String[] labels) {
        if (lines != null) {
            for (String line : lines) {
                String v = valueAfterLabel(line, labels);
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    /**
     * 条目清洗：把「支付方式 零钱」「退款时间 2026…」这类**字段名**清掉。
     * 真机实测：红包案例的条目曾被解析成「支付方式 零钱」——
     * 那描述的是付款渠道，不是买了什么。
     */
    public static String cleanItems(String items) {
        if (items == null || items.trim().isEmpty()) return "";
        String t = items.trim();
        String[] junk = {"退款时间", "支付方式", "交易时间", "到账时间", "订单时间",
                "退款金额", "交易金额", "订单编号", "交易单号", "余额", "摘要",
                "收款方", "付款方", "备注", "说明", "用途"};
        for (String j : junk) {
            int i = t.indexOf(j);
            if (i == 0) {
                // 整条以字段名开头：把字段名连同值一起丢掉，除非后面还有别的词
                String rest = t.substring(j.length()).replaceAll("^[\\s:：=＝\\-—、,，.。]+", "");
                // 「支付方式 零钱」整段都是字段 → 清空
                if (rest.isEmpty() || rest.length() <= 6) return "";
                t = rest;
            }
        }
        t = t.replaceAll("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}\\s*日?", "");
        t = t.replaceAll("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) : t;
    }

    /**
     * 银行/机构名。用于整句短信（招商银行/工商银行…）。
     * 提取失败返回空串，交给上层继续兜底。
     */
    private static String extractBankName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        java.util.regex.Matcher m = P_BANK.matcher(raw);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) return v;
        }
        m = P_BANK_PLAIN.matcher(raw);
        if (m.find()) {
            String v = m.group(1).trim();
            // 「你账户」这类误匹配排除
            if (!v.contains("账户") && !v.contains("你的")) return v;
        }
        return "";
    }

    /**
     * 金额行的可信度：强前缀词（实付/合计/支付）最高，
     * 「商品小计/运费/优惠」等明细行最低——不然会把小计当成本笔金额。
     */
    private static int amountPriority(String line) {
        String[] strong = {"实付", "实际支付", "应付", "合计", "总计", "支付金额", "付款金额",
                "订单金额", "交易金额", "本次支付", "扣除", "到账", "收款"};
        String[] weak = {"小计", "商品金额", "配送费", "运费", "优惠", "折扣", "红包", "积分",
                "手续费", "服务费", "原价", "单价", "×", "x", "数量"};
        int s = 50;
        for (String w : strong) if (line.contains(w)) { s = 100; break; }
        if (s < 100) {
            for (String w : weak) if (line.contains(w)) { s = 20; break; }
        }
        if (s == 50) {
            // 「文字 ¥12.90」这种同行结构，中等可信
            if (line.matches(".*[¥￥]\\s*\\d.*") || line.matches(".*\\d+[.,]\\d{1,2}\\s*元.*")) s = 60;
        }
        return s;
    }

    /**
     * 从金额行里剥出商户/商品名。
     * 「华莱士 ¥36.50」→ 华莱士；「香辣鸡腿堡 x1  12.90」→ 香辣鸡腿堡
     */
    private static String merchantFromLine(String line) {
        if (line == null || line.isEmpty()) return "";
        // 去掉金额部分及尾部符号
        String t = line
                .replaceAll("[¥￥]\\s*\\d+[.,]?\\d*", " ")
                .replaceAll("\\d+[.,]\\d{1,2}\\s*元", " ")
                .replaceAll("\\d+\\s*元", " ")
                .replaceAll("^[\\s\\-—:：·•*]+|[\\s\\-—:：·•*]+$", "")
                .trim();
        // 去掉数量、单位
        t = t.replaceAll("\\s*[x×]\\s*\\d+", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() < 2) return "";
        // 剥完只剩明细/费用标签的，不是商户（交给 merchantAbove 往上看）
        if (isLabelWord(t)) return "";
        // 纯数字/纯符号不算商户
        if (t.matches("^[\\d\\s\\-—:：.]+$")) return "";
        // 太长的多半是整段描述，截前 16 字
        if (t.length() > 16) t = t.substring(0, 16).trim();
        return t;
    }

    /**
     * 金额行往上找最近的一行「像商户名」的文字。
     * 账单/小票的结构几乎都是「店名在最上面，金额在下面」。
     */
    private static String merchantAbove(java.util.List<String> lines, int amountLine) {
        // 明细行噪音：整行是「费用/小计/优惠/数量」这类价目表项目，不是店名。
        // 这类行通常自带金额，必须整行跳过（否则会把「配送费」当商户）。
        String[] detailWords = {"小计", "配送费", "运费", "打包费", "餐盒费", "包装费",
                "优惠", "折扣", "红包", "积分", "手续费", "服务费", "原价", "单价",
                "商品金额", "商品合计", "数量", "共减", "已减", "抹零", "找零", "税费"};
        String[] noise = {"订单", "详情", "支付成功", "交易成功", "账单", "收银台",
                "确认支付", "请确认", "已优惠", "查看", "返回", "取消", "备注"};
        // 第一优先：往上找「不含金额的纯文字行」——店名几乎总是不带钱的
        for (int i = amountLine - 1; i >= 0 && i >= amountLine - 6; i--) {
            String t = lines.get(i) == null ? "" : lines.get(i).trim();
            if (!plausibleMerchantLine(t, detailWords, noise)) continue;
            if (hasMoney(t)) continue;                 // 带金额的是明细，不是店名
            return t.length() > 20 ? t.substring(0, 20) : t;
        }
        // 第二优先：放宽到「可能是商户的任意行」
        for (int i = amountLine - 1; i >= 0 && i >= amountLine - 6; i--) {
            String t = lines.get(i) == null ? "" : lines.get(i).trim();
            if (!plausibleMerchantLine(t, detailWords, noise)) continue;
            String stripped = merchantFromLine(t);
            if (!stripped.isEmpty() && !isLabelWord(stripped)) return stripped;
        }
        return "";
    }

    /** 这一行是否有资格被当成商户名（长度、非纯数字、非日期、非明细） */
    private static boolean plausibleMerchantLine(String t, String[] detailWords, String[] noise) {
        if (t == null || t.length() < 2 || t.length() > 30) return false;
        if (t.matches("^[\\d\\s\\-—:：./%]+$")) return false;
        if (t.matches("^\\d{4}[-/年].*")) return false;
        if (t.matches("^\\d{1,2}[:：]\\d{2}.*")) return false;
        String noNum = t.replaceAll("[\\d\\s.,¥￥:：·•*\\-—%]", "");
        for (String d : detailWords) if (noNum.contains(d)) return false;
        for (String n : noise) if (t.contains(n)) return false;
        if (!t.matches(".*[\\u4e00-\\u9fa5]{2,}.*") && t.length() < 4) return false;
        return true;
    }

    /** 行里是否带金额 */
    private static boolean hasMoney(String t) {
        if (t == null) return false;
        return t.matches(".*[¥￥]\\s*\\d.*") || t.matches(".*\\d+[.,]\\d{1,2}\\s*元.*")
                || t.matches(".*\\b\\d+\\.\\d{1,2}\\b.*");
    }

    // ==================== 主入口 ====================
    //
    //  两个入口，精度不同：
    //
    //   parseNotification(title, text, bigText, pkg)  ← 通知监听通道，**精度最高**
    //       保留字段结构，知道「哪句是标题、哪句是正文」。
    //       title 常常是 App 名（微信支付/支付宝/抖音），
    //       拼进正文就会污染商户名 —— 这是错账的最大来源。
    //
    //   parse(raw, pkg)  ← 无障碍兜底 / 短信整段
    //       只有一整串，结构已经丢了，只能靠启发式。
    //
    //  同一个 App 同一条通知，走 parseNotification 的结果严格优于走 parse。

    /**
     * 从结构化的通知字段解析 —— 自动记账的**首选入口**。
     *
     * 关键设计：标题不当正文用。
     * 真机 dumpsys 实测：
     *   title = "微信支付"、text = "支付成功 ¥128.50 收款方：星巴克…"
     * 若把 title 拼进正文，「微信支付」里的「微信」会被当商户名。
     *
     * 所以分两步：
     *   1) 正文（bigText 优先 / text 次之）单独走解析，得到干净的结果
     *   2) 标题只在**正文没给出商户**时才当候选，且必须通过合理性校验
     *
     * @param title   通知标题（通常是 App 名或联系人）
     * @param text    通知正文（可能被系统截断）
     * @param bigText 完整正文（有则优先）
     * @param pkg     来源包名
     */
    public ParseResult parseNotification(String title, String text, String bigText, String pkg) {
        return parseNotification(title, text, bigText, pkg, 100, true);
    }

    /**
     * 带置信度上限的版本（标题可当商户）。
     */
    public ParseResult parseNotification(String title, String text, String bigText, String pkg,
                                         int confCap) {
        return parseNotification(title, text, bigText, pkg, confCap, true);
    }

    /**
     * 完整版本。
     *
     * @param confCap         置信度上限
     * @param allowTitleMerchant 标题能否当商户名。
     *                    false 用于聊天会话/群聊：标题是人名或群名，
     *                    用它当商户会得到「工作群」这种用户无法解释的账目。
     *
     * 这两个参数**必须分开**。早先实现用 confCap<0 兼作「禁用标题」，
     * 结果群聊场景传 -1 时置信度也失去了上限，
     * 「张三: 已转账 200.00元」以 conf=75 直接入账（端到端考卷抓到的）。
     */
    public ParseResult parseNotification(String title, String text, String bigText, String pkg,
                                         int confCap, boolean allowTitleMerchant) {
        ParseResult r = parseNotificationCore(title, text, bigText, pkg, allowTitleMerchant);

        // 上限必须在最后统一施加 —— 中间任何一步（银行名/标题兜底）都可能加分
        // 把低可信通知又顶回自动入账区间。
        if (r != null && confCap >= 0 && r.confidence > confCap) {
            r.confidence = confCap;
        }
        return r;
    }

    private ParseResult parseNotificationCore(String title, String text, String bigText,
                                              String pkg, boolean allowTitleMerchant) {
        String t = title == null ? "" : title.trim();
        String body = bigText != null && !bigText.trim().isEmpty() ? bigText.trim()
                : (text == null ? "" : text.trim());

        // 正文全空：只能用标题，但标题往往是 App 名，价值很低
        if (body.isEmpty()) {
            if (t.isEmpty()) return new ParseResult();
            return parse(t, pkg);
        }

        // ---- 第一步：正文单独解析（不看标题，拿到最干净的结果）----
        ParseResult r = parse(body, pkg);

        // ---- 第二步：标题作为补充信息 ----
        if (t.isEmpty() || t.equals(pkg)) return r;

        // 标题是 App 名 / 渠道名 → 绝不作为商户
        boolean titleIsAppName = isAppNameLike(t, pkg);

        if (!r.ok) {
            // 正文没解析出来，但标题+正文合起来也许能成（极少见）
            return r;
        }

        // 商户缺失或只有平台名时，标题才可能是更好的答案（如「李四 收到转账 ¥200」）
        String mer = r.merchant == null ? "" : r.merchant;
        boolean merchantUnknown = mer.isEmpty() || "其他".equals(mer) || "未识别".equals(mer);
        boolean merchantIsPlatform = merchantUnknown || mer.equals(platformName(pkg))
                || containsAny(mer, CHANNEL_NAMES);

        if (titleIsAppName) {
            // 标题是 App 名：正文已经给出真实商户时，App 名**不得**覆盖它
            // （「微信支付」不能压过「星巴克」）。
            //
            // 但正文根本给不出商户时，App 名就是最佳兜底。
            // 真机实测：通知来自 com.android.shell 时 platformName 只能是「其他」，
            // 于是「小米钱包 您已使用小米公交卡刷卡」的商户被记成「其他」——
            // 而用户明明看得到 App 是小米钱包。标题是 App 名 ≠ 标题没用。
            if (merchantUnknown && allowTitleMerchant) {
                String app = platformName(pkg);
                // pkg 认不出来（shell/adb 等）时，退回标题本身
                if ("其他".equals(app) && plausibleTitleAsMerchant(t)) app = t;
                if (!"其他".equals(app)) {
                    r.merchant = app.length() > 20 ? app.substring(0, 20) : app;
                    // App 名只是兜底，可信度不该因此变高
                    r.confidence = Math.max(0, r.confidence - 5);
                }
            }
        } else if (allowTitleMerchant) {
            // 标题是银行名：账单通知里银行就是商户（「招商银行信用卡」+「本期账单已还清」）
            if (isBankTitle(t) && (merchantIsPlatform || mer.length() < t.length())) {
                String bank = extractBankNameFromTitle(t);
                if (!bank.isEmpty()) {
                    r.merchant = bank;
                    r.confidence = Math.min(100, r.confidence + 8);
                }
            } else if (merchantIsPlatform && plausibleTitleAsMerchant(t)) {
                r.merchant = t.length() > 20 ? t.substring(0, 20) : t;
                // 标题补出了真实商户，可信度提升
                r.confidence = Math.min(100, r.confidence + 8);
            }
        }
        // allowTitleMerchant == false（聊天会话/群聊）：
        // 标题是人名或群名，**任何情况下都不当商户**。
        // 微信群里「张三: 转你200」若记成「工作群」这种账目，用户无法解释。如果这里留空，后面还会走平台兜底（微信/支付宝），
        // 那是可接受的答案 —— 至少用户知道钱是从哪付的。
        

        // 正文里完全没有方向词时，标题里的「收款/退款/转账」可以定方向。
        //
        // 但**必须排除 App 名**：真机实测「支付宝 + 余额宝昨日收益0.52元」
        // 走这条分支时，标题「支付宝」本身含「支付」，被 detectType 判成支出，
        // 把正文已经算对的「收入」覆盖掉了。
        // 标题只是兜底线索，它不该有能力推翻正文的结论。
        if (!hasDirWord(body) && hasDirWord(t) && !titleIsAppName) {
            int tt = detectType(t);
            // 只在标题给出了**明确且与正文不冲突**的信号时才采信
            if (tt != 0 && tt != r.type) {
                boolean titleIncome = tt == 2;
                boolean titleHasStrong = titleIncome
                        ? (t.contains("收款") || t.contains("退款") || t.contains("到账")
                           || t.contains("入账") || t.contains("工资") || t.contains("收益"))
                        : (t.contains("支出") || t.contains("扣款") || t.contains("消费"));
                if (titleHasStrong) r.type = tt;
            }
        }

        return r;
    }

    /**
     * 标题是否像「App 名」而不是「商户/联系人」。
     *
     * 判定依据（任一命中即是 App 名）：
     *   · 与包名对应的平台名相同或包含（微信支付/支付宝/美团…）
     *   · 是已知渠道词（CHANNEL_NAMES）
     *   · 形如「招商银行信用卡」这种「银行+卡种」
     *   · 纯英文/数字（如 95588、CMB）
     *
     * 注意「银行」单独处理：标题「招商银行信用卡」虽然是机构名，
     * 但在这类账单通知里它**就是商户**（钱是跟这家银行结算的），
     * 所以由 caller 通过 isBankTitle() 单独判断，不走这里的拒绝逻辑。
     */
    private static boolean isAppNameLike(String title, String pkg) {
        if (title == null || title.isEmpty()) return true;
        String t = title.trim();

        // 银行名优先当商户，不算 App 名
        if (isBankTitle(t)) return false;

        String pn = platformName(pkg);
        if (t.equals(pn)) return true;
        if (!pn.isEmpty() && (t.startsWith(pn) || pn.startsWith(t))) return true;

        if (containsAny(t, CHANNEL_NAMES)) return true;

        String[] apps = {"微信支付", "微信", "支付宝", "美团", "饿了么", "淘宝", "天猫",
                "京东", "拼多多", "抖音", "快手", "滴滴", "云闪付", "银联"};
        for (String a : apps) if (t.contains(a)) return true;

        // 纯英文/数字/符号 → 号码类标题（95588、CMB）不是商户
        if (t.matches("^[A-Za-z0-9\\s\\-+_]+$")) return true;

        return false;
    }

    /** 标题是否是银行/金融机构名 —— 这类标题可以当商户 */
    private static boolean isBankTitle(String t) {
        if (t == null) return false;
        String s = t.trim();
        // 「招商银行信用卡」「工商银行」都算
        if (s.matches(".*[\\u4e00-\\u9fa5]{2,8}(?:银行|信用社).*")) return true;
        // 招行/工行/建行这类简称
        String[] shortNames = {"招行", "工行", "建行", "农行", "中行", "交行",
                "邮储", "浦发", "中信", "民生", "光大", "华夏", "兴业", "平安", "广发"};
        for (String n : shortNames) if (s.startsWith(n)) return true;
        return false;
    }

    /**
     * 从标题里剥出银行名。
     * 「招商银行信用卡」→ 招商银行；「工商银行」→ 工商银行；「招行」→ 招行
     */
    private static String extractBankNameFromTitle(String t) {
        if (t == null) return "";
        String s = t.trim();
        // 先去掉卡种/业务后缀
        s = s.replaceAll("(?:信用卡|储蓄卡|借记卡|卡|账单|还款|提醒|通知|服务)+$", "").trim();
        if (s.isEmpty()) return "";
        // 若已含「银行」直接返回
        if (s.matches(".*[\\u4e00-\\u9fa5]{2,8}(?:银行|信用社)$")) return s;
        return s;
    }

    /** 标题能否当商户：得像个名字，不能是标签词/时间/纯数字 */
    private static boolean plausibleTitleAsMerchant(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (s.length() < 2 || s.length() > 20) return false;
        if (isLabelWord(s)) return false;
        if (hasMoney(s)) return false;
        if (s.matches("^[\\d\\s\\-—:：./%+]+$")) return false;
        if (s.matches("^\\d{4}[-/年].*")) return false;
        if (s.matches("^\\d{1,2}[:：]\\d{2}.*")) return false;
        // 至少要有两个中文字，或一个中文+数字（如「星巴克」）
        return s.matches(".*[\\u4e00-\\u9fa5]{2,}.*");
    }

    public ParseResult parse(String rawIn, String platform) {
        ParseResult r = new ParseResult();
        String raw = normalize(rawIn);
        if (raw.isEmpty()) return r;

        // ② 噪音拦截
        if (blocked(raw)) return r;

        // ③ 否定语境：不是用户支出，直接丢
        if (containsAny(raw, DROP_CONTEXTS)) return r;

        // ④ 金额（先遮盖订单号/日期/验证码等非金额数字）
        //    择优而非取第一个：一条通知里常有多个金额（小计/运费/优惠/实付/余额），
        //    取错了账本就失真。pickAmount 按「实付>合计>小计>优惠>余额」打分。
        //
        //    注意别写成 `cand != null ? cand.fen : extractAmount(masked)`：
        //    cand.fen 是 long、extractAmount 返回 Long，三元运算符会把 Long 强制拆箱，
        //    extractAmount 返回 null 时直接 NPE（CandidateSelftest 实测踩到）。
        String masked = maskNonAmount(raw);
        Long fen = null;
        AmtCand cand = pickAmount(masked);
        if (cand != null) {
            fen = Long.valueOf(cand.fen);
        } else {
            fen = extractAmount(masked);
        }
        if (fen == null) return r;

        // ④.5 子句切割：本笔的商户/条目只看金额所在子句
        String focus = focusClause(raw, fen);

        // ④.6 场景与渠道（场景通常写在通知标题里，用整段取；渠道本句优先、整段兜底）
        String scene = extractScene(raw);
        int invType = investType(scene);
        String channel = extractChannel(focus.contains("余额宝") || focus.contains("花呗")
                || focus.contains("零钱") || focus.contains("尾号") ? focus : raw);

        // ⑤ 方向：投资场景优先（申购/赎回是资金流向，不受消费词干扰）；
        //    否则金额子句里有方向词就用子句的（更准），没有才退回整段
        int type;
        if (invType != 0) {
            type = invType;
        } else if (hasDirWord(focus)) {
            type = detectType(focus);
        } else {
            type = detectType(raw);
        }

        // ⑥ 商户：投资品名 > 「标签+值」> 银行名 > 金额子句 > 整段
        //    真机三类错账都在这层修好：
        //      退款「商户名称 京东商城」→ 旧逻辑取到「名称」
        //      工资「【招商银行】…入账…」→ 旧逻辑退化成平台名「短信」
        //      红包「收款人 张小明」   → 旧逻辑取到「微信」
        String merchant;
        String prod = investProduct(raw);
        if (prod != null) {
            merchant = prod;
        } else {
            String labeled = valueAfterLabel(raw, MERCHANT_LABELS);
            if (labeled.isEmpty() && (raw.contains("【") || raw.contains("】"))) {
                labeled = extractBankName(raw);
            }
            if (!labeled.isEmpty()) {
                merchant = labeled;
            } else {
                String fb = platformName(platform);
                String mFocus = detectMerchant(focus, platform);
                merchant = (!mFocus.isEmpty() && !mFocus.equals(fb))
                        ? mFocus : detectMerchant(raw, platform);
            }
        }

        // ⑦ 条目：金额子句里的商品/费用 > 场景词（不再从整段捞「手续费」）
        String items = extractItems(focus);
        if (items.isEmpty()) items = scene;
        if (items.isEmpty() && focus.equals(raw)) items = extractItems(raw);

        // ⑧ 置信度
        int conf = scoreConfidence(raw, platform, merchant, type);
        if (!scene.isEmpty()) conf += 5;          // 场景明确
        if (!channel.isEmpty()) conf += 5;        // 渠道明确

        r.ok = true;
        r.amountFen = fen;
        r.type = type;
        r.merchant = fixBrackets(merchant);
        r.scene = scene;
        r.channel = channel;
        // 条目里带上渠道，详情页「条目」一行即可看到「基金申购 · 支付宝」
        r.note = channel.isEmpty() ? items
                : (items.isEmpty() ? channel : items + " · " + channel);
        r.confidence = Math.max(0, Math.min(100, conf));
        r.rawAmount = String.valueOf(fen / 100.0);
        // 单条通知不设置 line：分类用整段（标题常含「餐厅/基金」等商户线索），
        // 条目/渠道/方向才用金额子句，避免「手续费」这类别处词污染
        return r;
    }

    private static boolean hasDirWord(String s) {
        return containsAny(s, INCOME_WORDS) || containsAny(s, EXPENSE_WORDS);
    }

    /**
     * 多金额候选：一张截图（如账单列表）可能含多笔交易。
     * 按行独立解析（每行用自己的金额/方向/商户），避免全文互相污染
     * （+100 转账被整段的「支出」语境带偏、商户全标成第一个命中词）。
     * 同金额去重；行解析失败时退回整段解析。
     */
    public java.util.List<ParseResult> parseAll(String rawIn, String platform) {
        java.util.List<ParseResult> out = new java.util.ArrayList<>();
        String raw = normalize(rawIn);
        if (raw.isEmpty()) return out;
        if (blocked(raw)) return out;

        java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();

        // 按行独立解析：账单列表截图每行通常就是一笔（用原始文本切行，
        // 因为 normalize 会把换行压成空格）
        String[] lines = rawIn.split("[\\r\\n。]+");
        for (String rawLine : lines) {
            String l = normalize(rawLine);
            if (l.length() < 4) continue;
            if (blocked(l)) continue;
            if (containsAny(l, DROP_CONTEXTS)) continue;
            String masked = maskNonAmount(l);
            for (Pattern p : new Pattern[]{P_CURRENCY, P_NEG, P_PREFIX, P_UNIT}) {
                Matcher m = p.matcher(masked);
                while (m.find()) {
                    Long fen = toFen(m.group(1));
                    if (fen == null || fen <= 0 || fen > 100_000_000L) continue;
                    if (!seen.add(fen)) continue;
                    ParseResult r = new ParseResult();
                    r.ok = true;
                    r.amountFen = fen;
                    // 行内方向：账单列表行常用 +N / -N 显式收支符号
                    String num = m.group(1);
                    boolean plus = l.contains("+" + num) || l.contains("+ " + num);
                    boolean minus = l.contains("-" + num) || l.contains("- " + num);
                    int type = plus ? 2 : (minus ? 1 : detectType(l));
                    r.type = type;
                    r.merchant = detectMerchant(l, platform);
                    // OCR 行候选：商户兜底没意义，取金额前的文本片段当商户
                    if ("ocr".equals(platform) && (r.merchant == null
                            || r.merchant.isEmpty() || "未识别".equals(r.merchant)
                            || "截图识别".equals(r.merchant))) {
                        int numIdx = masked.indexOf(num);
                        String head = numIdx > 0 ? masked.substring(0, numIdx).trim() : "";
                        // 去掉行首多余符号/空格，截 14 字
                        head = head.replaceAll("[\\s:：,，;；\\-+]+$", "");
                        if (head.length() > 14) head = head.substring(head.length() - 14);
                        if (!head.isEmpty()) r.merchant = head;
                    }
                    r.note = extractItems(l);
                    // 行内场景/渠道（账单列表行通常自带「余额宝」「基金申购」等）
                    String rowScene = extractScene(l);
                    int rowInv = investType(rowScene);
                    if (rowInv != 0) type = rowInv;
                    r.scene = rowScene;
                    r.channel = extractChannel(l);
                    if (!r.channel.isEmpty()) {
                        r.note = r.note.isEmpty() ? r.channel : r.note + " · " + r.channel;
                    }
                    int conf = scoreConfidence(l, platform, r.merchant, type);
                    // 显式收支符号 = 明确的交易记录，大幅加分
                    if (plus || minus) conf = Math.min(100, conf + 40);
                    r.confidence = conf;
                    r.rawAmount = String.valueOf(fen / 100.0);
                    r.line = l;   // 候选来源行：分类/展示用，避免整段文本污染
                    if (r.confidence >= 50) out.add(r);
                }
            }
        }

        // 行解析全失败时，退回整段解析
        if (out.isEmpty()) {
            ParseResult single = parse(rawIn, platform);
            if (single.ok && single.confidence >= 50) out.add(single);
        }
        return out;
    }

    // ==================== 工具 ====================

    /** 包名 → 展示用中文名 */
    /**
     * 包名 → 人类可读的应用名。
     *
     * 这张表不只是「好看」：detectMerchant 在没有别的线索时会兜底返回
     * platformName(platform)，所以漏配的包名会**原样写进账本**。
     * 真机实测踩到两次：
     *   商户 = com.greenpoint.android.mc10086.activity
     *   商户 = com.mipay.wallet
     * 用户看到的就是一串包名。
     *
     * 命中的都是用户真机实际安装、且**会推交易通知**的 App。
     */
    private static final Map<String, String> PKG_NAMES = new HashMap<>();
    static {
        String[][] t = {
            // 支付/电商
            {"com.tencent.mm", "微信"}, {"com.eg.android.AlipayGphone", "支付宝"},
            {"com.unionpay.tsmservice.mi", "云闪付"}, {"com.mipay.wallet", "小米钱包"},
            {"com.taobao.taobao", "淘宝"}, {"com.taobao.idlefish", "闲鱼"},
            {"com.jingdong.app.mall", "京东"}, {"com.xunmeng.pinduoduo", "拼多多"},
            {"com.sankuai.meituan", "美团"}, {"com.sankuai.meituan.takeoutnew", "美团外卖"},
            {"com.ss.android.ugc.aweme", "抖音"}, {"com.smile.gifmaker", "快手"},
            {"com.kuaishou.nebula", "快手极速版"}, {"com.xingin.xhs", "小红书"},
            // 出行
            {"com.sdu.didi.psnger", "滴滴"}, {"com.autonavi.minimap", "高德地图"},
            {"com.baidu.BaiduMap", "百度地图"}, {"com.MobileTicket", "铁路12306"},
            {"com.ygkj.chelaile.standard", "车来了"},
            // 三大运营商（真机都装了，且都会发话费/流量类通知）
            {"com.greenpoint.android.mc10086.activity", "中国移动"},
            {"com.sinovatech.unicom.ui", "中国联通"},
            {"com.ct.client", "中国电信"},
            // 银行
            {"com.chinamworld.main", "中国建设银行"},
            {"com.chinamworld.bocmbci", "中国银行"},
            {"com.icbc", "中国工商银行"}, {"com.android.bankabc", "中国农业银行"},
            {"cmb.pb", "招商银行"}, {"com.cmbchina.ccd.pluto.cmbActivity", "招商银行信用卡"},
            {"com.bankcomm.Bankcomm", "交通银行"}, {"com.ecitic.bank.mobile", "中信银行"},
            {"com.yitong.mbank.psbc", "邮储银行"},
            // 工作/生活
            {"com.alibaba.android.rimet", "钉钉"}, {"com.tencent.wework", "企业微信"},
            {"com.tencent.mobileqq", "QQ"}, {"com.tencent.weread", "微信读书"},
            // 系统
            {"com.android.mms", "短信"}, {"com.android.systemui", "通知栏"},
            {"com.aron.autobill", "手动/本机"}, {"ocr", "截图识别"},
        };
        for (String[] x : t) PKG_NAMES.put(x[0], x[1]);
    }

    public static String platformName(String pkg) {
        if (pkg == null) return "其他";
        String hit = PKG_NAMES.get(pkg);
        if (hit != null) return hit;
        // 未收录的包名：绝不能原样返回（会变成一串 com.xxx.yyy 写进账本）
        return "其他";
    }

    /** 这个包名是否已知 —— 未知包名不能拿来做任何兜底 */
    static boolean knownPackage(String pkg) {
        return pkg != null && PKG_NAMES.containsKey(pkg);
    }

    static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static int lastIndexOfAny(String s, String[] words) {
        int best = -1;
        for (String w : words) {
            int i = s.lastIndexOf(w);
            if (i > best) best = i;
        }
        return best;
    }
}
