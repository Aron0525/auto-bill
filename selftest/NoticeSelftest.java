package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.ParseResult;

/** 通知栏文本切割判定验证（用户实测的基金申购通知） */
public class NoticeSelftest {

    static int pass = 0, fail = 0;

    static void expect(String label, String raw, String pkg,
                       long fen, int type, String merchantLike, String catLike) {
        BillParser p = new BillParser();
        ParseResult r = p.parse(raw, pkg);
        String cat = Category.classifyFor(r, raw);
        boolean ok = r.ok && r.amountFen == fen && r.type == type
                && (merchantLike == null || (r.merchant != null && r.merchant.contains(merchantLike)))
                && cat.equals(catLike);
        System.out.println((ok ? "PASS  " : "FAIL  ") + label
                + "  => 金额=" + (r.ok ? r.amountFen / 100.0 : "-")
                + " 方向=" + (r.ok ? (r.type == 2 ? "收入" : "支出") : "-")
                + " 商户=" + r.merchant
                + " 场景=" + r.scene
                + " 渠道=" + r.channel
                + " 条目=" + r.note
                + " 分类=" + cat
                + " conf=" + r.confidence);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        // 用户截图那条：支付宝基金申购确认
        expect("基金申购-支付宝",
                "支付宝 基金申购确认成功通知 12:37 你于2026-11-06买入易方达中债3-5年期国债基金已确认成功，确认金额99.92元，手续费0.10元",
                "com.eg.android.AlipayGphone", 9992, 1, "基金", "投资/理财");

        // 基金赎回：方向应为收入
        expect("基金赎回-收入",
                "支付宝 基金赎回确认成功通知 你于2026-11-01卖出易方达中债3-5年期国债基金已确认成功，确认金额150.00元",
                "com.eg.android.AlipayGphone", 15000, 2, "基金", "投资/理财");

        // 普通消费：条目不应乱捞别处的词
        expect("普通消费",
                "微信支付 你已成功支付￥25.00 收款方：华莱士",
                "com.tencent.mm", 2500, 1, "华莱士", "餐饮");

        // 银行+尾号 → 渠道识别
        expect("信用卡还款-银行尾号",
                "支付宝 信用卡还款成功 你已向招商银行信用卡(尾号1234)还款520.00元",
                "com.eg.android.AlipayGphone", 52000, 1, null, "转账");

        // 余额宝扣款消费：渠道=余额宝，分类仍是餐饮
        expect("余额宝-餐饮消费",
                "支付宝 餐厅扫码消费 从余额宝扣款成功，消费金额36.50元",
                "com.eg.android.AlipayGphone", 3650, 1, null, "餐饮");

        System.out.println("\n======== 通知切割自检：通过 " + pass + " / 失败 " + fail + " ========");
        if (fail > 0) System.exit(1);
    }
}
