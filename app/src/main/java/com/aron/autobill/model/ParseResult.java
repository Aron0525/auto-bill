package com.aron.autobill.model;

/** 解析结果 */
public class ParseResult {
    public boolean ok;
    public long amountFen;      // 金额（分）
    public String rawAmount;    // 原始金额字符串
    public int type;            // 1=支出 2=收入
    public String merchant;     // 商户
    public String note;         // 条目/商品（买了什么）
    public int confidence;      // 置信度 0-100
    public String line;         // 候选来源行（多金额候选时用于分类/展示，可空）
    public String channel;      // 付款渠道：余额宝/花呗/信用卡(尾号1234)/微信支付…
    public String scene;        // 交易场景：基金申购/赎回/消费/退款…
}
