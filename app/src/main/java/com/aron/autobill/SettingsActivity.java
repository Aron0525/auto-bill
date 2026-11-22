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
    private TextView tvBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new BillStore(this);

        swAuto = findViewById(R.id.sw_auto);
        swNotify = findViewById(R.id.sw_notify);
        tvA11y = findViewById(R.id.tv_a11y);
        tvBackground = findViewById(R.id.tv_background);

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

        findViewById(R.id.card_background).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBackgroundOptions(); }
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
        StringBuilder sb = new StringBuilder();
        if (on) {
            state.setText("已开启");
            state.setTextColor(ContextCompat.getColor(this, R.color.brand_teal));
            if (com.aron.autobill.core.NotifLog.started()) {
                sb.append(com.aron.autobill.core.NotifLog.healthLine(System.currentTimeMillis()));
            } else {
                // 授权是开的但服务没连上：这是最容易让人以为「功能坏了」的状态
                sb.append("已授权，但监听服务还没连上（进程可能刚被系统回收）。")
                  .append("\n点这里可请求重新连接");
            }
            long lastAt = com.aron.autobill.core.NotifLog.lastAt();
            if (lastAt > 0) {
                sb.append("\n最近一条 ").append(new java.text.SimpleDateFormat(
                        "MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date(lastAt)));
            }
        } else {
            state.setText("未开启");
            state.setTextColor(ContextCompat.getColor(this, R.color.warn));
            sb.append("开启后能读到完整通知正文，识别准确率显著高于只靠无障碍");
        }
        hint.setText(sb.toString());
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
            // 已授权但服务没连上：先请求一次重绑（用户最常见的困境就是
            // 权限明明是开的、一条通知也收不到，却不知道能做什么）
            if (!com.aron.autobill.core.NotifLog.started()) {
                com.aron.autobill.core.NotifBinder.rebind(this, BillNotificationListener.class);
                KeepAliveService.start(this);
                Toast.makeText(this, "已请求重新连接，稍等几秒再看主页状态",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "通知使用权已开启，监听正常", Toast.LENGTH_SHORT).show();
            }
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

    /**
     * 后台运行。重启后不记账，九成栽在「自启动被系统拦」这一环：
     * 进程根本起不来，通知监听和无障碍就都跟着失效，App 自己没有任何办法自愈。
     */
    private void showBackgroundOptions() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("让自动记账活过重启")
                .setMessage("系统会在重启后拦截本应用自启动。进程起不来，通知监听和无障碍就一起失效，"
                        + "账本自然一条都不动。下面按顺序设一遍。")
                .setItems(new String[]{
                        "① 自启动（最关键）",
                        "② 省电策略 → 无限制",
                        "③ 电池优化白名单",
                        "④ 系统应用详情页"
                }, (d, which) -> {
                    switch (which) {
                        case 0: openMiuiAutoStart(); break;
                        case 1: openMiuiPower(); break;
                        case 2: openBatteryWhitelist(); break;
                        default: openAppDetail(); break;
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** MIUI 自启动管理页。不同版本路径不一样，逐个试，全失败就退到应用详情 */
    private void openMiuiAutoStart() {
        String[][] targets = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "请把「自动记账」的自启动打开", Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "这台机器没有独立的自启动页，去应用详情里找", Toast.LENGTH_LONG).show();
        openAppDetail();
    }

    /** MIUI 单应用省电策略页 */
    private void openMiuiPower() {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName("com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
            i.putExtra("package_name", getPackageName());
            i.putExtra("package_label", getString(R.string.app_name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "把省电策略改成「无限制」", Toast.LENGTH_LONG).show();
            return;
        } catch (Throwable ignored) {}
        openAppDetail();
    }

    private void openBatteryWhitelist() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "在列表里找到「自动记账」，选「不优化」", Toast.LENGTH_LONG).show();
            return;
        } catch (Throwable ignored) {}
        openAppDetail();
    }

    private void openAppDetail() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开应用详情，请手动到 设置→应用", Toast.LENGTH_LONG).show();
        }
    }

    /** 回前台刷一次：分桶和电池白名单都可能被系统在后台改掉 */
    private void refreshBackground() {
        if (tvBackground == null) return;
        boolean restricted = HealthCheck.standbyRestricted(this);
        boolean battery = HealthCheck.batteryUnrestricted(this);
        if (restricted) {
            tvBackground.setText("后台运行：被系统限制 ✗  点此解除");
            tvBackground.setTextColor(ContextCompat.getColor(this, R.color.expense));
        } else if (!battery) {
            tvBackground.setText("后台运行：未加入电池白名单 ⚠  点此设置");
            tvBackground.setTextColor(ContextCompat.getColor(this, R.color.warn));
        } else {
            tvBackground.setText("后台运行：电池白名单已加 ✓  自启动仍需手动确认");
            tvBackground.setTextColor(ContextCompat.getColor(this, R.color.ink_sub));
        }
    }

    private void confirmClear() {
        int n = store.count();
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
        // 无障碍三态：跑着 / 设置里开着但服务已掉线 / 完全没开。
        // 中间那种是 MIUI 上最常见的，旧版会显示成「已开启」，把人骗过去。
        boolean a11yRunning = HealthCheck.a11yEnabled(this);
        boolean a11ySetOn = HealthCheck.a11ySettingOn(this);
        if (a11yRunning) {
            tvA11y.setText("无障碍服务：已开启 ✓");
        } else if (a11ySetOn) {
            tvA11y.setText("无障碍服务：已掉线 ⚠  设置里开着但服务没在跑，点此关掉再打开一次");
        } else {
            tvA11y.setText("无障碍服务：未开启 ✗  点此前往开启");
        }
        tvA11y.setTextColor(ContextCompat.getColor(this,
                a11yRunning ? R.color.income : R.color.expense));
        refreshPendingBadge();
        refreshNotifAccess();
        refreshAppsHint();
        refreshBackground();
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

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }
}
