package com.aron.autobill.selftest;
import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;
import java.util.Arrays;
import java.util.List;

/** 用真实截图的 OCR 行序列（含 OCR 噪声）端到端验证 */
public class RealImageSelftest {
    static int pass=0, fail=0;
    static void eq(String n, Object w, Object g){
        boolean c = w==null? g==null : w.equals(g);
        if(c){pass++;System.out.println("  [PASS] "+n);}
        else{fail++;System.out.println("  [FAIL] "+n+"  期望="+w+" 实际="+g);}
    }
    public static void main(String[] a){
        // t_meituan.png 的 OCR 行（模拟 ML Kit 输出）
        List<String> meituan = Arrays.asList(
            "<", "订单详情",
            "华莱士炸鸡汉堡（科技园店）",
            "香辣鸡腿堡 x1", "12.90",
            "劲脆鸡腿堡 x2", "25.80",
            "薯条（大） x1", "8.50",
            "配送费", "4.00", "打包费", "2.00", "商品小计", "47.20",
            "优惠", "-8.00", "实付", "39.20", "待评价");
        ParseResult r1 = new BillParser().parseOcr(meituan, "ocr");
        eq("美团 能解析", true, r1.ok);
        eq("美团 金额=39.20", 3920L, r1.amountFen);
        eq("美团 商户", "华莱士炸鸡汉堡（科技园店）", r1.merchant);

        // t_wechat.png
        List<String> wechat = Arrays.asList(
            "微信支付", "支付成功", "¥128.50",
            "商户全称", "星巴克咖啡（万象城店）",
            "支付方式", "零钱",
            "支付时间", "2026-11-10 15:22:08",
            "交易单号", "4200001234202611109876543210");
        ParseResult r2 = new BillParser().parseOcr(wechat, "ocr");
        eq("微信 能解析", true, r2.ok);
        eq("微信 金额=128.50", 12850L, r2.amountFen);
        eq("微信 商户", "星巴克咖啡（万象城店）", r2.merchant);
        eq("微信 渠道", "微信支付", r2.channel);

        // t_alipay.png —「支付金额」换行写「56.80」
        List<String> alipay = Arrays.asList(
            "账单详情", "支付金额", "56.80",
            "账单分类", "餐饮美食",
            "收款方", "麦当劳（软件园店）",
            "支付方式", "余额宝",
            "创建时间", "2026-11-10 18:05:33");
        ParseResult r3 = new BillParser().parseOcr(alipay, "ocr");
        eq("支付宝 能解析", true, r3.ok);
        eq("支付宝 金额=56.80", 5680L, r3.amountFen);
        eq("支付宝 商户", "麦当劳（软件园店）", r3.merchant);
        eq("支付宝 渠道", "支付宝", r3.channel);

        System.out.println("\n==== 真实版式端到端: "+pass+" 通过 / "+fail+" 失败 ====");
        if(fail>0) System.exit(1);
    }
}