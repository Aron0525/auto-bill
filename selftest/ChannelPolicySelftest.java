package com.aron.autobill.selftest;

import com.aron.autobill.core.NotifPolicy;

/**
 * 通知准入策略考卷。
 *
 * 这一层在**解析器之前**做判断，所以它错了是双倍的错：
 *   · 该解析的判成 SKIP → 漏记（用户觉得"根本没记账"）
 *   · 该丢弃的判成 PARSE → 错记（用户觉得"账本是假的"）
 *
 * 题目全部来自真机 dumpsys 抓到的渠道结构：
 *   微信：message_channel_new_id / reminder_channel_id / voip_*
 *   支付宝：100058 朋友消息 / 100060 交易 / 100061 物流 / 100062 订阅 / 100064 活动
 *
 * 注意：本考卷**不依赖 Android API**，NotifPolicy 是纯 Java 类。
 */
public class ChannelPolicySelftest {

    static int pass = 0, fail = 0;

    static final String WX = "com.tencent.mm";
    static final String ALI = "com.eg.android.AlipayGphone";

    static final String CH_WX_MSG = "message_channel_new_id";
    static final String CH_WX_VOIP = "voip_norify_channel_silent1738329284124";
    static final String CH_WX_REMIND = "reminder_channel_id";
    static final String CH_ALI_100058 = "mipush|com.eg.android.AlipayGphone|100058";  // 朋友消息
    static final String CH_ALI_100060 = "mipush|com.eg.android.AlipayGphone|100060";  // 交易
    static final String CH_ALI_100061 = "mipush|com.eg.android.AlipayGphone|100061";  // 物流
    static final String CH_ALI_100062 = "mipush|com.eg.android.AlipayGphone|100062";  // 订阅
    static final String CH_ALI_100064 = "mipush|com.eg.android.AlipayGphone|100064";  // 活动

    /** 普通：无 group / 无 ongoing / 无会话标题 */
    static NotifPolicy.Decision d(String pkg, String ch, String title, String text) {
        return NotifPolicy.decide(pkg, ch, title, text, false, false, null);
    }

    static void want(String id, String desc, NotifPolicy.Decision got, int verdict) {
        if (got.verdict == verdict) {
            pass++;
            System.out.println("  [PASS] " + pad(id) + desc + "  → " + got);
        } else {
            fail++;
            System.out.println("  [FAIL] " + pad(id) + desc);
            System.out.println("         期望=" + NotifPolicy.verdictName(verdict)
                    + " 实际=" + got);
        }
    }

    static void wantCap(String id, String desc, NotifPolicy.Decision got, int maxCap) {
        if (got.verdict != NotifPolicy.SKIP && got.verdict != NotifPolicy.PLACEHOLDER
                && got.confCap <= maxCap) {
            pass++;
            System.out.println("  [PASS] " + pad(id) + desc + "  → 上限=" + got.confCap);
        } else {
            fail++;
            System.out.println("  [FAIL] " + pad(id) + desc
                    + "  期望上限≤" + maxCap + " 实际=" + got);
        }
    }

    static void wantNoTitle(String id, String desc, NotifPolicy.Decision got) {
        if (!got.titleAsMerchant) {
            pass++;
            System.out.println("  [PASS] " + pad(id) + desc + "  → 标题不当商户");
        } else {
            fail++;
            System.out.println("  [FAIL] " + pad(id) + desc + "  标题仍被当商户");
        }
    }

    static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 26) b.append(' ');
        return b.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== 通知准入策略考卷（真机渠道结构）===");
        System.out.println();

        // ---------- A 组：支付宝渠道分流 ----------
        // App 自己已经标好了品类，这一组验证我们真的按它分了流。

        want("ali_100060_trade", "支付宝-交易渠道放行",
                d(ALI, CH_ALI_100060, "支付成功", "付款成功 -56.80元 付款给 麦当劳"),
                NotifPolicy.PARSE);

        want("ali_100062_subscribe", "支付宝-订阅消息丢弃",
                d(ALI, CH_ALI_100062, "订阅消息", "您订阅的店铺上新了，快来看看"),
                NotifPolicy.SKIP);

        want("ali_100064_activity", "支付宝-活动通知丢弃",
                d(ALI, CH_ALI_100064, "活动通知", "双11狂欢，满200减30优惠券已发放"),
                NotifPolicy.SKIP);

        want("ali_100058_friend", "支付宝-朋友消息丢弃",
                d(ALI, CH_ALI_100058, "张三", "我转你100块，收到了吗"),
                NotifPolicy.SKIP);

        wantCap("ali_100061_logistics", "支付宝-订单物流压置信度",
                d(ALI, CH_ALI_100061, "订单已发货", "您的订单已发货，运费12.00元"),
                45);

        // ---------- B 组：微信渠道 ----------

        want("wx_voip", "微信-通话邀请丢弃",
                d(WX, CH_WX_VOIP, "语音通话", "张三 邀请你语音通话"),
                NotifPolicy.SKIP);

        wantCap("wx_reminder", "微信-其他通知压置信度",
                d(WX, CH_WX_REMIND, "微信团队", "你的账号已在新设备登录"),
                45);

        want("wx_pay_ok", "微信-支付凭证放行",
                d(WX, CH_WX_MSG, "微信支付",
                        "支付成功 ¥128.50 收款方：星巴克咖啡(万象城店) 支付方式：零钱"),
                NotifPolicy.PARSE);

        // ---------- C 组：通知类型（与渠道无关的硬规则）----------

        // 通知组摘要：微信「3条新消息」。不进解析器的理由见 NotifPolicy 注释。
        want("group_summary", "通知组摘要丢弃",
                NotifPolicy.decide(WX, CH_WX_MSG, "微信", "3条新消息", true, false, null),
                NotifPolicy.SKIP);

        want("ongoing", "常驻通知丢弃",
                NotifPolicy.decide(WX, CH_WX_MSG, "微信", "正在通话中 ¥0.00", false, true, null),
                NotifPolicy.SKIP);

        // ---------- D 组：会话标题（最容易记错账的一类）----------

        // 微信群里有人发转账，标题是群名。若当商户会得到「工作群」这种账目。
        {
            NotifPolicy.Decision got = NotifPolicy.decide(
                    WX, CH_WX_MSG, "工作群", "张三: 转你200", false, false, "工作群");
            wantNoTitle("chat_group_notitle", "群聊标题不当商户", got);
            wantCap("chat_group_cap", "群聊置信度压低", got, 50);
        }

        // 好友单聊
        {
            NotifPolicy.Decision got = NotifPolicy.decide(
                    WX, CH_WX_MSG, "李四", "李四: 已转账 200.00元", false, false, "李四");
            wantNoTitle("chat_friend_notitle", "好友标题不当商户", got);
        }

        // 真实支付凭证没有 conversationTitle，标题可以当兜底
        {
            NotifPolicy.Decision got = d(WX, CH_WX_MSG, "微信支付", "支付成功 ¥66.60");
            if (got.titleAsMerchant) {
                pass++;
                System.out.println("  [PASS] pay_has_title        支付凭证允许标题兜底");
            } else {
                fail++;
                System.out.println("  [FAIL] pay_has_title        支付凭证被误禁了标题兜底");
            }
        }

        // ---------- E 组：占位符（微信转账卡片的现实限制）----------
        // 通知栏只有「[转账]」，金额在 App 里。不记账，但要提示用户。

        want("ph_transfer", "微信转账卡片→待补全",
                d(WX, CH_WX_MSG, "张三", "[转账]"),
                NotifPolicy.PLACEHOLDER);

        want("ph_redpacket", "微信红包→待补全",
                d(WX, CH_WX_MSG, "张三", "[微信红包]"),
                NotifPolicy.PLACEHOLDER);

        want("ph_transfer_long", "转账+提示语→待补全",
                d(WX, CH_WX_MSG, "张三", "[转账] 请收款"),
                NotifPolicy.PLACEHOLDER);

        want("ph_voice", "语音消息→待补全",
                d(WX, CH_WX_MSG, "张三", "[语音]"),
                NotifPolicy.PLACEHOLDER);

        // 关键反例：带金额的转账不是占位符，必须正常记账
        // 「[转账] 200.00元」是能记的，别把它也拦了
        {
            NotifPolicy.Decision got = d(WX, CH_WX_MSG, "张三", "[转账] 200.00元");
            if (got.verdict != NotifPolicy.PLACEHOLDER) {
                pass++;
                System.out.println("  [PASS] ph_not_amount       带金额的转账不当占位符  → " + got);
            } else {
                fail++;
                System.out.println("  [FAIL] ph_not_amount       带金额的转账被误判成占位符");
            }
        }

        // 支付凭证不是占位符
        {
            NotifPolicy.Decision got = d(WX, CH_WX_MSG, "微信支付", "支付成功 ¥36.50 瑞幸咖啡");
            if (got.verdict == NotifPolicy.PARSE) {
                pass++;
                System.out.println("  [PASS] ph_not_pay          支付凭证不当占位符");
            } else {
                fail++;
                System.out.println("  [FAIL] ph_not_pay          支付凭证被误判: " + got);
            }
        }

        // 【真机翻车记录】调用方传的 body 是「标题 + 正文」的拼接结果。
        // 「李四」+「[转账]」→「李四 [转账]」，占位符正则匹配不上，
        // 这笔交易就从「待补全」变成「静默丢弃」—— 用户永远不知道该补记。
        want("ph_with_title_prefix", "标题拼进正文也能认占位符",
                d(WX, CH_WX_MSG, "李四", "李四 [转账]"),
                NotifPolicy.PLACEHOLDER);

        want("ph_with_title_rp", "标题拼进正文（红包）",
                d(WX, CH_WX_MSG, "张三", "张三 [微信红包]"),
                NotifPolicy.PLACEHOLDER);

        // ---------- F 组：没有会话标题时也要认出聊天 ----------
        // 【真机翻车记录】EXTRA_CONVERSATION_TITLE 不是可靠信号：
        // MIUI 某些版本不填充、`cmd notification post` 发的没有、群消息折叠后也可能丢。
        // 实测：标题「工作群」+ 正文「张三: 已转账 200.00元」
        //       → 记成 商户=工作群 conf=98 直接入账，账本被写脏。
        // 所以必须有基于文本结构的兜底判断。

        {
            NotifPolicy.Decision got = NotifPolicy.decide(
                    WX, CH_WX_MSG, "工作群", "张三: 已转账 200.00元", false, false, null);
            wantNoTitle("chat_noconv_notitle", "无会话标题的群聊也不当商户", got);
            wantCap("chat_noconv_cap", "无会话标题的群聊也压置信度", got, 50);
        }

        // 中文全角冒号同样要认
        {
            NotifPolicy.Decision got = NotifPolicy.decide(
                    WX, CH_WX_MSG, "李四", "李四：晚上一起吃饭吗", false, false, null);
            wantCap("chat_fullwidth_colon", "全角冒号的聊天也压低", got, 50);
        }

        // 关键反例：支付凭证不能被误判成聊天
        // 「收款方：星巴克」也有冒号，但左边是标签不是人名。
        // 漏判这里就会从"记错"变成"不记"，那是另一种伤害。
        {
            NotifPolicy.Decision got = d(WX, CH_WX_MSG, "微信支付",
                    "支付成功 ¥128.50 收款方：星巴克咖啡 支付方式：零钱");
            if (got.verdict == NotifPolicy.PARSE && got.titleAsMerchant && got.confCap >= 100) {
                pass++;
                System.out.println("  [PASS] voucher_not_chat      支付凭证不被判成聊天");
            } else {
                fail++;
                System.out.println("  [FAIL] voucher_not_chat      支付凭证被误判成聊天: " + got
                        + " cap=" + got.confCap);
            }
        }

        // 支付宝凭证同样不能被误判
        {
            NotifPolicy.Decision got = d(ALI, CH_ALI_100060, "支付宝",
                    "付款成功 -56.80元 付款给 麦当劳 商户订单号：2024111522001");
            if (got.verdict == NotifPolicy.PARSE && got.confCap >= 100) {
                pass++;
                System.out.println("  [PASS] ali_not_chat         支付宝凭证不被判成聊天");
            } else {
                fail++;
                System.out.println("  [FAIL] ali_not_chat         支付宝凭证被误判: " + got);
            }
        }

        // ---------- G 组：营销文案不能靠渠道漏网 ----------
        // 有些营销走的是交易渠道（100060），渠道分流挡不住，必须靠文本兜底。

        want("mkt_in_trade_ch", "交易渠道里的营销文案也要压置信度",
                d(ALI, CH_ALI_100060, "活动", "恭喜您获得立减金，点击领取"),
                NotifPolicy.PARSE);   // 渠道判定放行，靠解析器 BLOCK/PENDING 兜

        // ---------- H 组：未知渠道不能一刀切 ----------
        // 新版本 App 会加新渠道。认不出时必须给 LOW 而不是 SKIP，
        // 否则升级后就静默漏记了。

        wantCap("unknown_ali_ch", "未知支付宝渠道→保守放行",
                d(ALI, "mipush|com.eg.android.AlipayGphone|100099", "支付成功",
                        "支付成功 ¥12.00"),
                100);

        wantCap("unknown_pkg", "未知包名→保守放行",
                d("com.unknown.app", "some_channel", "支付成功", "支付成功 ¥8.80"),
                100);

        System.out.println();
        System.out.println("==== 通知准入策略: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }
}
