package com.aron.autobill;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.Prefs;
import com.aron.autobill.core.ScreenPolicy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用读屏权限设置。
 *
 * 解决的问题（用户原话）：
 *   「现在他无法分辨我是在浏览商品还是已经付款了，刷一会拼多多全是账单」
 *
 * 根因：读屏通道每 800ms 抓一次整屏文本，商品列表页每屏几十个「¥9.9」，
 * 全被当成账单。这不是解析器不准，是**数据源本身对浏览页无效**。
 *
 * 这个页面给用户最终裁决权：每个 App 三档
 *   · 信任    —— 屏幕文字直接记账（微信/支付宝付款页是结账结论）
 *   · 需凭证  —— 必须看到「支付成功/实付款/支付方式」才记账（购物类默认）
 *   · 不读屏  —— 完全不用这个 App 的屏幕文字（通知通道不受影响）
 *
 * 为什么要做成可见的列表而不是写死规则：
 *   每个人的使用习惯不同。有人只用支付宝付钱，那所有购物 App 关掉读屏最省心；
 *   有人习惯在小程序里付款，那就得留着。这种偏好只能问用户，猜不出来。
 */
public class AppScreenActivity extends AppCompatActivity {

    private LinearLayout list;
    private TextView tvSummary;
    private final List<String> pkgs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_screen);

        list = findViewById(R.id.ll_app_list);
        tvSummary = findViewById(R.id.tv_app_summary);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_reset_apps).setOnClickListener(v -> confirmReset());

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        pkgs.clear();
        pkgs.addAll(ScreenPolicy.knownPackages());

        list.removeAllViews();

        int trusted = 0, cautious = 0, off = 0;
        for (String p : pkgs) {
            int lv = Prefs.screenLevel(this, p);
            if (lv < 0) lv = ScreenPolicy.defaultLevel(p);
            if (lv == ScreenPolicy.TRUSTED) trusted++;
            else if (lv == ScreenPolicy.CAUTIOUS) cautious++;
            else off++;
        }
        tvSummary.setText("信任 " + trusted + " · 需凭证 " + cautious + " · 不读屏 " + off
                + "，点任意一项修改");

        // 分组展示：先信任，再需凭证，再不读屏
        addGroup("完全信任（读屏直接记账）", ScreenPolicy.TRUSTED);
        addGroup("需要结账凭证（只会误记在浏览页）", ScreenPolicy.CAUTIOUS);
        addGroup("不读屏", ScreenPolicy.OFF);
    }

    private void addGroup(String title, int level) {
        List<String> group = new ArrayList<>();
        for (String p : pkgs) {
            int lv = Prefs.screenLevel(this, p);
            if (lv < 0) lv = ScreenPolicy.defaultLevel(p);
            if (lv == level) group.add(p);
        }
        if (group.isEmpty()) return;

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(12);
        h.setTextColor(ContextCompat.getColor(this, R.color.ink_sub));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(16);
        hp.bottomMargin = dp(6);
        hp.leftMargin = dp(4);
        h.setLayoutParams(hp);
        list.addView(h);

        for (String p : group) {
            list.addView(buildRow(p, level));
        }
    }

    private View buildRow(final String pkg, int level) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(1);
        row.setLayoutParams(rp);
        row.setBackgroundColor(ContextCompat.getColor(this, R.color.card));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String name = ScreenPolicy.label(pkg);
        TextView tvName = new TextView(this);
        tvName.setText(name.isEmpty() ? pkg : name);
        tvName.setTextSize(15);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.ink));
        texts.addView(tvName);

        TextView tvSub = new TextView(this);
        boolean custom = Prefs.screenLevel(this, pkg) >= 0;
        tvSub.setText(ScreenPolicy.levelName(level)
                + (custom ? " · 已自定义" : " · 默认")
                + " · " + pkg);
        tvSub.setTextSize(10);
        tvSub.setTextColor(ContextCompat.getColor(this, R.color.ink_sub));
        tvSub.setPadding(0, dp(2), 0, 0);
        texts.addView(tvSub);

        row.addView(texts);

        TextView tvLv = new TextView(this);
        tvLv.setText(ScreenPolicy.levelName(level));
        tvLv.setTextSize(13);
        tvLv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvLv.setTextColor(ContextCompat.getColor(this,
                level == ScreenPolicy.TRUSTED ? R.color.income
                        : level == ScreenPolicy.CAUTIOUS ? R.color.warn : R.color.expense));
        row.addView(tvLv);

        row.setOnClickListener(v -> pickLevel(pkg, name.isEmpty() ? pkg : name));
        return row;
    }

    /** 三档选择 —— 用「单选列表 + 说明」而不是循环开关，避免用户不知道当前在哪一档 */
    private void pickLevel(final String pkg, String display) {
        final int[] levels = {ScreenPolicy.TRUSTED, ScreenPolicy.CAUTIOUS, ScreenPolicy.OFF};
        final String[] labels = {
                "信任 —— 屏幕文字直接记账",
                "需要结账凭证 —— 只认「支付成功/实付款」",
                "不读屏 —— 只用这个 App 的通知",
        };
        int cur = Prefs.screenLevel(this, pkg);
        if (cur < 0) cur = ScreenPolicy.defaultLevel(pkg);
        int checked = 0;
        for (int i = 0; i < levels.length; i++) if (levels[i] == cur) checked = i;

        new MaterialAlertDialogBuilder(this)
                .setTitle(display)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Prefs.setScreenLevel(this, pkg, levels[which]);
                    d.dismiss();
                    reload();
                    Toast.makeText(this,
                            display + " → " + ScreenPolicy.levelName(levels[which]),
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    Prefs.setScreenLevel(this, pkg, -1);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        int n = Prefs.customScreenCount(this);
        if (n == 0) {
            Toast.makeText(this, "全部都是默认设置", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("恢复全部默认？")
                .setMessage("将清除 " + n + " 个自定义设置。")
                .setPositiveButton("恢复", (d, w) -> {
                    Prefs.clearScreenLevels(this);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
