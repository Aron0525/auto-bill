package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * OCR 优化自检：验证小数点修复 + 按行归属商户。
 * 全部用例都取自 ML Kit 实际会给出的「错误输出」，不是理想文本。
 */
public class OcrSelftest {

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
        testFixDecimal();
        testOcrLineMerchant();
        testOcrPriority();
        testNoFalsePositive();
        System.out.println();
        System.out.println("==== OCR 自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    // ========== 一、小数点修复 ==========
    static void testFixDecimal() {
        System.out.println("\n【一】小数点修复（ML Kit 实际错误输出）");

        // 1. 小数点被完全吞掉（最常见）
        eq("9992元 → 99.92",
                "实付 99.92 元",
                BillParser.fixOcrAmount("实付 9992 元"));

        // 2. 小数点被认成逗号
        eq("99,92 → 99.92",
                "合计 99.92",
                BillParser.fixOcrAmount("合计 99,92"));

        // 3. 小数点前后被空格拆开
        eq("99 . 92 → 99.92",
                "支付 99.92 元",
                BillParser.fixOcrAmount("支付 99 . 92 元"));

        // 4. 整数金额被拆成两段
        eq("「128 00 元」→ 128.00",
                "消费 128.00 元",
                BillParser.fixOcrAmount("消费 128 00 元"));

        // 5. 带 ¥ 的场景
        eq("¥9992 → ¥99.92",
                "¥99.92",
                BillParser.fixOcrAmount("¥9992"));

        // 6. 千分位不能被误改：1,280.00 的逗号后是 3 位，不命中
        eq("1,280.00 保持原样",
                "总计 1,280.00 元",
                BillParser.fixOcrAmount("总计 1,280.00 元"));

        // 7. 已有小数的不动
        eq("36.50 保持原样",
                "实付 36.50 元",
                BillParser.fixOcrAmount("实付 36.50 元"));

        // 8. 订单号绝不能补点（无金额前缀词）
        eq("订单号 202611061234 不动",
                "订单号 202611061234",
                BillParser.fixOcrAmount("订单号 202611061234"));
    }

    // ========== 二、按行归属商户 ==========
    static void testOcrLineMerchant() {
        System.out.println("\n【二】按行归属：金额与商户同行");

        BillParser bp = new BillParser();

        // 典型外卖订单详情页：多行都有数字，只有「实付」那行是真正的本笔金额
        List<String> lines = Arrays.asList(
                "华莱士",
                "香辣鸡腿堡 x1        12.90",
                "配送费               3.00",
                "商品小计            15.90",
                "实付                99.92"
        );
        ParseResult r = bp.parseOcr(lines, "ocr");
        ok("能解析出结果", r.ok, "r.ok=" + r.ok);
        eq("金额取实付行 99.92 而不是小计", 9992L, r.amountFen);
        eq("商户取店名「华莱士」", "华莱士", r.merchant);
        eq("方向=支出", 1, r.type);
    }

    static void testOcrPriority() {
        System.out.println("\n【三】金额行优先级");

        BillParser bp = new BillParser();

        // 「小计」在前、「实付」在后：必须优先取实付
        ParseResult r1 = bp.parseOcr(Arrays.asList(
                "商品小计            15.90",
                "实付                99.92",
                "星巴克咖啡"
        ), "ocr");
        eq("小计在前也取实付", 9992L, r1.amountFen);

        // 小数点被吞 + 按行：9992 要修成 99.92
        ParseResult r2 = bp.parseOcr(Arrays.asList(
                "麦当劳",
                "实付 9992 元"
        ), "ocr");
        eq("行内小数点修复生效", 9992L, r2.amountFen);
        eq("同行商户", "麦当劳", r2.merchant);

        // 收入方向
        ParseResult r3 = bp.parseOcr(Arrays.asList(
                "张三",
                "收款 250.00 元"
        ), "ocr");
        eq("收入金额", 25000L, r3.amountFen);
        eq("收入方向=2", 2, r3.type);
    }

    static void testNoFalsePositive() {
        System.out.println("\n【四】不得误记");

        BillParser bp = new BillParser();

        // 纯订单号页，没有金额：不该产出账单
        ParseResult r1 = bp.parseOcr(Arrays.asList(
                "订单详情",
                "订单号 2026110612345678",
                "下单时间 2026-11-10 12:30"
        ), "ocr");
        ok("无金额页不记账", !r1.ok, "r.ok=" + r1.ok + " fen=" + r1.amountFen);

        // 空输入
        ParseResult r2 = bp.parseOcr(new ArrayList<>(), "ocr");
        ok("空列表不记账", !r2.ok, "r.ok=" + r2.ok);
    }
}
