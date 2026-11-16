package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.OcrLine;
import com.aron.autobill.core.OcrTemplates;
import com.aron.autobill.model.ParseResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 真机原始顺序端到端自检。
 *
 * 与 RealImageSelftest 的区别：这里用的是真机 logcat 里导出的
 * ML Kit 原始输出 —— 顺序是「标签一坨、数字一坨」（label 列和金额列
 * 被切成不同 TextBlock），还带繁体/异体字（商戶全称、支付金颉）。
 * 链路：原始顺序 + bbox → sortVisual → 文本 → parseOcr → 账单结果。
 *
 * 这三组数据就是 2026-11-11 真机验证时的原始输出，修复前分别
 * 错成 金额4.00 / 商户「商戶全称」/ 商户「支付方式」。
 */
public class VisualSortSelftest {

    static int pass = 0, fail = 0;

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        if (c) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  期望=" + want + " 实际=" + got); }
    }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  -> " + detail); }
    }

    public static void main(String[] args) {
        testSortBasic();
        testMeituanDevice();
        testWechatDevice();
        testAlipayDevice();
        testNorm();
        System.out.println();
        System.out.println("==== 真机顺序端到端: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    // ---------- 排序基础 ----------
    static void testSortBasic() {
        System.out.println("\n【视觉排序】");
        // label 列一个 block、金额列一个 block（ML Kit 常见切法）
        List<OcrLine> lines = new ArrayList<>(Arrays.asList(
                new OcrLine("实付", 60, 711, 140, 751),
                new OcrLine("39.20", 300, 713, 380, 749),
                new OcrLine("配送费", 60, 491, 140, 531),
                new OcrLine("4.00", 300, 493, 380, 529)));
        OcrLine.sortVisual(lines);
        // 视觉顺序：y 小的在上 —— 配送费(491) 排在 实付(711) 前面
        eq("上行标签", "配送费", lines.get(0).text);
        eq("上行金额", "4.00", lines.get(1).text);
        eq("同行标签在前", "实付", lines.get(2).text);
        eq("同行金额在后", "39.20", lines.get(3).text);

        // joinText 与视觉顺序一致
        eq("joinText", "配送费\n4.00\n实付\n39.20", OcrLine.joinText(lines));
    }

    // ---------- 美团（真机原始输出） ----------
    static void testMeituanDevice() {
        System.out.println("\n【美团 · 真机原始顺序】");
        // 文本与 logcat 完全一致；bbox 按截图视觉位置给出
        // 视觉行：[<,订单详情]@36 店名@150 三行商品@265/327/389
        //         六行费用@491..766（标签 x60、金额 x300 右侧）
        List<String> rawOrder = Arrays.asList(
                "<", "华莱士炸鸡汉堡(科技园店)",
                "香辣鸡腿堡 x1", "劲脆鸡腿堡 x2",
                "薯条(大)x1", "配送费", "打包费", "商品小计", "优惠", "实付", "待评价",
                "4.00", "2.00", "订单详情", "47.20", "-8.00", "39.20",
                "12.90", "25.80", "8.50");
        int[] ys = {36, 150, 265, 327, 389, 491, 546, 601, 656, 711, 766,
                491, 546, 36, 601, 656, 711, 265, 327, 389};
        int[] xs = {40, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60,
                300, 300, 360, 300, 300, 300, 740, 740, 740};

        List<String> sorted = sortAndExtract(rawOrder, ys, xs);
        // 视觉顺序断言（关键几行的位置）
        eq("第0行是<", "<", sorted.get(0));
        eq("第1行是订单详情", "订单详情", sorted.get(1));
        eq("店名第2行", "华莱士炸鸡汉堡(科技园店)", sorted.get(2));
        int iShipei = sorted.indexOf("实付");
        ok("实付后面紧跟金额", iShipei >= 0 && iShipei + 1 < sorted.size()
                && "39.20".equals(sorted.get(iShipei + 1)),
                "实付位置=" + iShipei + " 后一行=" + (iShipei + 1 < sorted.size() ? sorted.get(iShipei + 1) : "?"));

        ParseResult r = new BillParser().parseOcr(sorted, "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("金额=39.20（不是4.00/47.20）", 3920L, r.amountFen);
        eq("商户=店名", "华莱士炸鸡汉堡(科技园店)", r.merchant);
        eq("方向=支出", 1, r.type);
        eq("模板=美团", "美团", OcrTemplates.detect(sorted).name);
    }

    // ---------- 微信支付（真机原始输出，含繁体「商戶」） ----------
    static void testWechatDevice() {
        System.out.println("\n【微信 · 真机原始顺序 + 异体字】");
        List<String> rawOrder = Arrays.asList(
                "微信支付", "支付成功", "¥128.50",
                "商戶全称", "支付方式", "支付时间", "交易单号",
                "星巴克咖啡(万象城店)", "零钱", "2026-11-10 15:22:08",
                "420000123420261110987654321 0");
        int[] ys = {90, 180, 260, 400, 470, 540, 610, 400, 470, 540, 610};
        int[] xs = {60, 60, 60, 60, 60, 60, 60, 260, 260, 260, 260};

        List<String> sorted = sortAndExtract(rawOrder, ys, xs);
        int iLabel = sorted.indexOf("商戶全称");
        ok("商戶全称后面紧跟店名", iLabel >= 0 && iLabel + 1 < sorted.size()
                && sorted.get(iLabel + 1).startsWith("星巴克"),
                "位置=" + iLabel);

        ParseResult r = new BillParser().parseOcr(sorted, "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("金额=128.50", 12850L, r.amountFen);
        ok("商户=星巴克（不是「商戶全称」标签）",
                r.merchant != null && r.merchant.startsWith("星巴克"),
                "实际=" + r.merchant);
        eq("渠道=微信支付", "微信支付", r.channel);
        eq("方向=支出", 1, r.type);
    }

    // ---------- 支付宝（真机原始输出，含异体「金颉」「餘額」） ----------
    static void testAlipayDevice() {
        System.out.println("\n【支付宝 · 真机原始顺序 + 异体字 + 换行金额】");
        List<String> rawOrder = Arrays.asList(
                "账单详情", "支付金颉", "56.80",
                "账单分类", "收款方", "支付方式", "创建时间",
                "餐饮美食", "麦当劳(软件园店)", "余額宝", "2026-11-10 18:05:33");
        int[] ys = {90, 200, 260, 400, 470, 540, 610, 400, 470, 540, 610};
        int[] xs = {60, 60, 60, 60, 60, 60, 60, 260, 260, 260, 260};

        List<String> sorted = sortAndExtract(rawOrder, ys, xs);
        // 原始行保留 ML Kit 的异体字「支付金颉」，归一由 BillParser.parseOcr 内部完成
        int iLabel = sorted.indexOf("支付金颉");
        ok("行定位：支付金颉 在排序后列表里", iLabel >= 0, "实际=" + sorted);
        ok("支付金颉下面紧跟金额", iLabel >= 0 && iLabel + 1 < sorted.size()
                && "56.80".equals(sorted.get(iLabel + 1)), "错位");

        ParseResult r = new BillParser().parseOcr(sorted, "ocr");
        ok("能解析", r.ok, "ok=" + r.ok);
        eq("金额=56.80", 5680L, r.amountFen);
        ok("商户=麦当劳（不是「支付方式」）",
                r.merchant != null && r.merchant.startsWith("麦当劳"),
                "实际=" + r.merchant);
        eq("渠道=支付宝", "支付宝", r.channel);
        eq("方向=支出", 1, r.type);
    }

    // ---------- 异体字归一 ----------
    static void testNorm() {
        System.out.println("\n【异体字归一】");
        eq("支付金颉→支付金额", "支付金额", OcrTemplates.norm("支付金颉"));
        eq("商戶全称→商户全称", "商户全称", OcrTemplates.norm("商戶全称"));
        eq("余額宝→余额宝", "余额宝", OcrTemplates.norm("余額宝"));
        eq("账单金额→原样", "账单金额", OcrTemplates.norm("账单金额"));
        eq("幂等", "商户全称", OcrTemplates.norm(OcrTemplates.norm("商戶全称")));
        eq("null 安全", null, OcrTemplates.norm(null));
    }

    static List<String> sortAndExtract(List<String> texts, int[] ys, int[] xs) {
        List<OcrLine> rows = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            rows.add(new OcrLine(texts.get(i), xs[i], ys[i], xs[i] + 80, ys[i] + 40));
        }
        OcrLine.sortVisual(rows);
        List<String> out = new ArrayList<>();
        for (OcrLine l : rows) out.add(l.text);
        return out;   // 保留 ML Kit 原文（含异体字），归一在 parseOcr 内部做
    }
}
