import com.aron.autobill.core.Category;

/** 分类推断自检：关键词 → 分类 */
public class CategorySelftest {

    static int pass = 0, fail = 0;

    static void check(String text, int type, String expect) {
        String got = Category.classify(text, "", type);
        if (expect.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: [" + text + "] 期望=" + expect + " 实际=" + got);
        }
    }

    public static void main(String[] args) {
        // 餐饮
        check("华莱士 你已成功支付 ¥36", 1, "餐饮");
        check("您的美团订单已支付成功", 1, "餐饮");
        check("饿了么 外卖订单", 1, "餐饮");
        // 购物
        check("淘宝 购物 支付成功", 1, "购物");
        check("京东下单成功", 1, "购物");
        check("拼多多 支付成功", 1, "购物");
        // 交通
        check("滴滴出行 行程结束 支付 25元", 1, "交通");
        check("12306 购票成功", 1, "交通");
        check("地铁 乘车码 扣款 4元", 1, "交通");
        // 美团已进餐饮关键词，但不能抢走「美团打车」这笔 —— 交通优先于餐饮
        check("美团打车 行程结束 支付 25元", 1, "交通");
        // 生活缴费
        check("话费充值成功 50元", 1, "生活缴费");
        check("电费 缴费成功", 1, "生活缴费");
        // 娱乐
        check("腾讯视频会员 续费", 1, "娱乐");
        // 医疗
        check("市第一医院 挂号 20元", 1, "医疗");
        // 转账 / 红包（用户最关心的场景）
        check("你已向 张三 转账 100元", 1, "转账");
        check("张三 转账给你 50元", 2, "转账");
        check("领取了红包 20.00元", 1, "红包");
        check("发出了红包 8.88元", 1, "红包");
        // 收入
        check("工资 到账 8000元", 2, "工资");
        check("退款 到账 36元", 2, "退款");
        // 兜底
        check("不明来源 支付 5元", 1, "其他");
        check("xyz 支付 5元", 1, "其他");

        System.out.println("======== 分类自检 通过 " + pass + " / 失败 " + fail + " ========");
    }
}
