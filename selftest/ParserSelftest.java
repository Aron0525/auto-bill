import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;

/**
 * 解析器自检 —— 对应《记账信息处理标准 v1》的边界案例清单
 * 通过标准：all PASS
 */
public class ParserSelftest {

    static int pass = 0, fail = 0;

    /** expFen=null 表示期望「不落账」；expType/expMerchant=null 表示不校验 */
    static void check(String raw, String pkg, Long expFen, Integer expType, String expMerchant) {
        BillParser p = new BillParser();
        ParseResult r = p.parse(raw, pkg);

        boolean amtOk = expFen == null ? !r.ok : (r.ok && r.amountFen == expFen);
        boolean typOk = expType == null || (r.ok && r.type == expType);
        boolean merOk = expMerchant == null || (r.ok && r.merchant != null && r.merchant.contains(expMerchant));

        if (amtOk && typOk && merOk) {
            pass++;
            System.out.println("PASS  " + raw + "  =>  "
                    + (r.ok ? "¥" + (r.amountFen / 100.0) + " type=" + r.type + " " + r.merchant
                            + " conf=" + r.confidence : "REJECT"));
        } else {
            fail++;
            System.out.println("FAIL  " + raw + "  =>  ok=" + r.ok
                    + " fen=" + (r.ok ? r.amountFen : "-")
                    + " type=" + (r.ok ? r.type : "-")
                    + " merchant=" + (r.ok ? r.merchant : "-")
                    + "  [期望 fen=" + expFen + " type=" + expType + " merchant=" + expMerchant + "]");
        }
    }

    public static void main(String[] args) {
        // ===== A. 支付基础（支出）=====
        check("你已成功支付 ¥36.00 收款方：华莱士", "com.tencent.mm", 3600L, 1, "华莱士");
        check("您已成功付款18.99元", "com.sankuai.meituan", 1899L, 1, null);
        check("消费36.00元", "com.tencent.mm", 3600L, 1, null);
        check("扣款 12.5元", "com.tencent.mm", 1250L, 1, null);
        check("京东下单成功 支付 199元", "com.jingdong.app.mall", 19900L, 1, "京东");
        check("拼多多 支付成功 9.9元", "com.xunmeng.pinduoduo", 990L, 1, "拼多多");
        check("滴滴出行 行程结束 支付 25元", "com.sdu.didi.psnger", 2500L, 1, "滴滴");
        check("话费充值成功 50元", "com.tencent.mm", 5000L, 1, null);

        // ===== B. 通知栏形态（真实抓到的文案）=====
        check("您已成功付款18.99元 您的美团订单已支付成功，点击查看详情>",
                "com.android.systemui", 1899L, 1, "美团");
        check("美团 您已成功付款18.99元", "com.sankuai.meituan", 1899L, 1, "美团");

        // ===== C. 收入 =====
        check("微信红包 领取 20.00元", "com.tencent.mm", 2000L, 2, null);
        check("红包到账 20.00元", "com.tencent.mm", 2000L, 2, null);
        check("对方已收款 88.00元", "com.tencent.mm", 8800L, 2, null);
        check("退款 到账 36元", "com.tencent.mm", 3600L, 2, null);
        check("工资 到账 8000元", "com.android.mms", 800000L, 2, null);
        check("群收款到账 30元", "com.tencent.mm", 3000L, 2, null);

        // ===== D. 转账 / 红包（用户重点场景）=====
        check("你已向 张三 转账 100元", "com.tencent.mm", 10000L, 1, "张三");
        check("张三 转账给你 50元", "com.tencent.mm", 5000L, 2, "张三");
        check("转账给 李四 ¥88.00", "com.tencent.mm", 8800L, 1, "李四");
        check("发出了红包 8.88元", "com.tencent.mm", 888L, 1, null);
        check("发起了群收款 30元", "com.tencent.mm", 3000L, null, null);

        // ===== E. 误判拦截（标准③ 否定语境 / ② 噪音）=====
        check("运费4.88元由商家承担并退回微信钱包", "com.android.mms", null, null, null);
        check("订单已取消 支付18.99元", "com.tencent.mm", null, null, null);
        check("支付失败 请重试", "com.tencent.mm", null, null, null);
        check("验证码 708527，请勿泄露", "com.android.mms", null, null, null);
        check("您的验证码是 928328", "com.android.mms", null, null, null);
        check("恭喜您！22 元奖励已到账！ 点击领取，可提现至微信！", "com.sankuai.meituan", null, null, null);
        check("恭喜您！22 元奖励已到账！ 点击领取，可提现至微信！", "com.android.systemui", null, null, null);
        check("满100减20，券后价 36元", "com.tencent.mm", null, null, null);
        check("优惠券 5元 已发放", "com.tencent.mm", null, null, null);
        check("签到得 5 元红包", "com.tencent.mm", null, null, null);
        check("限时优惠 立即领取", "com.tencent.mm", null, null, null);
        check("活动期间 满减优惠", "com.tencent.mm", null, null, null);
        check("红包待领取，快来抢", "com.tencent.mm", null, null, null);
        check("红包雨 开抢", "com.tencent.mm", null, null, null);

        // ===== F. 非交易系统文本 =====
        check("系统更新 5.0 版本", "com.android.systemui", null, null, null);
        check("步行 8000 步 消耗 200 千卡", "com.android.systemui", null, null, null);
        check("今日天气 晴 25度", "com.android.systemui", null, null, null);
        check("手机可用空间不足3G 释放手机空间", "com.android.systemui", null, null, null);
        check("欢迎使用微信支付", "com.tencent.mm", null, null, null);
        check("微信支付 3个联系人发来11条消息", "com.tencent.mm", null, null, null);
        check("您的美团订单已支付成功，点击查看详情>", "com.sankuai.meituan", null, null, null);

        // ===== G. 金额校验（标准④ 非金额数字遮蔽）=====
        check("订单号 2026110812345678 支付成功 18.99元", "com.tencent.mm", 1899L, 1, null);
        check("11-08 20:43 支付成功 18.99", "com.android.systemui", 1899L, 1, null);
        check("尾号 1234 的银行卡 消费 88元", "com.android.mms", 8800L, 1, null);
        check("2026年11月8日 消费 36元", "com.android.systemui", 3600L, 1, null);
        check("进度 95% 支付 20元", "com.android.systemui", 2000L, 1, null);

        // ===== H. 银行营销短信（实测误判案例）=====
        check("中国银行 【中国银行】中行VISA/万事达信用卡绑Apple Pay境外消费享双重礼：绑卡首笔消费至高返5美元、消费笔笔返1%消费金。绑卡方式：中国银行APP搜Apple Pay绑卡，活动需要先报名再参与。拒收请回复R",
                "com.android.mms", null, null, null);
        check("【建设银行】您尾号1234的信用卡消费1,280.00元", "com.android.mms", 128000L, 1, null);

        // ===== I. 标签词的边界（「付款方」是「付款方式」的前缀）=====
        // 真机翻车：「支付宝 你已成功付款 12.00元 付款方式：余额宝」
        // 旧逻辑把「付款方」当商户标签，取出「式：余额宝」→ 清洗成「式:」
        // 置信度 100 直接入账 —— 商户名完全是垃圾。
        check("支付宝 你已成功付款 12.00元 付款方式：余额宝",
                "com.eg.android.AlipayGphone", 1200L, 1, "支付宝");
        // 反例保护：真商户在「收款方」，不能被「付款方式」挤掉
        check("支付成功 ¥128.50 收款方：星巴克咖啡 付款方式：零钱",
                "com.tencent.mm", 12850L, 1, "星巴克");

        System.out.println("\n======== 结果：通过 " + pass + " / 失败 " + fail + " ========");
        if (fail > 0) System.exit(1);
    }
}
