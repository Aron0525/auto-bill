package com.aron.autobill.selftest;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.ParseResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 通知精准度考卷 —— 按**真实通知字段结构**出题。
 *
 * 为什么需要新考卷：LlmBenchBaselineSelftest 把所有字段拼成一个字符串，
 * 掩盖了真实场景的结构。真机 dumpsys 抓到的事实是：
 *
 *   title   = App 名称（微信支付 / 抖音 / 支付宝）或联系人
 *   text    = 真正的交易详情
 *   bigText = 完整正文（text 被截断时的全文）
 *
 * 拼成一段后，「微信支付」和正文混在一起，
 * 解析器就会把「微信」当商户 —— 这就是错账的根源。
 *
 * 本考卷分两类：
 *   A. 分字段（notif）——  模拟 NotificationListenerService 拿到的真实结构
 *   B. 单串（flat）  ——   模拟无障碍兜底 / 短信
 */
public class NotifPrecisionSelftest {

    static int pass = 0, fail = 0;

    static void check(String id, String desc, ParseResult r, String rawForCategory,
                      Long wantAmt, String[] wantMer, String[] forbidMer,
                      int wantType, String wantCat) {
        List<String> problems = new ArrayList<>();

        if (wantAmt != null) {
            if (!r.ok || r.amountFen != wantAmt.longValue()) {
                problems.add("金额 期望=" + fmt(wantAmt) + " 实际=" + (r.ok ? fmt(r.amountFen) : "未解析"));
            }
        }
        if (wantMer != null) {
            String mer = r.merchant == null ? "" : r.merchant;
            boolean ok = false;
            for (String k : wantMer) if (mer.contains(k)) { ok = true; break; }
            if (!ok) problems.add("商户 期望⊇" + Arrays.toString(wantMer) + " 实际=「" + mer + "」");
        }
        if (forbidMer != null) {
            String mer = r.merchant == null ? "" : r.merchant;
            for (String k : forbidMer) {
                if (mer.contains(k)) {
                    problems.add("商户 不该含「" + k + "」实际=「" + mer + "」");
                    break;
                }
            }
        }
        if (wantType != 0 && r.type != wantType) {
            problems.add("方向 期望=" + dir(wantType) + " 实际=" + dir(r.type));
        }
        if (wantCat != null) {
            String cat = Category.classifyFor(r, rawForCategory == null ? "" : rawForCategory);
            if (!cat.contains(wantCat)) {
                problems.add("分类 期望=" + wantCat + " 实际=" + cat);
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

    /** 只判「不该记账」 */
    static void checkReject(String id, String desc, ParseResult r) {
        if (r != null && r.ok && r.amountFen > 0) {
            fail++;
            System.out.println("  [FAIL] " + id + "  " + desc);
            System.out.println("         · 不该记账却解析出 " + fmt(r.amountFen)
                    + " 「" + r.merchant + "」");
        } else {
            pass++;
            System.out.println("  [PASS] " + id + "  " + desc);
        }
    }

    static String fmt(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    static String dir(int t) {
        return t == 2 ? "收入" : (t == 1 ? "支出" : "未知");
    }

    /** 模拟 NotificationListenerService：title/text/bigText 分字段送入 */
    static ParseResult notif(String title, String text, String bigText, String pkg) {
        return new BillParser().parseNotification(title, text, bigText, pkg);
    }

    public static void main(String[] args) {
        BillParser bp = new BillParser();

        System.out.println("=== 通知精准度考卷（按真实字段结构）===");
        System.out.println();

        // ---------- A 组：分字段（NotificationListenerService 真实结构）----------

        // A1 微信支付：title 是 App 名，body 才是详情
        //    旧实现会把「微信支付」拼进正文，商户被污染
        {
            String body = "支付成功 ¥128.50 收款方：星巴克咖啡(万象城店) 支付方式：零钱";
            check("wx_title_is_app", "微信 title=微信支付",
                    notif("微信支付", body, null, "com.tencent.mm"), body,
                    12850L, new String[]{"星巴克"}, new String[]{"微信", "支付成功"}, 1, null);
        }

        // A2 支付宝：title 为 App 名，商户在「付款给」里
        {
            String body = "付款成功 -56.80元 付款给 麦当劳(软件园店) 余额宝";
            check("ali_title_is_app", "支付宝 title=支付宝",
                    notif("支付宝", body, null, "com.eg.android.AlipayGphone"), body,
                    5680L, new String[]{"麦当劳"}, new String[]{"支付宝"}, 1, null);
        }

        // A3 bigText 完整、text 被截断：必须优先用 bigText
        {
            String txt = "您账户1234于11月10日…";
            String big = "【招商银行】您账户1234于11月10日15:30入账人民币12000.00元，"
                    + "摘要：工资，余额45231.88元。";
            check("prefer_bigtext", "text 截断 < bigText 完整",
                    notif("招商银行", txt, big, "com.android.mms"), big,
                    1200000L, new String[]{"招商", "工资"}, new String[]{"短信"}, 2, "工资");
        }

        // A4 验证码：必须拒收
        {
            String body = "【抖音】验证码6473，用于支付验证登录，5分钟内有效。"
                    + "验证码提供给他人可能导致账号被盗。";
            checkReject("reject_verify_code", "抖音验证码（含『支付』也不该记）",
                    notif("抖音", body, null, "com.android.mms"));
        }

        // A5 营销推送：含「满减/优惠」不记账
        checkReject("reject_marketing", "营销推送不得记账",
                notif("美团",
                        "限时抢购！满30减15，下单立省，点击立即领取优惠券",
                        null, "com.sankuai.meituan"));

        // A6 外卖订单：商品明细 + 实付
        {
            String body = "订单已完成，实付39.20元，华莱士炸鸡汉堡";
            check("meituan_notif", "美团外卖通知",
                    notif("美团", body, null, "com.sankuai.meituan"), body,
                    3920L, new String[]{"华莱士", "美团"}, null, 1, "餐饮");
        }

        // A7 淘宝：有原价干扰，取「实付款」
        {
            String body = "确认收货 实付款 268.00 商品总价 328.00 优惠 60.00 小米官方旗舰店";
            check("taobao_notif", "淘宝订单（有原价干扰）",
                    notif("淘宝", body, null, "com.taobao.taobao"), body,
                    26800L, new String[]{"小米", "淘宝"}, null, 1, null);
        }

        // A8 收入：转账收款，title 是联系人
        {
            String body = "微信支付 收到转账 ¥200.00 已存入零钱";
            check("transfer_in", "微信转账收款（title 含人名）",
                    notif("李四", body, null, "com.tencent.mm"), body,
                    20000L, new String[]{"李四"}, new String[]{"微信"}, 2, null);
        }

        // A9 银行消费短信（分字段版）
        {
            String body = "【工商银行】您尾号5678卡11月11日10:23消费支出58.00元，余额3210.50元。";
            check("bank_expense", "工商银行消费",
                    notif("95588", body, null, "com.android.mms"), body,
                    5800L, new String[]{"工商"}, new String[]{"95588"}, 1, null);
        }

        // A10 分期还款进度
        {
            String body = "本期账单已还清 1000.00元，剩余6期";
            check("loan_installment", "信用卡还款通知",
                    notif("招商银行信用卡", body, null, "com.android.mms"), body,
                    100000L, new String[]{"招商"}, null, 1, null);
        }

        // ---------- B 组：单串（无障碍兜底 / 短信整段）----------

        // B1 整屏通知栏（真机#66 实测）
        String b1 = "支付宝 基金申购确认成功通知 12:37 你于2026-11-06买入易方达中债"
                + "3-5年期国债基金已确认成功，确认金额99.92元，手续费0.10元";
        check("flat_fund", "基金申购（整段）",
                bp.parse(b1, "com.eg.android.AlipayGphone"), b1,
                9992L, new String[]{"易方达", "基金"}, null, 1, null);

        // B2 短信两笔：取第一笔
        String b2 = "【工商银行】您尾号5678卡11月11日共2笔交易：10:23消费支出58.00元，"
                + "余额3210.50元；14:07消费支出128.00元，余额3082.50元。";
        check("flat_multi", "短信两笔（取第一笔）",
                bp.parse(b2, "com.android.mms"), b2,
                5800L, new String[]{"工商"}, null, 1, null);

        // B3 含干扰金额：原价/优惠/运费
        String b3 = "饿了么 订单完成 商品小计 47.20 配送费 4.00 优惠 -8.00 实付 39.20 华莱士";
        check("flat_noise", "饿了么（原价干扰）",
                bp.parse(b3, "me.ele"), b3,
                3920L, new String[]{"华莱士"}, null, 1, null);

        // B4 繁体/异体字
        String b4 = "微信支付 商戶全称 星巴克 支付金颉 128.50 余額宝";
        check("flat_trad", "繁体异体字",
                bp.parse(b4, "com.tencent.mm"), b4,
                12850L, new String[]{"星巴克"}, new String[]{"微信"}, 1, null);

        // ---------- C 组：金额择优的硬骨头 ----------

        // C1 多金额混排，实付在最后
        {
            String body = "外卖订单 商品小计 88.00 包装费 2.00 配送费 5.00 优惠 -15.00 实付 80.00 肯德基";
            check("amt_pick_last", "多个金额择优（实付在末尾）",
                    notif("饿了么", body, null, "me.ele"), body,
                    8000L, new String[]{"肯德基", "饿了么"}, null, 1, null);
        }

        // C2 淘宝：原价在前、实付在后，且原价更大（最容易取错）
        {
            String body = "订单支付成功 商品总价 599.00 优惠券 -100.00 实付款 499.00 优衣库官方旗舰店";
            check("amt_orig_bigger", "原价大于实付",
                    notif("淘宝", body, null, "com.taobao.taobao"), body,
                    49900L, new String[]{"优衣库"}, null, 1, null);
        }

        // C3 余额远大于交易额：绝不能取余额
        {
            String body = "【建设银行】您尾号8899的储蓄卡11月12日18:30消费支出36.00元，余额125680.00元。";
            check("amt_not_balance", "余额远大于交易额",
                    notif("建设银行", body, null, "com.android.mms"), body,
                    3600L, new String[]{"建设"}, null, 1, null);
        }

        // C4 手续费与本金：取本金
        {
            String body = "基金赎回确认 赎回金额 5000.00 手续费 5.00 实际到账 4995.00 易方达";
            check("amt_fund", "基金赎回（本金 vs 手续费 vs 到账）",
                    notif("支付宝", body, null, "com.eg.android.AlipayGphone"), body,
                    499500L, new String[]{"易方达", "基金"}, null, 2, null);
        }

        // C5 分期：期数与还款金额
        {
            String body = "还款成功 本期还款 1288.00元 剩余6期 每期214.67元";
            check("amt_installment", "分期还款（期数干扰）",
                    notif("招商银行信用卡", body, null, "com.android.mms"), body,
                    128800L, new String[]{"招商"}, null, 1, null);
        }

        // C6 带千分位
        {
            String body = "微信支付 转账 收款 ¥12,800.00 来自 王五";
            check("amt_thousands", "千分位金额",
                    notif("微信支付", body, null, "com.tencent.mm"), body,
                    1280000L, new String[]{"王五"}, null, 2, null);
        }

        // C7 电话/卡号不能当金额
        {
            String body = "【工商银行】您尾号1234卡消费100.00元，客服电话95588，余额5000.00元。";
            check("amt_phone", "客服电话不是金额",
                    notif("工商银行", body, null, "com.android.mms"), body,
                    10000L, new String[]{"工商"}, null, 1, null);
        }

        // ---------- D 组：用户真机实际安装的 App ----------
        // 这一组的 App 全部在真机 pm list packages 里核对过，
        // 都是**真的会往通知栏推交易信息**的。

        // D1 包名绝不能进账本（真机实测 com.greenpoint... 被当商户名）
        {
            String body = "尊敬的客户，您本月话费已扣除58.00元，余额32.00元。";
            check("pkg_not_merchant", "包名不能当商户名",
                    notif("10086", body, null, "com.greenpoint.android.mc10086.activity"), body,
                    5800L, new String[]{"移动"}, new String[]{"com.", "greenpoint"}, 1, null);
        }

        // D2 小米钱包：不能因为含「小米」就配成「小米之家」
        {
            String body = "您已使用小米公交卡刷卡，扣款2.00元。";
            check("bus_card_not_store", "公交卡≠小米之家",
                    notif("小米钱包", body, null, "com.mipay.wallet"), body,
                    200L, new String[]{"小米钱包"}, new String[]{"小米之家"}, 1, "交通");
        }

        // D3 退款：动作词「原路退回」不能当商户
        {
            String body = "退款18.60元已原路退回，请注意查收。";
            check("refund_not_action", "「原路退回」不是商户",
                    notif("美团", body, null, "com.sankuai.meituan"), body,
                    1860L, new String[]{"美团"}, new String[]{"原路退回", "查收"}, 2, "退款");
        }

        // D4 收益：是收入，不能因为有「余额宝」就判成支出
        {
            String body = "余额宝昨日收益0.52元，累计收益128.30元。";
            check("fund_income", "余额宝收益=收入",
                    notif("支付宝", body, null, "com.eg.android.AlipayGphone"), body,
                    52L, new String[]{"支付宝"}, null, 2, null);
        }

        // D5 打车：分类必须是交通，不能被「美团」拽进餐饮
        {
            String body = "行程已结束，本次支付18.60元。";
            check("taxi_category", "美团打车→交通",
                    notif("美团打车", body, null, "com.sankuai.meituan"), body,
                    1860L, null, null, 1, "交通");
        }

        // D6 高德打车
        {
            String body = "行程已结束，车费32.50元已自动支付。";
            check("gaode_taxi", "高德打车",
                    notif("高德地图", body, null, "com.autonavi.minimap"), body,
                    3250L, new String[]{"高德"}, new String[]{"自动支付"}, 1, "交通");
        }

        // D7 12306 购票
        {
            String body = "您已成功购买G1234次列车车票，票价553.00元。";
            check("rail_ticket", "12306 购票",
                    notif("铁路12306", body, null, "com.MobileTicket"), body,
                    55300L, new String[]{"12306"}, null, 1, "交通");
        }

        // D8 建设银行 App（真机已安装）
        {
            String body = "您尾号1234的储蓄卡消费支出128.00元，余额5678.90元。";
            check("ccb_app", "建行 App 消费",
                    notif("中国建设银行", body, null, "com.chinamworld.main"), body,
                    12800L, new String[]{"建设"}, null, 1, null);
        }

        // D9 中国银行 App（真机已安装）
        {
            String body = "您尾号5678的借记卡消费人民币88.50元。";
            check("boc_app", "中行 App 消费",
                    notif("中国银行", body, null, "com.chinamworld.bocmbci"), body,
                    8850L, new String[]{"中国银行"}, new String[]{"借记卡"}, 1, null);
        }

        // D10 闲鱼卖出
        {
            String body = "您卖出的商品已确认收货，货款128.00元已到账。";
            check("xianyu_sell", "闲鱼卖出到账",
                    notif("闲鱼", body, null, "com.taobao.idlefish"), body,
                    12800L, new String[]{"闲鱼"}, null, 2, null);
        }

        // D11 联通欠费：还没交的钱，置信度必须低（进待确认队列）
        {
            String body = "您的话费已欠费12.30元，请及时缴费。";
            ParseResult r = notif("中国联通", body, null, "com.sinovatech.unicom.ui");
            boolean lowConf = !r.ok || r.confidence < 50;
            if (lowConf) {
                pass++;
                System.out.println("  [PASS] unpaid_bill  欠费提醒进待确认队列 (conf=" + r.confidence + ")");
            } else {
                fail++;
                System.out.println("  [FAIL] unpaid_bill  欠费提醒置信度过高 conf=" + r.confidence
                        + " 会被直接入账，但用户还没交钱");
            }
        }

        // D12 花呗账单：待还金额，不是已支出
        {
            String body = "花呗本月账单1280.00元，还款日11月20日。";
            ParseResult r = notif("支付宝", body, null, "com.eg.android.AlipayGphone");
            boolean lowConf = !r.ok || r.confidence < 50;
            if (lowConf) {
                pass++;
                System.out.println("  [PASS] huabei_bill  花呗账单进待确认队列 (conf=" + r.confidence + ")");
            } else {
                fail++;
                System.out.println("  [FAIL] huabei_bill  花呗账单置信度过高 conf=" + r.confidence
                        + " 会被直接入账，但这是待还不是已还");
            }
        }

        // D13 打车预估：不能当实付，否则真扣款时会记两笔
        {
            String body = "正在为您打车，预估26.00元，实际以行程结束为准。";
            ParseResult r = notif("高德地图", body, null, "com.autonavi.minimap");
            boolean lowConf = !r.ok || r.confidence < 50;
            if (lowConf) {
                pass++;
                System.out.println("  [PASS] taxi_estimate  打车预估进待确认队列 (conf=" + r.confidence + ")");
            } else {
                fail++;
                System.out.println("  [FAIL] taxi_estimate  打车预估置信度过高 conf=" + r.confidence
                        + " 会在真扣款时记成两笔");
            }
        }

        // ---------- E 组：标题是 App 名时的商户兜底 ----------
        // 这是**真机专属**场景：通知真的从 App 发出时，pkg 是「com.android.shell」之类
        // 认不出来的值，platformName 只能是「其他」。
        // 手机上没有「支付宝」这个包，所以正文和 pkg 都给不出商户 ——
        // 但标题明明写着「支付宝」，这才是用户期望看到的商户名。

        // E1 pkg 认不出 + 正文无商户 → 用标题里的 App 名
        {
            String body = "余额宝昨日收益0.52元，累计收益128.30元。";
            check("title_fallback_unknown_pkg", "pkg=shell 时用标题兜底商户",
                    notif("支付宝", body, null, "com.android.shell"), body,
                    52L, new String[]{"支付宝"}, new String[]{"其他", "com."}, 2, null);
        }

        // E2 同上，小米钱包
        {
            String body = "您已使用小米公交卡刷卡，扣款3.00元。";
            check("title_fallback_wallet", "pkg=shell 时小米钱包兜底",
                    notif("小米钱包", body, null, "com.android.shell"), body,
                    300L, new String[]{"小米钱包"}, new String[]{"其他"}, 1, "交通");
        }

        // E3 但标题是 App 名**不能**压过正文里的真实商户
        {
            String body = "支付成功 ¥128.50 收款方：星巴克咖啡(万象城店) 支付方式：零钱";
            check("appname_not_override", "App 名不得覆盖真实商户",
                    notif("支付宝", body, null, "com.eg.android.AlipayGphone"), body,
                    12850L, new String[]{"星巴克"}, new String[]{"支付宝"}, 1, null);
        }

        System.out.println();
        System.out.println("==== 通知精准度: " + pass + " 通过 / " + fail + " 失败 ====");
    }
}
