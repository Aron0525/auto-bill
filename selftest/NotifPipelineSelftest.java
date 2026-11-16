package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.NotifPolicy;
import com.aron.autobill.model.ParseResult;

/**
 * 端到端考卷：渠道策略 + 解析器串联。
 *
 * ChannelPolicySelftest 只验证策略本身（纯逻辑）；
 * 这里验证策略**真的作用到了解析结果**上 —— 两条链路的接缝才是 bug 高发区。
 *
 * 重点验证三件事：
 *   1. confCap 真的把置信度压到了自动入账线以下（否则低可信仍会污染账本）
 *   2. 群聊标题真的不会变成商户名
 *   3. 支付凭证不会被这些限制误伤（这是精度和覆盖的权衡点）
 */
public class NotifPipelineSelftest {

    static int pass = 0, fail = 0;
    static final String WX = "com.tencent.mm";
    static final String ALI = "com.eg.android.AlipayGphone";
    static final String CH_WX = "message_channel_new_id";
    static final String CH_ALI_TRADE = "mipush|com.eg.android.AlipayGphone|100060";

    /** 模拟 BillNotificationListener → BillProcessor 的完整链路 */
    static ParseResult run(String pkg, String ch, String title, String text,
                           boolean group, boolean ongoing, String conv) {
        NotifPolicy.Decision d = NotifPolicy.decide(pkg, ch, title, text, group, ongoing, conv);
        if (!d.shouldParse()) return null;   // SKIP / PLACEHOLDER 不落账

        BillParser bp = new BillParser();
        ParseResult r = bp.parseNotification(title, text, null, pkg,
                d.confCap, d.titleAsMerchant);

        // 复刻 BillProcessor 的标题清理
        if (r != null && r.ok && !d.titleAsMerchant && title != null && title.equals(r.merchant)) {
            r.merchant = "";
            r.confidence = Math.min(r.confidence, 45);
        }
        return r;
    }

    static void expect(String id, String desc, ParseResult r, long wantFen,
                       String[] wantMer, String[] forbidMer, int maxConf) {
        StringBuilder why = new StringBuilder();
        boolean ok = true;
        if (r == null || !r.ok) { ok = false; why.append("未解析 "); }
        else {
            if (wantFen > 0 && r.amountFen != wantFen) {
                ok = false;
                why.append(String.format("金额 %.2f≠%.2f ", r.amountFen / 100.0, wantFen / 100.0));
            }
            if (wantMer != null) {
                boolean hit = false;
                for (String w : wantMer) if (r.merchant != null && r.merchant.contains(w)) hit = true;
                if (!hit) { ok = false; why.append("商户「").append(r.merchant).append("」不含期望 "); }
            }
            if (forbidMer != null) {
                for (String w : forbidMer) {
                    if (r.merchant != null && r.merchant.contains(w)) {
                        ok = false;
                        why.append("商户「").append(r.merchant).append("」含禁用词 ").append(w).append(' ');
                    }
                }
            }
            if (r.confidence > maxConf) {
                ok = false;
                why.append("置信度 ").append(r.confidence).append('>').append(maxConf).append(' ');
            }
        }
        report(id, desc, ok, why.toString());
    }

    static void expectNull(String id, String desc, ParseResult r) {
        report(id, desc, r == null || !r.ok, r == null || !r.ok ? "" : "不该落账却落账了");
    }

    static void report(String id, String desc, boolean ok, String why) {
        StringBuilder b = new StringBuilder(id);
        while (b.length() < 26) b.append(' ');
        if (ok) { pass++; System.out.println("  [PASS] " + b + desc); }
        else { fail++; System.out.println("  [FAIL] " + b + desc + "  ← " + why); }
    }

    public static void main(String[] args) {
        System.out.println("=== 通知链路端到端考卷（策略→解析器）===");
        System.out.println();

        // ---- 1. 微信支付凭证：必须准，且置信度要高（这是主用例）----
        expect("wx_pay_ok", "微信支付凭证完整记账",
                run(WX, CH_WX, "微信支付",
                        "支付成功 ¥128.50 收款方：星巴克咖啡(万象城店) 支付方式：零钱",
                        false, false, null),
                12850L, new String[]{"星巴克"}, new String[]{"微信"}, 100);

        // ---- 2. 支付宝交易渠道 ----
        expect("ali_pay_ok", "支付宝交易渠道记账",
                run(ALI, CH_ALI_TRADE, "支付宝",
                        "付款成功 -56.80元 付款给 麦当劳(软件园店) 余额宝",
                        false, false, null),
                5680L, new String[]{"麦当劳"}, new String[]{"支付宝"}, 100);

        // ---- 3. 营销通知：必须一条都不落账 ----
        expectNull("ali_activity", "支付宝活动通知不落账",
                run(ALI, "mipush|com.eg.android.AlipayGphone|100064", "活动通知",
                        "双11狂欢，满200减30优惠券已发放，点击领取", false, false, null));

        expectNull("ali_subscribe", "支付宝订阅消息不落账",
                run(ALI, "mipush|com.eg.android.AlipayGphone|100062", "订阅消息",
                        "您订阅的店铺上新了，12.00元起", false, false, null));

        // ---- 4. 群聊：最容易记错的一类 ----
        // 「工作群」永远不能出现在商户名里。金额可以记（用户确实付了），
        // 但置信度必须低到进待确认队列。
        expect("wx_group_transfer", "群聊转账不当商户名",
                run(WX, CH_WX, "工作群", "张三: 已转账 200.00元",
                        false, false, "工作群"),
                20000L, null, new String[]{"工作群", "张三"}, 50);

        // ---- 5. 通知组摘要 ----
        expectNull("wx_group_summary", "通知组摘要不落账",
                run(WX, CH_WX, "微信", "3条新消息", true, false, null));

        // ---- 6. 转账卡片：不落账（走 PLACEHOLDER 提示）----
        expectNull("wx_transfer_card", "转账卡片不落账",
                run(WX, CH_WX, "张三", "[转账]", false, false, "张三"));

        // ---- 7. 微信支付不能被聊天规则误伤 ----
        // 关键：支付凭证的 title 是「微信支付」，它**没有** conversationTitle。
        // 如果这里被当成聊天压到 50 以下，用户会觉得"支付反而不记了"。
        {
            ParseResult r = run(WX, CH_WX, "微信支付",
                    "支付成功 ¥36.50 收款方：瑞幸咖啡(万达店)", false, false, null);
            boolean ok = r != null && r.ok && r.confidence >= 50 && r.amountFen == 3650L;
            report("wx_pay_not_chat", "支付凭证不被聊天规则误伤",
                    ok, r == null ? "未解析" : ("conf=" + r.confidence + " amt=" + r.amountFen));
        }

        // ---- 8. 物流通知：有金额但压到待确认 ----
        {
            ParseResult r = run(ALI, "mipush|com.eg.android.AlipayGphone|100061", "订单已发货",
                    "您的订单已发货，运费12.00元", false, false, null);
            boolean ok = (r == null || !r.ok) || r.confidence <= 45;
            report("ali_logistics", "物流通知不直接入账", ok,
                    r == null ? "" : "conf=" + r.confidence);
        }

        // ---- 9. 未知渠道不能静默漏记 ----
        // 支付宝加新渠道时，若规则一刀切返回 SKIP，用户会突然"不记账了"却查不出原因。
        {
            ParseResult r = run(ALI, "mipush|com.eg.android.AlipayGphone|100099", "支付宝",
                    "支付成功 ¥12.00 付款给 全家便利店", false, false, null);
            boolean ok = r != null && r.ok && r.amountFen == 1200L;
            report("unknown_channel", "未知渠道仍能记账", ok,
                    r == null ? "未解析（会静默漏记）" : "amt=" + r.amountFen);
        }

        // ---- 10. 抽奖营销：金额是假的，绝不能记 ----
        // 真机验证发现的漏洞：「恭喜抽中88元红包，点击查看」被记成 88 元支出，
        // conf=50 刚好卡在自动入账线上 —— 凭空多出一笔钱，账本直接失真。
        expectNull("lottery_mkt", "抽奖营销不落账",
                run("com.eg.android.AlipayGphone", "mipush|com.eg.android.AlipayGphone|100060",
                        "活动通知", "恭喜抽中88元红包，点击查看", false, false, null));

        // ---- 11. 优惠券发放：同理 ----
        expectNull("coupon_mkt", "优惠券发放不落账",
                run("com.eg.android.AlipayGphone", "mipush|com.eg.android.AlipayGphone|100060",
                        "活动通知", "满200减30优惠券已发放，点击领取", false, false, null));

        // ---- 12. 抽奖词不能误伤真实交易 ----
        // 「赠」已在拦截词里，但真实账单可能含类似字眼。确认正常凭证仍能记。
        expect("real_pay_after_mkt", "营销规则不误伤真实支付",
                run(WX, CH_WX, "微信支付", "支付成功 ¥45.00 收款方：肯德基",
                        false, false, null),
                4500L, new String[]{"肯德基"}, null, 100);

        System.out.println();
        System.out.println("==== 通知链路端到端: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }
}
