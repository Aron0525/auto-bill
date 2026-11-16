package com.aron.autobill.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 记账分类（小米钱包风格的内置分类）
 *  - 支出：餐饮、购物、交通、生活缴费、娱乐、医疗、通讯、转账、红包、其他
 *  - 收入：工资、红包、转账、退款、其他
 *  - 自动记账时按原始文本 + 商户关键词推断，用户可在详情里手动修改
 */
public class Category {

    public static final String OTHER = "其他";

    public static final String[] EXPENSE = {
            "餐饮", "购物", "交通", "生活缴费", "娱乐", "医疗", "通讯", "转账", "红包", "投资/理财", "其他"
    };
    public static final String[] INCOME = {
            "工资", "红包", "转账", "退款", "投资/理财", "其他"
    };

    private static final Map<String, Integer> COLORS = new HashMap<>();
    static {
        COLORS.put("餐饮", 0xFFFF7043);
        COLORS.put("购物", 0xFF42A5F5);
        COLORS.put("交通", 0xFF66BB6A);
        COLORS.put("生活缴费", 0xFFFFB300);
        COLORS.put("娱乐", 0xFFAB47BC);
        COLORS.put("医疗", 0xFFEF5350);
        COLORS.put("通讯", 0xFF26A69A);
        COLORS.put("转账", 0xFF5C6BC0);
        COLORS.put("红包", 0xFFEC407A);
        COLORS.put("工资", 0xFF00897B);
        COLORS.put("退款", 0xFF29B6F6);
        COLORS.put("投资/理财", 0xFF8D6E63);
        COLORS.put("其他", 0xFF9E9E9E);
    }

    public static int colorOf(String category) {
        Integer v = COLORS.get(category);
        return v == null ? 0xFF9E9E9E : v;
    }

    // ---- 图标（emoji）+ 图标圆底色（参照设计稿的彩色圆形图标）----

    /** 分类 → emoji 图标 */
    public static String iconOf(String category) {
        if (category == null) return "🧾";
        switch (category) {
            case "餐饮": return "🍜";
            case "购物": return "🛍️";
            case "交通": return "🚗";
            case "生活缴费": return "💡";
            case "娱乐": return "🎮";
            case "医疗": return "🏥";
            case "通讯": return "📱";
            case "转账": return "💳";
            case "红包": return "🧧";
            case "工资": return "💰";
            case "退款": return "💸";
            case "投资/理财": return "📈";
            default: return "🧾";
        }
    }

    /** 图标圆底色（淡色系） */
    public static int iconBgOf(String category) {
        if (category == null) return 0xFFE3EDFF;
        switch (category) {
            case "餐饮": return 0xFFFFF3D6;
            case "购物": return 0xFFFFE8EE;
            case "交通": return 0xFFE3EDFF;
            case "生活缴费": return 0xFFFFF3D6;
            case "娱乐": return 0xFFFFE8EE;
            case "医疗": return 0xFFE3EDFF;
            case "通讯": return 0xFFFFF3D6;
            case "转账": return 0xFFE3EDFF;
            case "红包": return 0xFFFFE8EE;
            case "工资": return 0xFFE3EDFF;
            case "退款": return 0xFFFFF3D6;
            default: return 0xFFE3EDFF;
        }
    }

    /** 来源标签（参照设计稿：蓝色小字，如「美团自动记账」「图片记账」） */
    public static String sourceTag(String platform, String raw) {
        if ("手动记账".equals(raw)) return "手动";
        if (platform == null) return "";
        switch (platform) {
            case "com.tencent.mm": return "微信自动记账";
            case "com.eg.android.AlipayGphone": return "支付宝自动记账";
            case "com.sankuai.meituan": return "美团自动记账";
            case "com.jingdong.app.mall": return "京东自动记账";
            case "com.taobao.taobao": return "淘宝自动记账";
            case "com.xunmeng.pinduoduo": return "拼多多自动记账";
            case "com.ss.android.ugc.aweme": return "抖音自动记账";
            case "ocr": return "图片记账";
            case "com.android.mms": return "短信";
            case "com.android.systemui": return "通知栏";
            default: return "";
        }
    }

    public static String[] of(int type) {
        return type == 2 ? INCOME : EXPENSE;
    }

    private static final Map<String, String[]> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put("转账", new String[]{"转账", "转给", "你已向", "转账给", "群收款", "发起了群收款"});
        KEYWORDS.put("红包", new String[]{"红包", "发出了红包", "领取了红包"});
        KEYWORDS.put("退款", new String[]{"退款", "退回", "退货"});
        KEYWORDS.put("工资", new String[]{"工资", "薪资", "薪酬", "奖金", "报销"});
        KEYWORDS.put("餐饮", new String[]{"饿了么", "外卖", "餐厅", "餐饮", "华莱士", "肯德基",
                "麦当劳", "星巴克", "瑞幸", "蜜雪", "喜茶", "茶百道", "奶茶", "咖啡", "火锅", "快餐",
                "食堂", "面包", "饭店", "烧烤", "早餐", "午餐", "晚餐", "美团外卖", "美团买菜"});
        KEYWORDS.put("购物", new String[]{"淘宝", "天猫", "京东", "拼多多", "闲鱼", "商城", "购物",
                "超市", "便利店", "百货", "专卖店", "小米之家", "买菜", "生鲜"});
        KEYWORDS.put("交通", new String[]{"滴滴", "打车", "地铁", "公交", "12306", "铁路", "高铁",
                "火车", "加油", "停车", "高速", "出租车", "单车", "行程", "车费", "网约车",
                "高德", "百度地图", "公交卡", "交通卡", "地铁卡", "佩奇", "青桔", "哈啰"});
        KEYWORDS.put("生活缴费", new String[]{"话费", "电费", "水费", "燃气", "宽带", "物业",
                "充值", "缴费", "暖气"});
        KEYWORDS.put("娱乐", new String[]{"电影", "游戏", "视频", "会员", "腾讯视频", "爱奇艺",
                "哔哩哔哩", "音乐", "KTV", "演出", "门票", "steam"});
        KEYWORDS.put("医疗", new String[]{"医院", "药店", "门诊", "挂号", "诊所", "体检", "药"});
        KEYWORDS.put("通讯", new String[]{"移动", "联通", "电信", "套餐", "流量"});
        KEYWORDS.put("投资/理财", new String[]{"基金", "申购", "认购", "定投", "赎回", "理财",
                "股票", "证券", "黄金", "定期", "存款", "保险", "收益", "债卷", "债券", "份额"});
    }

    /** 候选账单专用：优先用来源行分类，没有行文本才退回整段 */
    public static String classifyFor(com.aron.autobill.model.ParseResult r, String fallbackRaw) {
        // 解析器已判定为投资场景（申购/赎回…）→ 直接归投资/理财，不再猜关键词
        if (r.scene != null && contains(r.scene, KEYWORDS.get("投资/理财"))) return "投资/理财";
        String text = (r.line != null && !r.line.isEmpty()) ? r.line : fallbackRaw;
        return classify(text, r.merchant, r.type);
    }

    /** 按原始文本 + 商户推断分类 */
    public static String classify(String raw, String merchant, int type) {        String text = (raw == null ? "" : raw) + " " + (merchant == null ? "" : merchant);

        // 转账 / 红包优先：这是用户明确要记录的场景，分类必须准
        if (contains(text, KEYWORDS.get("转账"))) return "转账";
        if (contains(text, KEYWORDS.get("红包"))) return "红包";

        // 投资/理财：基金申购、赎回、理财买入等，绝不能落进「通讯/购物」
        if (contains(text, KEYWORDS.get("投资/理财"))) return "投资/理财";

        if (type == 2) {
            if (contains(text, KEYWORDS.get("退款"))) return "退款";
            if (contains(text, KEYWORDS.get("工资"))) return "工资";
        }

        // 顺序即优先级。真机踩过的坑：
        //   美团打车 → 「美团」命中餐饮，而正确答案是交通
        //   银行消费 → 只有「消费支出」，没有任何品类词，落到「其他」
        // 所以：具体场景词（交通/医疗/娱乐）必须排在平台王牌（餐饮/购物）之前。
        String[] order = {"交通", "医疗", "娱乐", "生活缴费", "通讯", "餐饮", "购物"};
        for (String c : order) {
            if (contains(text, KEYWORDS.get(c))) return c;
        }
        // 银行/信用卡的消费提醒通常不带品类词，按「消费」兜底归购物，
        // 比笼统的「其他」更有用（用户看账本时至少能按消费聚合）
        if (text.contains("消费") || text.contains("刷卡") || text.contains("购物")) return "购物";
        return OTHER;
    }

    private static boolean contains(String s, String[] words) {
        if (words == null) return false;
        for (String w : words) {
            if (s.contains(w)) return true;
        }
        return false;
    }
}
