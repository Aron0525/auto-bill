package com.aron.autobill.selftest;

import com.aron.autobill.core.ScreenPolicy;

/**
 * 读屏准入策略考卷。
 *
 * 用户原话：「现在他无法分辨我是在浏览商品还是已经付款了，刷一会拼多多全是账单」
 *
 * 这个考卷就为这一件事而写：**浏览页绝不记账，结账页必须记账**。
 *
 * 误判的代价是不对称的：
 *   · 浏览页误记 → 每次刷商品都多几条假账，账本直接不可信（用户的原症状）
 *   · 结账页漏记 → 少记一笔，但微信/支付宝的通知通道还能兜住
 * 所以规则整体偏向「宁可漏，不可错」，但结账页必须认得出来。
 */
public class ScreenPolicySelftest {

    static int pass = 0, fail = 0;

    static final String PDD = "com.xunmeng.pinduoduo";
    static final String TB = "com.taobao.taobao";
    static final String JD = "com.jingdong.app.mall";
    static final String WX = "com.tencent.mm";
    static final String ALI = "com.eg.android.AlipayGphone";
    static final String ICBC = "com.icbc";

    /** 用出厂默认级别 */
    static ScreenPolicy.Decision d(String pkg, String text) {
        return ScreenPolicy.decide(pkg, text, -1);
    }

    static void want(String id, String desc, ScreenPolicy.Decision got, boolean expectAllow) {
        StringBuilder b = new StringBuilder(id);
        while (b.length() < 28) b.append(' ');
        if (got.allow == expectAllow) {
            pass++;
            System.out.println("  [PASS] " + b + desc + "  → " + got);
        } else {
            fail++;
            System.out.println("  [FAIL] " + b + desc);
            System.out.println("         期望=" + (expectAllow ? "允许" : "跳过")
                    + " 实际=" + got);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 读屏准入策略考卷（浏览页 vs 结账页）===");
        System.out.println();

        // ---------- A 组：拼多多商品浏览页（用户报的主症状）----------
        // 这些是商品列表/详情页真实会出现的文本组合。
        // 每一条都必须被拒 —— 有任意一条漏过，用户刷商品时就会看到假账。

        want("pdd_list_titles", "商品列表页（多个价格+购买按钮）",
                d(PDD, "百亿补贴 限时秒杀 ¥9.9 已拼10万件 加入购物车 立即购买"),
                false);

        want("pdd_detail", "商品详情页",
                d(PDD, "商品详情 ¥29.90 原价 ¥59.90 已售 3.2万件 累计评价 8万 领券 收藏 客服 立即购买"),
                false);

        want("pdd_cart", "购物车页",
                d(PDD, "购物车 全选 ￥128.00 去结算 共2件 商品金额 129.00 优惠 -1.00 运费 0.00"),
                false);

        // 购物车页必须被「商品浏览页」识别，而不是靠「没看到凭证」兜底。
        // 两者结果都是不记账，但原因不同 —— 前者是精准识别，后者是运气好。
        {
            ScreenPolicy.Decision got = d(PDD,
                    "购物车 全选 ￥128.00 去结算 共2件 商品金额 129.00");
            boolean precise = !got.allow && got.reason.contains("浏览");
            report("pdd_cart_precise", "购物车页被识别为浏览页（非兜底）", precise,
                    got.reason);
        }

        want("pdd_search", "搜索结果页",
                d(PDD, "搜索结果 综合 销量 价格 ￥15.80 ￥22.00 ￥8.90 店铺 已售 加购"),
                false);

        // 单个强特征词也要拦住 —— 商品页最典型的两个按钮
        want("pdd_buy_btn", "只有「立即购买」按钮",
                d(PDD, "¥199.00 立即购买"), false);

        want("pdd_cart_btn", "只有「加入购物车」按钮",
                d(PDD, "¥68.00 加入购物车"), false);

        // ---------- B 组：拼多多结账成功页（必须记账）----------
        // 用户真的下单付款了，这时不能漏。
        // 真机上结算成功页会出现「实付款 + 支付方式 + 订单号」。

        want("pdd_paid", "拼多多付款成功页",
                d(PDD, "支付成功 实付款 ¥36.50 支付方式 支付宝 订单编号 2411152200188 支付时间 2024-11-15 12:30"),
                true);

        want("pdd_paid_min", "拼多多最简成功页",
                d(PDD, "支付成功 实付款 ¥29.90"), true);

        // ---------- C 组：淘宝/京东同类页 ----------

        want("tb_list", "淘宝商品列表",
                d(TB, "淘宝 猜你喜欢 ￥59.00 ￥128.00 立即购买 加入购物车 店铺 已售"),
                false);

        want("tb_paid", "淘宝付款成功",
                d(TB, "支付成功 实付款 ¥88.00 支付方式 余额宝 订单号 3087..."), true);

        want("jd_list", "京东商品页",
                d(JD, "京东 商品详情 ￥2999.00 秒杀价 加入购物车 立即抢购 累计评价"),
                false);

        want("jd_paid", "京东下单成功",
                d(JD, "支付成功 实付款 ¥2999.00 支付方式 微信支付"), true);

        // ---------- D 组：微信/支付宝读屏（信任级）----------
        // 这两家的付款页文字就是结账结论，几乎没有商品列表，应放行。
        // 但它们的商品页（小程序里）同样要拦住。

        want("wx_paid", "微信付款成功页",
                d(WX, "支付成功 ¥36.50 收款方 瑞幸咖啡(万达店) 支付方式 零钱"), true);

        want("ali_paid", "支付宝付款成功页",
                d(ALI, "支付成功 付款金额 56.80 付款方式 余额宝 收款方 麦当劳"), true);

        want("ali_browse", "支付宝内购物小程序商品页",
                d(ALI, "商品详情 ¥9.9 加入购物车 立即购买 店铺 已售"), false);

        // ---------- E 组：银行/运营商（信任级，账单页应放行）----------

        want("icbc_bill", "工行账单页",
                d(ICBC, "信用卡账单 本期应还 1,280.00 元 最低还款 128.00 到期日 11-25"), true);

        // ---------- F 组：默认不读屏的 App ----------
        // 出厂表之外的包默认 OFF，避免误读一堆无关界面。
        want("unknown_app", "未收录的 App 默认不读屏",
                d("com.some.random.app", "支付成功 ¥12.00"), false);

        // ---------- G 组：用户手动覆盖 ----------
        // 用户说「拼多多别读屏」就必须真的不读，哪怕出现结账凭证。

        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(
                    PDD, "支付成功 实付款 ¥36.50 支付方式 支付宝", ScreenPolicy.OFF);
            want("user_off_pdd", "用户关闭拼多多读屏后结账页也不读", got, false);
        }

        // 反过来：用户把某个不认识的 App 设为信任
        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(
                    "com.some.random.app", "支付成功 ¥12.00 收款方 便利店", ScreenPolicy.TRUSTED);
            want("user_trust_unknown", "用户手动设为信任后放行", got, true);
        }

        // 用户把拼多多设为信任（想看所有页面）—— 仍要拦浏览页，
        // 因为浏览页识别是**正确性问题**，不是偏好问题。
        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(
                    PDD, "¥9.9 加入购物车 立即购买", ScreenPolicy.TRUSTED);
            want("trust_still_blocks_browse", "信任级别仍拦商品浏览页", got, false);
        }

        // ---------- H 组：边界 ----------

        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(PDD, "", -1);
            want("empty_text", "空文本不记账", got, false);
        }

        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(null, "支付成功 ¥1.00", -1);
            want("null_pkg", "空包名不记账", got, false);
        }

        // 订单号但没金额 → 商品详情页也会显示订单号提示，不算凭证
        {
            ScreenPolicy.Decision got = ScreenPolicy.decide(
                    PDD, "订单编号 2411152200188 查看详情", -1);
            want("orderid_no_amount", "只有订单号没金额不算凭证", got, false);
        }

        // ---------- I 组：出厂表完整性 ----------
        // 购物类必须是 CAUTIOUS，不能是 TRUSTED —— 这是本轮的根因修复。
        {
            String[] shopping = {PDD, TB, JD, "com.sankuai.meituan", "me.ele", "com.xingin.xhs"};
            boolean ok = true;
            StringBuilder why = new StringBuilder();
            for (String p : shopping) {
                int lv = ScreenPolicy.defaultLevel(p);
                if (lv != ScreenPolicy.CAUTIOUS) {
                    ok = false;
                    why.append(p).append('=').append(ScreenPolicy.levelName(lv)).append(' ');
                }
            }
            report("shopping_cautious", "购物类出厂级别必须是「需凭证」", ok, why.toString());
        }

        {
            String[] pay = {WX, ALI};
            boolean ok = true;
            StringBuilder why = new StringBuilder();
            for (String p : pay) {
                int lv = ScreenPolicy.defaultLevel(p);
                if (lv != ScreenPolicy.TRUSTED) {
                    ok = false;
                    why.append(p).append('=').append(ScreenPolicy.levelName(lv)).append(' ');
                }
            }
            report("pay_trusted", "微信/支付宝出厂级别必须是「信任」", ok, why.toString());
        }

        // 出厂表里的包必须有中文名，否则设置页会显示一串包名
        {
            boolean ok = true;
            StringBuilder why = new StringBuilder();
            for (String p : ScreenPolicy.knownPackages()) {
                if (ScreenPolicy.label(p).isEmpty()) {
                    ok = false;
                    why.append(p).append(' ');
                }
            }
            report("labels_present", "出厂表每个包都有中文名", ok, why.toString());
        }

        System.out.println();
        System.out.println("==== 读屏准入策略: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    static void report(String id, String desc, boolean ok, String why) {
        StringBuilder b = new StringBuilder(id);
        while (b.length() < 28) b.append(' ');
        if (ok) { pass++; System.out.println("  [PASS] " + b + desc); }
        else { fail++; System.out.println("  [FAIL] " + b + desc + "  ← " + why); }
    }
}
