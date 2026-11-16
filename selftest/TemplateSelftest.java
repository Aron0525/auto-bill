package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.OcrTemplates;
import com.aron.autobill.model.ParseResult;

import java.util.Arrays;
import java.util.List;

/**
 * 版式模板自检：微信支付 / 支付宝 / 美团 / 饿了么 / 淘宝。
 * 用例都按各 App 真实账单页的版式组织。
 */
public class TemplateSelftest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  -> " + detail); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        ok(name, c, "期望=" + want + " 实际=" + got);
    }

    public static void main(String[] args) {
        testDetect();
        testWechat();
        testAlipay();
        testMeituan();
        testEleme();
        testTaobao();
        testFallback();
        System.out.println();
        System.out.println("==== 版式模板自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    // ---------- 版式识别 ----------
    static void testDetect() {
        System.out.println("\n【版式识别】");
        eq("微信支付", "wechat", id(OcrTemplates.detect(Arrays.asList(
                "转账详情", "当前状态 已到账", "¥250.00", "转账说明 房租"))));
        eq("支付宝", "alipay", id(OcrTemplates.detect(Arrays.asList(
                "账单详情", "支付金额 36.50", "账单分类 餐饮美食", "交易号 2026..."))));
        eq("美团", "meituan", id(OcrTemplates.detect(Arrays.asList(
                "订单详情", "华莱士", "配送费 4.00", "商品小计 56.20", "实付 ¥48.20", "待评价"))));
        eq("饿了么", "eleme", id(OcrTemplates.detect(Arrays.asList(
                "订单详情", "订单编号 123", "配送费 3.00", "商品小计 30.00", "实付 33.00"))));
        eq("淘宝", "taobao", id(OcrTemplates.detect(Arrays.asList(
                "订单详情", "店铺 某某旗舰店", "宝贝 连衣裙", "实付款 ¥129.00", "确认收货"))));
        // 聊天里提到美团，不该被认成订单页
        ok("聊天提到美团不误判", OcrTemplates.detect(Arrays.asList(
                "张三：今天美团外卖好便宜", "李四：是啊")) == null, "被误判了");
    }

    static String id(OcrTemplates.Tpl t) { return t == null ? null : t.id; }

    // ---------- 微信支付 ----------
    static void testWechat() {
        System.out.println("\n【微信支付】");

        // 微信支付凭证：金额在上、收款方在下（merchantBelow = true）
        List<String> lines = Arrays.asList(
                "微信支付",
                "支付成功",
                "¥36.50",
                "商户全称 华莱士炸鸡汉堡店",
                "支付方式 零钱",
                "支付时间 2026-11-10 12:30:45",
                "交易单号 4200001234202611101234567890"
        );
        ParseResult r = new BillParser().parseOcr(lines, "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("金额 36.50", 3650L, r.amountFen);
        eq("商户取商户全称", "华莱士炸鸡汉堡店", r.merchant);
        eq("方向=支出", 1, r.type);
        eq("渠道=微信支付", "微信支付", r.channel);

        // 微信转账（收入）
        ParseResult r2 = new BillParser().parseOcr(Arrays.asList(
                "微信转账",
                "已收款",
                "¥250.00",
                "转账说明 房租",
                "转账时间 2026-11-10 09:00"
        ), "ocr");
        eq("转账金额", 25000L, r2.amountFen);
        eq("方向=收入", 2, r2.type);
    }

    // ---------- 支付宝 ----------
    static void testAlipay() {
        System.out.println("\n【支付宝】");

        ParseResult r = new BillParser().parseOcr(Arrays.asList(
                "账单详情",
                "支付金额",
                "36.50",
                "账单分类 餐饮美食",
                "收款方 华莱士",
                "支付方式 余额宝",
                "创建时间 2026-11-10 12:30"
        ), "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("金额 36.50", 3650L, r.amountFen);
        eq("商户取收款方", "华莱士", r.merchant);
        eq("渠道=支付宝", "支付宝", r.channel);

        // 余额宝收益（投资场景）
        ParseResult r2 = new BillParser().parseOcr(Arrays.asList(
                "账单详情",
                "收益到账",
                "支付金额 1.23",
                "账单分类 投资理财",
                "收款方 余额宝"
        ), "ocr");
        eq("余额宝收益金额", 123L, r2.amountFen);
    }

    // ---------- 美团 ----------
    static void testMeituan() {
        System.out.println("\n【美团外卖】");

        // 真实美团订单页：多行金额，「实付」才是本笔
        ParseResult r = new BillParser().parseOcr(Arrays.asList(
                "订单详情",
                "华莱士炸鸡汉堡（科技园店）",
                "香辣鸡腿堡 x1        12.90",
                "劲脆鸡腿堡 x2        25.80",
                "配送费               4.00",
                "打包费               2.00",
                "商品小计            56.20",
                "优惠                -8.00",
                "实付                48.20",
                "待评价"
        ), "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("取实付 48.20 不是小计", 4820L, r.amountFen);
        eq("商户取店名", "华莱士炸鸡汉堡（科技园店）", r.merchant);
        eq("方向=支出", 1, r.type);
    }

    // ---------- 饿了么 ----------
    static void testEleme() {
        System.out.println("\n【饿了么】");

        ParseResult r = new BillParser().parseOcr(Arrays.asList(
                "订单详情",
                "订单编号 8001234567890",
                "麦当劳（软件园店）",
                "巨无霸 x1        22.00",
                "配送费            3.00",
                "商品小计         22.00",
                "实付             25.00"
        ), "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("取实付 25.00", 2500L, r.amountFen);
        eq("商户取店名", "麦当劳（软件园店）", r.merchant);
    }

    // ---------- 淘宝 ----------
    static void testTaobao() {
        System.out.println("\n【淘宝】");

        ParseResult r = new BillParser().parseOcr(Arrays.asList(
                "订单详情",
                "店铺 优衣库官方旗舰店",
                "宝贝 男装圆领T恤",
                "商品总价 129.00",
                "运费 0.00",
                "实付款 129.00",
                "确认收货"
        ), "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("取实付款 129.00", 12900L, r.amountFen);
        eq("商户取店铺", "优衣库官方旗舰店", r.merchant);
    }

    // ---------- 模板不命中时退回通用规则 ----------
    static void testFallback() {
        System.out.println("\n【退回通用规则】");

        // 没有任何 App 特征的普通小票
        ParseResult r = new BillParser().parseOcr(Arrays.asList(
                "某某便利店",
                "矿泉水 x2        3.00",
                "面包 x1          5.50",
                "合计            8.50"
        ), "ocr");
        ok("通用规则仍能解析", r.ok, "ok=" + r.ok);
        eq("金额 8.50", 850L, r.amountFen);

        // 模板命中了但没取到金额 → 也要能退回
        ParseResult r2 = new BillParser().parseOcr(Arrays.asList(
                "订单详情", "待评价", "配送费", "商品小计"
        ), "ocr");
        ok("无金额时不记账", !r2.ok, "ok=" + r2.ok);
    }
}
