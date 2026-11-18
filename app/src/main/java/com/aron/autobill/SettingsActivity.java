package com.aron.autobill;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Prefs;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 设置页：自动记账开关、提醒开关、无障碍跳转、清空账本、关于 */
public class SettingsActivity extends AppCompatActivity {

    private BillStore store;
    private MaterialSwitch swAuto, swNotify;
    private TextView tvA11y;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new BillStore(this);

        swAuto = findViewById(R.id.sw_auto);
        swNotify = findViewById(R.id.sw_notify);
        tvA11y = findViewById(R.id.tv_a11y);

        swAuto.setChecked(Prefs.autoEnabled(this));
        swNotify.setChecked(Prefs.notifyEnabled(this));

        swAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setAutoEnabled(this, isChecked);
            // 开关不只是个标记：关掉就把常驻服务停掉，打开就拉回来，
            // 否则用户以为关了，通知栏还挂着一条「自动记账运行中」。
            if (isChecked) KeepAliveService.start(this);
            else KeepAliveService.stop(this);
            Toast.makeText(this, isChecked ? "自动记账已开启" : "自动记账已关闭",
                    Toast.LENGTH_SHORT).show();
        });
        swNotify.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setNotifyEnabled(this, isChecked));

        findViewById(R.id.card_a11y).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });

        // 远程更新
        findViewById(R.id.card_update).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { com.aron.autobill.ui.UpdateFlow.show(SettingsActivity.this); }
        });

        // ---- 待确认队列入口 ----
        findViewById(R.id.card_pending).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, PendingActivity.class));
            }
        });

        // ---- 应用读屏权限 ----
        findViewById(R.id.card_apps).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AppScreenActivity.class));
            }
        });

        // ---- 通知使用权（主数据源）----
        findViewById(R.id.card_notif_access).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openNotificationAccess();
            }
        });
        TextView tvSub = findViewById(R.id.tv_update_sub);
        String savedUrl = com.aron.autobill.core.Prefs.updateUrl(this);
        tvSub.setText("当前版本 " + com.aron.autobill.ui.UpdateFlow.localName(this)
                + " (" + com.aron.autobill.ui.UpdateFlow.localCode(this) + ")"
                + (savedUrl.isEmpty() ? "，尚未配置更新地址" : "\n更新地址：" + savedUrl));
        TextView tvAbout = findViewById(R.id.tv_about);
        tvAbout.setText("自动记账 v" + com.aron.autobill.ui.UpdateFlow.localName(this)
                + "\n所有数据仅存本机 SQLite，不联网、不上传；截图识别（OCR）亦为本地推理。"
                + "无障碍权限仅用于读取支付相关通知与页面文字。"
                + "\n「检查更新」是唯一的联网入口，仅按你配置的地址拉取版本信息。");
    }

    /** 待确认数量角标 */
    private void refreshPendingBadge() {
        com.aron.autobill.core.PendingStore ps = new com.aron.autobill.core.PendingStore(this);
        int n = 0;
        try { n = ps.count(); } catch (Throwable ignored) {}
        ps.close();

        TextView badge = findViewById(R.id.tv_pending_badge);
        TextView hint = findViewById(R.id.tv_pending_hint);
        if (n > 0) {
            badge.setVisibility(View.VISIBLE);
            badge.setText(String.valueOf(n));
            hint.setText("有 " + n + " 笔拿不准的交易等你确认");
        } else {
            badge.setVisibility(View.GONE);
            hint.setText("识别不准的交易会放在这里，确认后才入账");
        }
    }

    /** 通知使用权是否已授权 */
    private void refreshNotifAccess() {
        boolean on = isNotificationAccessGranted();
        TextView state = findViewById(R.id.tv_notif_state);
        TextView hint = findViewById(R.id.tv_notif_hint);
        if (on) {
            state.setText("已开启");
            state.setTextColor(ContextCompat.getColor(this, R.color.brand_teal));
            hint.setText("已能读取完整通知正文，识别准确率显著高于只靠无障碍");
        } else {
            state.setText("未开启");
            state.setTextColor(ContextCompat.getColor(this, R.color.warn));
            hint.setText("开启后能读到完整通知正文，识别准确率显著高于只靠无障碍");
        }
    }

    /**
     * 通知使用权（NotificationListener）检测。
     * 判定逻辑收在 NotifBinder.enabled —— 主页状态行与保活通知也用同一份，
     * 三处各写一遍迟早会不一致（一个说已开启、另一个说没有）。
     */
    private boolean isNotificationAccessGranted() {
        return com.aron.autobill.core.NotifBinder.enabled(this);
    }

    private void openNotificationAccess() {
        if (isNotificationAccessGranted()) {
            Toast.makeText(this, "通知使用权已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        // 标准 action 优先；MIUI 有自己的页面，失败再逐级回退
        Intent[] candidates = {
                new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this,
                        "请在列表里找到「自动记账」并开启通知使用权",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "无法打开通知使用权设置，请手动到 设置→应用→特殊权限", Toast.LENGTH_LONG).show();
    }

    private void confirmClear() {        int n = store.count();
        if (n == 0) {
            Toast.makeText(this, "账本已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空全部账单？")
                .setMessage("将删除 " + n + " 条账目记录，不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    store.clearAll();
                    Toast.makeText(this, "已清空 " + n + " 条记录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = isAccessibilityEnabled();
        tvA11y.setText(enabled ? "无障碍服务：已开启 ✓" : "无障碍服务：未开启 ✗  点此前往开启");
        tvA11y.setTextColor(ContextCompat.getColor(this,
                enabled ? R.color.income : R.color.expense));
        refreshPendingBadge();
        refreshNotifAccess();
        refreshAppsHint();
    }

    /** 应用读屏权限摘要：让用户一眼看到当前有多少 App 被限制 */
    private void refreshAppsHint() {
        TextView tv = findViewById(R.id.tv_apps_hint);
        if (tv == null) return;
        int custom = Prefs.customScreenCount(this);
        int off = 0, cautious = 0;
        for (String p : com.aron.autobill.core.ScreenPolicy.knownPackages()) {
            int lv = Prefs.screenLevel(this, p);
            if (lv < 0) lv = com.aron.autobill.core.ScreenPolicy.defaultLevel(p);
            if (lv == com.aron.autobill.core.ScreenPolicy.OFF) off++;
            else if (lv == com.aron.autobill.core.ScreenPolicy.CAUTIOUS) cautious++;
        }
        tv.setText(cautious + " 个需结账凭证， " + off + " 个不读屏"
                + (custom > 0 ? "（" + custom + " 项自定义）" : "")
                + "；刷商品被误记就关掉它");
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }
}
