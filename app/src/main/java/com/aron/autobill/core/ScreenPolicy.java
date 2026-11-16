package com.aron.autobill.core;

/**
 * 读屏准入策略 —— 决定「某个 App 的屏幕文字，要不要拿去记账」。
 *
 * ==================== 为什么需要这一层 ====================
 *
 * 通知通道和读屏通道的可信度天差地别：
 *
 *   通知：「支付成功 ¥36.50 收款方：瑞幸咖啡」  ← App 主动告诉你的结论
 *   读屏：「¥36.50  ¥9.9  ¥29.9 立即购买 加入购物车 …」  ← 你自己猜的
 *
 * 真实翻车：用户在拼多多刷商品，商品列表页每屏都有几十个「¥xx.xx」，
 * 无障碍每 800ms 抓一次整屏文本 → 每滑一屏就产生一条假账单。
 * 用户原话：「刷一会拼多多全是账单」。
 *
 * 根因不是解析器不准，而是**读屏这个数据源本身对商品浏览页无效**。
 * 商品页的价钱是「展示」，不是「交易」。
 *
 * ==================== 解决思路 ====================
 *
 * 分三层，从粗到细：
 *
 *   ① 应用级开关（用户可控）
 *      每个 App 单独开关读屏。用户说「拼多多别读屏」就真的不读。
 *      这是最终裁决权 —— 任何自动规则都比不上用户自己关掉。
 *
 *   ② 应用信任级别（默认策略）
 *      TRUSTED  ：微信/支付宝。读屏也认（付款页文字是结账结论）。
 *      CAUTIOUS ：购物/外卖/出行类。只有看到**结账凭证**才记账。
 *      OFF      ：默认不读屏（系统界面、输入法、新闻等）。
 *
 *   ③ 结账凭证门禁（CAUTIOUS 的核心）
 *      屏幕文本里必须同时出现「交易已完成的证据」，否则一律不记。
 *      证据分两类：
 *        · 成功动词：支付成功 / 付款成功 / 已支付 / 交易完成 / 已付款
 *        · 结果页特征：实付款 / 支付方式 / 订单编号 / 交易单号
 *
 *      商品页的「¥9.9 立即购买 加入购物车」一个都不满足 → 直接放弃。
 *
 *   注意：CAUTIOUS 不是「不记账」。用户真的在拼多多下单并付款时，
 *   结算成功页会出现「实付款 ¥xx.xx 支付方式 支付宝」，
 *   这类文本能通过门禁 —— 只是要求更严格。
 */
public final class ScreenPolicy {

    private ScreenPolicy() {}

    // ==================== 应用信任级别 ====================

    /** 完全信任：读屏文本可直接进解析器 */
    public static final int TRUSTED = 0;
    /** 谨慎：必须命中结账凭证才记账 */
    public static final int CAUTIOUS = 1;
    /** 关闭：不读屏（通知通道仍然照常工作） */
    public static final int OFF = 2;

    public static String levelName(int lv) {
        switch (lv) {
            case TRUSTED: return "信任";
            case CAUTIOUS: return "需凭证";
            case OFF: return "不读屏";
            default: return "?";
        }
    }

    // ==================== 应用分类表 ====================

    /**
     * 默认信任级别。
     *
     * 判据不是「这个 App 会不会花钱」，而是
     * **「它的屏幕上会不会出现大量与交易无关的金额」**：
     *
     *   · 微信/支付宝：付款结果页文字就是结账结论，几乎没有商品列表 → TRUSTED
     *   · 电商/外卖：满屏商品价、优惠价、划线价 → CAUTIOUS（必须看到凭证）
     *   · 银行/运营商：账单页文字是正式账单 → TRUSTED
     *   · 其余：默认 OFF，让通知通道去干活
     *
     * 用户可以在设置里逐项覆盖（见 Prefs.screenLevel）。
     */
    private static final java.util.Map<String, Integer> DEFAULT_LEVEL =
            new java.util.HashMap<>();

    /** 有中文名的包 → 展示名，用于设置页列表 */
    private static final java.util.Map<String, String> LABELS = new java.util.HashMap<>();

    static {
        // ---- 信任：支付工具的结账页 ----
        put("com.tencent.mm", "微信", TRUSTED);
        put("com.eg.android.AlipayGphone", "支付宝", TRUSTED);
        put("com.unionpay.tsmservice.mi", "云闪付", TRUSTED);
        put("com.mipay.wallet", "小米钱包", TRUSTED);

        // ---- 信任：银行的账单/交易页 ----
        put("com.chinamworld.main", "中国建设银行", TRUSTED);
        put("com.chinamworld.bocmbci", "中国银行", TRUSTED);
        put("com.icbc", "中国工商银行", TRUSTED);
        put("com.android.bankabc", "中国农业银行", TRUSTED);
        put("cmb.pb", "招商银行", TRUSTED);
        put("com.cmbchina.ccd.pluto.cmbActivity", "招商银行信用卡", TRUSTED);
        put("com.bankcomm.Bankcomm", "交通银行", TRUSTED);
        put("com.ecitic.bank.mobile", "中信银行", TRUSTED);
        put("com.yitong.mbank.psbc", "邮储银行", TRUSTED);

        // ---- 信任：运营商账单 ----
        put("com.greenpoint.android.mc10086.activity", "中国移动", TRUSTED);
        put("com.sinovatech.unicom.ui", "中国联通", TRUSTED);
        put("com.ct.client", "中国电信", TRUSTED);

        // ---- 谨慎：电商 / 外卖 / 出行 / 团购 ----
        // 这些 App 的浏览页是假账重灾区：商品价、优惠价、划线价满屏都是。
        put("com.xunmeng.pinduoduo", "拼多多", CAUTIOUS);
        put("com.taobao.taobao", "淘宝", CAUTIOUS);
        put("com.taobao.idlefish", "闲鱼", CAUTIOUS);
        put("com.jingdong.app.mall", "京东", CAUTIOUS);
        put("com.sankuai.meituan", "美团", CAUTIOUS);
        put("com.sankuai.meituan.takeoutnew", "美团外卖", CAUTIOUS);
        put("me.ele", "饿了么", CAUTIOUS);
        put("com.xingin.xhs", "小红书", CAUTIOUS);
        put("com.ss.android.ugc.aweme", "抖音", CAUTIOUS);
        put("com.smile.gifmaker", "快手", CAUTIOUS);
        put("com.kuaishou.nebula", "快手极速版", CAUTIOUS);
        put("com.sdu.didi.psnger", "滴滴", CAUTIOUS);
        put("com.autonavi.minimap", "高德地图", CAUTIOUS);
        put("com.baidu.BaiduMap", "百度地图", CAUTIOUS);
        put("com.MobileTicket", "铁路12306", CAUTIOUS);
        put("com.ygkj.chelaile.standard", "车来了", CAUTIOUS);
        put("com.jingdong.pdj", "京东到家", CAUTIOUS);
        put("com.achievo.vipshop", "唯品会", CAUTIOUS);
        put("ctrip.android.view", "携程", CAUTIOUS);
        put("com.tongcheng.android", "同程旅行", CAUTIOUS);
        put("com.cainiao.wireless", "菜鸟", CAUTIOUS);
        put("com.taobao.trip", "飞猪", CAUTIOUS);
        put("com.mcdonalds.gma.cn", "麦当劳", CAUTIOUS);
    }

    private static void put(String pkg, String label, int level) {
        DEFAULT_LEVEL.put(pkg, level);
        LABELS.put(pkg, label);
    }

    /** 该包的出厂信任级别；未收录的包 → OFF（不读屏） */
    public static int defaultLevel(String pkg) {
        if (pkg == null) return OFF;
        Integer v = DEFAULT_LEVEL.get(pkg);
        return v == null ? OFF : v;
    }

    /** 该包的中文展示名；未收录返回空串 */
    public static String label(String pkg) {
        if (pkg == null) return "";
        String s = LABELS.get(pkg);
        return s == null ? "" : s;
    }

    /** 是否在出厂表里（设置页只列这些，避免把几百个系统包摆出来） */
    public static boolean known(String pkg) {
        return pkg != null && DEFAULT_LEVEL.containsKey(pkg);
    }

    /** 出厂表里的全部分组，供设置页按类展示（包名 → 中文名） */
    public static java.util.List<String> knownPackages() {
        java.util.List<String> list = new java.util.ArrayList<>(DEFAULT_LEVEL.keySet());
        java.util.Collections.sort(list, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int ra = defaultLevel(a), rb = defaultLevel(b);
                if (ra != rb) return ra - rb;      // 按信任级别排：信任 → 需凭证
                return label(a).compareTo(label(b));
            }
        });
        return list;
    }

    // ==================== 决策入口 ====================

    public static final class Decision {
        /** 是否允许把这段屏幕文本交给解析器 */
        public boolean allow;
        /** 人类可读原因，写日志便于排查 */
        public String reason = "";
        /** 置信度上限（谨慎模式命中凭证后可正常记账，未命中则不许记） */
        public int confCap = 100;

        Decision(boolean a, String why) { allow = a; reason = why; }
        @Override public String toString() { return (allow ? "允许" : "跳过") + "(" + reason + ")"; }
    }

    private static Decision no(String why) { return new Decision(false, why); }
    private static Decision yes(String why) { return new Decision(true, why); }

    /**
     * 决定一段屏幕文本能不能记账。
     *
     * @param pkg        当前 App 包名
     * @param screenText 整屏可见文本（collectText 的结果）
     * @param userLevel  用户在设置里指定的级别（-1 = 用出厂默认）
     */
    public static Decision decide(String pkg, String screenText, int userLevel) {
        int lv = userLevel >= 0 ? userLevel : defaultLevel(pkg);

        if (lv == OFF) {
            String nm = label(pkg);
            return no("该应用不读屏" + (nm.isEmpty() ? "" : "(" + nm + ")"));
        }

        String s = screenText == null ? "" : screenText;

        // 无论哪一级，商品浏览页的特征一旦出现就放弃 —— 这是最大宗的假账来源。
        // 注意顺序：先看商品页特征，再看凭证。
        // 因为「加入购物车」页面也可能带「¥xx 支付方式」，先判特征能少错一批。
        if (looksLikeBrowsing(s)) {
            return no("商品浏览页");
        }

        if (lv == TRUSTED) {
            // 信任级：只要不是浏览页就放行，交给解析器的置信度分档去把关
            return yes("信任应用");
        }

        // ---- 谨慎级：必须看到结账凭证 ----
        if (!hasCheckoutProof(s)) {
            return no("未看到结账凭证");
        }
        return yes("命中结账凭证");
    }

    // ==================== 商品浏览页特征 ====================

    /**
     * 商品浏览页的判定。
     *
     * 只看**足够多**的商品页特征词，避免误伤：
     *   · 「加入购物车」单独出现 → 一定是商品页
     *   · 「立即购买」单独出现 → 一定是商品页
     *   · 「优惠券」「已售」「评价」「¥xx起」同时出现 → 也是商品页
     *
     * 这些词在**结账成功页里不会出现**：付款完的页面是
     * 「支付成功 实付款 ¥36.50 支付方式 支付宝」，没有购物车按钮。
     */
    private static final String[] BROWSE_STRONG = {
            "加入购物车", "立即购买", "立即抢购", "马上抢", "去结算", "去付款",
            "店铺", "商品详情", "累计评价", "已售", "收藏", "客服",
            "领券", "优惠券", "限时秒杀", "百亿补贴", "拼单", "购物车",
            "全选", "已拼", "加购", "搜同款", "找相似",
    };

    /** 单独出现即可判定为浏览页的铁证词 */
    private static final String[] BROWSE_IRONCLAD = {
            "加入购物车", "立即购买", "立即抢购", "马上抢", "去结算", "购物车",
    };

    static boolean looksLikeBrowsing(String s) {
        if (s == null || s.isEmpty()) return false;

        // 结账成功页的硬证据：有它就不是浏览页
        // （付款完成页不会有「加入购物车」，但可能残留「店铺」等字样，
        //   所以必须先确认成功凭证再说）
        boolean checkout = hasStrongSuccess(s);

        // 铁证词单独出现即可判定 —— 但结账页优先
        if (!checkout) {
            for (String w : BROWSE_IRONCLAD) {
                if (s.contains(w)) return true;
            }
        }

        int hits = 0;
        for (String w : BROWSE_STRONG) {
            if (s.contains(w)) hits++;
        }
        // 其余特征词累计 3 个以上才算浏览页
        return !checkout && hits >= 3;
    }

    // ==================== 结账凭证 ====================

    /** 明确的成功动词：交易已经发生 */
    private static final String[] SUCCESS_VERBS = {
            "支付成功", "付款成功", "交易成功", "交易完成", "支付完成", "付款完成",
            "已支付", "已付款", "扣款成功", "付款给", "支付给", "支付金额",
    };

    /** 结果页的结构特征：出现在付款结果页，不出现在商品页 */
    private static final String[] RESULT_MARKS = {
            "实付款", "实付", "支付方式", "订单编号", "订单号", "交易单号",
            "付款时间", "支付时间", "收款方", "商户名称", "已完成",
    };

    static boolean hasStrongSuccess(String s) {
        if (s == null) return false;
        for (String w : SUCCESS_VERBS) if (s.contains(w)) return true;
        return false;
    }

    /**
     * 是否看到「结账凭证」。
     *
     * 要求：成功动词 **或** 结果页结构特征（两者都是付款后才有的东西）。
     * 单独一个「订单号」不够 —— 商品详情页也会显示订单号提示，
     * 所以再加一条：必须有金额。
     */
    static boolean hasCheckoutProof(String s) {
        if (s == null || s.isEmpty()) return false;
        if (hasStrongSuccess(s)) return true;

        boolean hasResult = false;
        for (String w : RESULT_MARKS) {
            if (s.contains(w)) { hasResult = true; break; }
        }
        return hasResult && looksLikeAmount(s);
    }

    /** 文本里有没有像金额的东西（¥36.50 / 36.50元 / 36.5元） */
    static boolean looksLikeAmount(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.matches(".*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?.*")
                || s.matches(".*\\d+(?:\\.\\d{1,2})?\\s*元.*");
    }
}
