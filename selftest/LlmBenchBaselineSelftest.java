package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 与 LLM 实测同一套考卷的规则基线。
 *
 * 考卷与 llm_bench/cases.py 完全一致，这样「规则 vs 小模型」才是同一场考试，
 * 数字可比。跑完这个，才谈得上「小模型值不值得上」。
 */
public class LlmBenchBaselineSelftest {

    static int pass = 0, fail = 0;

    static void check(String id, String desc, ParseResult r,
                      Long wantAmt, String[] wantMerKws, int wantType) {
        List<String> problems = new ArrayList<>();

        if (wantAmt != null) {
            boolean ok = r.ok && r.amountFen == wantAmt.longValue();
            if (!ok) problems.add("金额 期望=" + wantAmt / 100.0 + " 实际="
                    + (r.amountFen / 100.0));
        }
        if (wantMerKws != null) {
            String mer = r.merchant == null ? "" : r.merchant;
            boolean ok = false;
            for (String k : wantMerKws) if (mer.contains(k)) { ok = true; break; }
            if (!ok) problems.add("商户 期望⊇" + Arrays.toString(wantMerKws) + " 实际=" + mer);
        }
        if (wantType != 0) {
            if (r.type != wantType) {
                problems.add("方向 期望=" + (wantType == 2 ? "收入" : "支出")
                        + " 实际=" + (r.type == 2 ? "收入" : "支出"));
            }
        }

        if (problems.isEmpty()) {
            pass++;
            System.out.println("  [PASS] " + id + "  " + desc);
        } else {
            fail++;
            System.out.println("  [FAIL] " + id + "  " + desc);
            for (String p : problems) System.out.println("         · " + p);
        }
    }

    public static void main(String[] args) {
        BillParser bp = new BillParser();

        System.out.println("=== 规则基线：与 LLM 同一套考卷 ===");

        // 案例1：真机#66 整屏通知栏
        String c1 = "17:14 设置 11月10日 周二 手机可用空间不足3G 展开 Shell 收起 "
                + "支付宝 基金申购确认成功通知 12:37 你于2026-11-06买入易方达中债3-5年期国债基金"
                + "已确认成功，确认金额99.92元，手续费0.10元 展开 Android 系统 展开 "
                + "已连接到 USB 调试 点按即可关闭 USB 调试 正在通过 USB 充电 热点已启用 "
                + "历史通知 清除所有通知";
        check("real_systemui", "真机#66 整屏通知栏", bp.parse(c1, "com.android.systemui"),
                9992L, new String[]{"易方达", "基金", "支付宝"}, 1);

        // 案例2：美团 OCR 行
        List<String> c2 = Arrays.asList("<", "订单详情", "华莱士炸鸡汉堡(科技园店)",
                "香辣鸡腿堡 x1", "12.90", "劲脆鸡腿堡 x2", "25.80", "薯条(大)x1", "8.50",
                "配送费", "4.00", "打包费", "2.00", "商品小计", "47.20", "优惠", "-8.00",
                "实付", "39.20", "待评价");
        check("meituan", "美团订单页", bp.parseOcr(new ArrayList<>(c2), "ocr"),
                3920L, new String[]{"华莱士"}, 1);

        // 案例3：微信（繁体商戶）
        List<String> c3 = Arrays.asList("微信支付", "支付成功", "¥128.50",
                "商戶全称", "星巴克咖啡(万象城店)", "支付方式", "零钱",
                "支付时间", "2026-11-10 15:22:08", "交易单号", "420000123420261110987654321 0");
        check("wechat", "微信支付凭证", bp.parseOcr(new ArrayList<>(c3), "ocr"),
                12850L, new String[]{"星巴克"}, 1);

        // 案例4：支付宝（异体字）
        List<String> c4 = Arrays.asList("账单详情", "支付金颉", "56.80", "账单分类",
                "餐饮美食", "收款方", "麦当劳(软件园店)", "支付方式", "余額宝",
                "创建时间", "2026-11-10 18:05:33");
        check("alipay", "支付宝账单详情", bp.parseOcr(new ArrayList<>(c4), "ocr"),
                5680L, new String[]{"麦当劳"}, 1);

        // 案例5：退款
        String c5 = "微信支付 退款到账通知 退款金额 ¥36.50 退款已原路退回至你的零钱 "
                + "商户名称 京东商城 退款时间 2026-11-11 09:14:22";
        check("refund", "退款到账", bp.parse(c5, "com.tencent.mm"),
                3650L, new String[]{"京东"}, 2);

        // 案例6：工资
        String c6 = "【招商银行】您账户1234于11月10日15:30入账人民币12000.00元，"
                + "摘要：工资，余额45231.88元。";
        check("salary", "工资到账", bp.parse(c6, "com.android.mms"),
                1200000L, new String[]{"工资", "招商"}, 2);

        // 案例7：红包
        String c7 = "微信支付 你已成功发送红包 金额 ¥88.00 收款人 张小明 "
                + "支付方式 零钱 2026-11-11 20:15:00";
        check("redpacket", "微信红包发出", bp.parse(c7, "com.tencent.mm"),
                8800L, new String[]{"红包", "张小明"}, 1);

        // 案例8：多笔（无唯一答案，只判方向）
        String c8 = "【工商银行】您尾号5678卡11月11日共2笔交易："
                + "10:23消费支出58.00元，余额3210.50元；14:07消费支出128.00元，余额3082.50元。";
        check("multi", "短信含两笔交易（只判方向）", bp.parse(c8, "com.android.mms"),
                null, null, 1);

        System.out.println();
        System.out.println("==== 规则基线: " + pass + " 通过 / " + fail + " 失败 ====");
    }
}
