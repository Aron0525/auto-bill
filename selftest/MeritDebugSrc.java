package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;

/**
 * 诊断：三条商户规则失败案例的中间态。
 * 打印 maskNonAmount 之后的文本，看标签是否还在。
 */
public class MeritDebugSrc {
    public static void main(String[] args) throws Exception {
        String[] cases = {
                "微信支付 退款到账通知 退款金额 ¥36.50 退款已原路退回至你的零钱 商户名称 京东商城 退款时间 2026-11-11 09:14:22",
                "【招商银行】您账户1234于11月10日15:30入账人民币12000.00元，摘要：工资，余额45231.88元。",
                "微信支付 你已成功发送红包 金额 ¥88.00 收款人 张小明 支付方式 零钱 2026-11-11 20:15:00",
        };
        java.lang.reflect.Method norm = BillParser.class.getDeclaredMethod("normalize", String.class);
        norm.setAccessible(true);
        BillParser bp = new BillParser();
        for (String c : cases) {
            System.out.println("\n===== 原始 =====");
            System.out.println(c);
            String n = (String) norm.invoke(null, c);
            System.out.println("--- normalize 后 ---");
            System.out.println(n);
            ParseResult r = bp.parse(c, "com.tencent.mm");
            System.out.println("--- 结果 ---");
            System.out.println("ok=" + r.ok + " 金额=" + (r.amountFen / 100.0)
                    + " 商户=" + r.merchant + " 方向=" + r.type + " 置信=" + r.confidence);
        }
    }
}
