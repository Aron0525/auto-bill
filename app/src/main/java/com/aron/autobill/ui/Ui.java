package com.aron.autobill.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/** 界面小工具：dp 转换、圆角背景、主题色（小米钱包风格） */
public class Ui {

    public static final int BG = 0xFFF2F3F5;        // 页面底色
    public static final int CARD = 0xFFFFFFFF;      // 卡片
    public static final int TEXT = 0xFF222222;      // 主文字
    public static final int SUB = 0xFF8A8A8A;       // 次要文字
    public static final int LINE = 0xFFEDEDED;      // 分隔线
    public static final int EXPENSE = 0xFFE74C3C;   // 支出红
    public static final int INCOME = 0xFF27AE60;    // 收入绿
    public static final int PRIMARY = 0xFFFF6900;   // 小米橙

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(Context c, int color, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, 1), strokeColor);
        return d;
    }
}
