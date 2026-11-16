package com.aron.autobill.core;

import java.util.List;

/**
 * 特定 App 的账单版式模板。
 *
 * 为什么需要模板：通用规则靠「金额标签 + 上下文猜测」，在这些版式固定的 App 里
 * 属于舍近求远。每个 App 的账单页把钱放在哪个位置、商户叫什么，都是稳定的，
 * 直接按版式定位，准确率远高于通用启发式。
 *
 * 设计原则：
 *  1) 模板只「增强」不「替代」——模板没命中或没取到金额时，自动退回通用规则，
 *     所以新增模板不会让原本能识别的截图变差。
 *  2) 版式指纹要求命中 >= 2 个普通标记，或 1 个独特标记。
 *     避免「聊天里提到美团」就被误判成美团订单页。
 *  3) 金额标签分强/弱两级：强的（实付/实付款）直接采信；
 *     弱的（商品小计/优惠）只在没有强标签时兜底。
 */
public final class OcrTemplates {

    /** 一个版式模板 */
    public static class Tpl {
        public final String id;
        public final String name;
        /** 普通版式指纹，命中 >= 2 个才算识别 */
        public final String[] markers;
        /** 独特指纹，命中 1 个即可判定 */
        public final String[] uniqueMarkers;
        /** 强金额标签（本笔金额几乎一定在这行） */
        public final String[] strongAmount;
        /** 弱金额标签（明细项，仅兜底） */
        public final String[] weakAmount;
        /** 同行商户标签：「商户全称 华莱士」这种 */
        public final String[] merchantInline;
        /** 找商户时要跳过的行 */
        public final String[] noise;
        /** 商户优先在金额下方（微信支付凭证是「支付成功 / ¥36.50 / 华莱士」） */
        public final boolean merchantBelow;

        Tpl(String id, String name, String[] markers, String[] uniqueMarkers,
            String[] strongAmount, String[] weakAmount, String[] merchantInline,
            String[] noise, boolean merchantBelow) {
            this.id = id;
            this.name = name;
            this.markers = markers;
            this.uniqueMarkers = uniqueMarkers;
            this.strongAmount = strongAmount;
            this.weakAmount = weakAmount;
            this.merchantInline = merchantInline;
            this.noise = noise;
            this.merchantBelow = merchantBelow;
        }

        /** 这是哪个支付渠道（写进账单的「来源」） */
        public String channelName() {
            if ("wechat".equals(id)) return "微信支付";
            if ("alipay".equals(id)) return "支付宝";
            return "";
        }
    }

    // ==================== 微信支付 ====================
    private static final Tpl WECHAT = new Tpl(
            "wechat", "微信支付",
            new String[]{"当前状态", "收款方", "转账说明", "支付时间", "商品", "商户", "转账"},
            new String[]{"微信支付", "微信红包", "零钱通", "已存入零钱", "微信转账"},
            new String[]{"支付金额", "转账金额", "退款金额", "已支付", "实付", "支付成功", "转账成功"},
            new String[]{"金额"},
            new String[]{"商户全称", "商户名称", "收款方", "收款人", "付款给", "转账给", "商户", "商品"},
            new String[]{"当前状态", "支付时间", "创建时间", "转账时间", "支付方式",
                    "交易单号", "商户单号", "转账说明", "零钱", "微信支付", "账单详情",
                    "转账", "收款方", "商品", "收款时间", "到账时间"},
            true);   // 微信凭证：金额在上、商户在下

    // ==================== 支付宝 ====================
    private static final Tpl ALIPAY = new Tpl(
            "alipay", "支付宝",
            new String[]{"账单分类", "支付方式", "交易号", "创建时间", "商品说明", "余额"},
            new String[]{"支付宝", "余额宝", "花呗", "商户全称", "蚂蚁"},
            new String[]{"支付金额", "实际支付", "付款金额", "实付", "交易成功", "交易金额"},
            new String[]{"金额", "合计"},
            new String[]{"商户全称", "商户名称", "收款方", "收款人", "商户", "商品说明"},
            new String[]{"账单详情", "账单分类", "创建时间", "付款时间", "交易号", "商户订单号",
                    "支付方式", "商品说明", "交易成功", "余额宝", "花呗", "余额",
                    "支付宝", "收款方", "商家订单号", "转账备注"},
            false);

    // ==================== 美团 ====================
    private static final Tpl MEITUAN = new Tpl(
            "meituan", "美团",
            new String[]{"待评价", "配送费", "商品小计", "实付", "订单号", "餐具", "准时宝"},
            new String[]{"美团外卖", "美团"},
            new String[]{"实付款", "实付", "在线支付", "已支付", "支付金额"},
            new String[]{"商品小计", "小计", "合计", "总计"},
            new String[]{"商家", "店铺"},
            new String[]{"配送费", "打包费", "餐盒费", "包装费", "优惠", "折扣", "红包",
                    "商品小计", "订单号", "下单时间", "支付方式", "备注", "准时宝",
                    "餐具", "发票", "小计", "合计", "总计", "共减", "已减", "原价",
                    "待评价", "已送达", "申请退款", "联系商家", "再来一单",
                    "订单详情", "订单", "详情", "开票", "评价", "配送"},
            false);

    // ==================== 饿了么 ====================
    private static final Tpl ELEME = new Tpl(
            "eleme", "饿了么",
            new String[]{"配送费", "商品小计", "实付", "餐具", "订单编号"},
            new String[]{"饿了么", "蜂鸟"},
            new String[]{"实付金额", "实付", "已支付", "支付金额"},
            new String[]{"商品小计", "小计", "合计", "总计"},
            new String[]{"商家", "店铺"},
            new String[]{"配送费", "打包费", "餐盒费", "包装费", "优惠", "折扣", "红包",
                    "商品小计", "订单编号", "下单时间", "支付方式", "备注", "准时宝",
                    "餐具", "发票", "小计", "合计", "总计", "共减", "已减", "原价",
                    "已送达", "申请退款", "联系商家", "再来一单"},
            false);

    // ==================== 淘宝 / 天猫 ====================
    private static final Tpl TAOBAO = new Tpl(
            "taobao", "淘宝",
            new String[]{"订单详情", "宝贝", "确认收货", "店铺", "退款", "物流", "客服"},
            new String[]{"淘宝", "天猫"},
            new String[]{"实付款", "实付", "应付", "已付款", "支付金额"},
            new String[]{"商品总价", "合计", "总价", "小计"},
            new String[]{"店铺", "商家"},
            new String[]{"订单编号", "创建时间", "付款时间", "成交时间", "宝贝", "数量",
                    "运费", "优惠", "积分", "确认收货", "退款", "物流", "客服",
                    "商品总价", "合计", "总价", "小计", "实付款", "订单详情", "订单"},
            false);

    private static final Tpl[] ALL = {WECHAT, ALIPAY, MEITUAN, ELEME, TAOBAO};

    /**
     * OCR 异体字/繁体归一。真机实测 ML Kit 会把个别字认成形近字：
     * 「支付金额」出成「支付金颉」、「商户全称」出成「商戶全称」。
     * 不归一，标签匹配全落空 —— 金额退回瞎猜、商户把标签本身当店名。
     * 只映射账单 UI 常见字，全部是「错→对」单向修正（幂等）。
     */
    static final char[][] CONFUSABLES = {
            {'額', '额'}, {'頡', '额'}, {'頷', '额'}, {'頜', '额'}, {'顎', '额'}, {'頴', '额'},
            {'颉', '额'},   // 真机实测：ML Kit 把「额」认成简化异体「颉」
            {'戶', '户'}, {'賬', '账'}, {'錢', '钱'}, {'轉', '转'}, {'餘', '余'},
            {'華', '华'}, {'買', '买'}, {'賣', '卖'}, {'現', '现'}, {'寶', '宝'},
            {'資', '资'}, {'費', '费'}, {'單', '单'}, {'據', '据'}, {'態', '态'},
    };

    /** 归一 OCR 异体字；没有命中时原样返回 */
    public static String norm(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean hit = false;
        for (int i = 0; i < s.length() && !hit; i++) {
            char c = s.charAt(i);
            for (char[] p : CONFUSABLES) {
                if (p[0] == c) { hit = true; break; }
            }
        }
        if (!hit) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char rep = c;
            for (char[] p : CONFUSABLES) {
                if (p[0] == c) { rep = p[1]; break; }
            }
            sb.append(rep);
        }
        return sb.toString();
    }

    /**
     * 从 OCR 行里识别版式。返回得分最高的模板，都不像则返回 null（走通用规则）。
     *
     * 判定门槛（避免「聊天里提到美团」被当成美团订单页）：
     *   - 命中 >= 2 个普通标记 → 认
     *   - 只命中 1 个独特标记 → 认（如「商户全称」这种只在账单页出现的词）
     *   - 独特标记是「App 名」这类日常词（美团外卖/淘宝/支付宝）→ 仍需 >= 1 个普通标记
     */
    public static Tpl detect(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        String all = norm(join(lines));
        Tpl best = null;
        int bestScore = 0;
        for (Tpl t : ALL) {
            int hits = 0;
            for (String m : t.markers) if (all.contains(m)) hits++;

            boolean unique = false;
            for (String u : t.uniqueMarkers) {
                if (all.contains(u)) { unique = true; break; }
            }

            int score;
            if (hits >= 2) {
                score = hits + (unique ? 3 : 0);
            } else if (hits == 1 && unique) {
                score = 3;              // 版式词 + App 名，够可信
            } else {
                continue;               // 只有 App 名 / 只有 1 个普通标记 → 不认
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) if (l != null) sb.append(l).append('\n');
        return sb.toString();
    }

    /** 供自检使用：按 id 取模板 */
    public static Tpl byId(String id) {
        for (Tpl t : ALL) if (t.id.equals(id)) return t;
        return null;
    }
}
