package com.aron.autobill.model;

import java.util.Objects;

/** 一条记账记录 */
public class Bill {
    public long id;
    public long ts;            // 交易时间（毫秒）
    public String platform;    // 来源平台包名，显示时转中文名
    public String merchant;    // 商户
    public long amountFen;     // 金额（分）
    public int type;           // 1=支出 2=收入
    public String raw;         // 原始文本（回溯/审计）
    public String category;    // 分类
    public String note;        // 条目/商品：买了什么
    public int confidence;     // 置信度 0-100
    /**
     * 待确认标记：1 = 已入账但置信度中等（50~79），账本里浅色底提示用户复核。
     * 旧逻辑对低置信度直接丢弃，用户完全不知道漏了什么；
     * 现在「中等可信」的照样入账，但明确标注出来。
     */
    public int pending;

    public Bill(long ts, String platform, String merchant, long amountFen, int type,
                String raw, String category, String note, int confidence) {
        this.ts = ts;
        this.platform = platform;
        this.merchant = merchant;
        this.amountFen = amountFen;
        this.type = type;
        this.raw = raw;
        this.category = category;
        this.note = note;
        this.confidence = confidence;
    }

    /**
     * 去重键：收支 + 金额 + 原始文本摘要
     *  - 不含时间戳：同一条通知会被反复扫到
     *  - 不含平台：同一笔可能先被通知通道、再被通知栏通道抓到
     *  - 手动记账用分钟粒度时间，避免同额不同笔被吞
     */
    public String dedupKey() {
        String body = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (body.length() > 60) body = body.substring(0, 60);
        if ("手动记账".equals(body) || body.isEmpty()) {
            return type + "|" + amountFen + "|manual|" + (ts / 60000L);
        }
        return type + "|" + amountFen + "|" + body.hashCode();
    }

    public String amountYuan() {
        boolean neg = amountFen < 0;
        long abs = Math.abs(amountFen);
        long yuan = abs / 100;
        long fen = abs % 100;
        String s = fen == 0 ? String.valueOf(yuan) : String.format("%d.%02d", yuan, fen);
        return (neg ? "-" : "") + s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bill)) return false;
        return Objects.equals(dedupKey(), ((Bill) o).dedupKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(dedupKey());
    }
}
