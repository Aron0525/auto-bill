package com.aron.autobill.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OCR 识别出的一行文字 + 它在图上的包围盒。
 *
 * 为什么排序必须在这里做：
 * ML Kit 的 getTextBlocks() 返回顺序不是视觉顺序 —— 账单页里「左列标签」和
 * 「右列金额」经常被切成两个 TextBlock，直接遍历得到的是
 *     配送费 / 打包费 / 商品小计 / 实付 / 4.00 / 2.00 / 47.20 / 39.20
 * 这种「标签一坨、数字一坨」的顺序。而解析规则（实付的下一行是金额、
 * 金额的上一行是店名）全靠版式关系，顺序错了整页全错。
 *
 * sortVisual() 按包围盒把行恢复成眼睛看到的顺序：
 *   1) 按行顶 y 升序；
 *   2) y 区间与「当前视觉行」重叠的归入同一行，行内按 left 升序
 *      （标签在左、金额在右，正好成对相邻）。
 *
 * 本类不依赖 Android API，自检可直接喂真机导出的行序列验证。
 */
public class OcrLine {

    public final String text;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public OcrLine(String text, int left, int top, int right, int bottom) {
        this.text = text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerY() { return (top + bottom) / 2; }

    /** 就地排序成视觉顺序（上→下，同一行左→右） */
    public static void sortVisual(List<OcrLine> lines) {
        if (lines == null || lines.size() < 2) return;

        // 1) 先按行顶升序
        lines.sort(Comparator.comparingInt(o -> o.top));

        // 2) 分视觉行：与当前行 y 区间（向上下各放宽半个行高）重叠 → 同行；
        //    行内按 left 排。分组只看「行首元素」的区间，避免一条 bbox 异常的
        //    长行把后面所有行连锁吞掉。
        List<OcrLine> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            OcrLine head = lines.get(i);
            int h = Math.max(1, head.bottom - head.top);
            int bandTop = head.top - h / 2;
            int bandBottom = head.bottom + h / 2;

            int j = i + 1;
            while (j < lines.size()) {
                OcrLine c = lines.get(j);
                int cy = c.centerY();
                if (cy >= bandTop && cy <= bandBottom) j++;
                else break;
            }
            if (j - i > 1) {
                List<OcrLine> row = new ArrayList<>(lines.subList(i, j));
                row.sort(Comparator.comparingInt(o -> o.left));
                out.addAll(row);
            } else {
                out.add(head);
            }
            i = j;
        }

        lines.clear();
        lines.addAll(out);
    }

    /** 排序后的纯文本（行以 \n 连接），用于全文匹配类的解析 */
    public static String joinText(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (OcrLine l : lines) {
            if (l == null || l.text == null || l.text.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.text);
        }
        return sb.toString();
    }
}
