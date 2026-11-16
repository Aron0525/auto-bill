package com.aron.autobill;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.aron.autobill.core.NotifLog;
import com.aron.autobill.core.Prefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 通知监控面板 —— 把「到底有没有在收通知」变成看得见的事实。
 *
 * ==================== 为什么值得单独做一页 ====================
 *
 * 用户反馈「不能在消息栏接收微信/支付宝的消息」。这类问题最麻烦的地方是
 * **三种完全不同的故障，在用户眼里长得一模一样**：
 *
 *   1. 通知使用权没开       → 服务根本没绑上
 *   2. 权限开了但服务被杀   → 绑上过，但现在是死的
 *   3. 通知收到了但被过滤   → 服务活着，只是判定逻辑没认出它
 *
 * 三种都表现为「没记账」。如果只是笼统提示「请检查权限」，
 * 用户关掉权限页回到 App 一看还是没记账，就只能放弃。
 *
 * 所以这一页要做的事：把三种情况分别指出来，并给出对应的动作。
 *
 * ==================== 检查项 ====================
 *
 *   · 通知使用权      —— 系统是否允许读取通知
 *   · 监听服务        —— 此刻是否真的绑着（这是最关键的实时状态）
 *   · 常驻保活        —— 前台服务是否在跑
 *   · 自动记账开关    —— 用户自己是否关了
 *   · 收件情况        —— 收到多少、收下多少、过滤多少
 *
 * 外加一个「发一条模拟通知」的自检按钮：它走真实的通知链路，
 * 能一次性验证「服务存活 + 解析可用 + 入账成功」三件事。
 */
public class NotifMonitorActivity extends AppCompatActivity {

    private TextView tvHealth, tvStats, tvEmpty, btnFix, btnAutoStart;
    private LinearLayout llChecks, llEntries;

    private final SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notif_monitor);

        tvHealth = findViewById(R.id.tv_health);
        tvStats = findViewById(R.id.tv_stats);
        tvEmpty = findViewById(R.id.tv_empty);
        btnFix = findViewById(R.id.btn_fix);
        btnAutoStart = findViewById(R.id.btn_auto_start);
        llChecks = findViewById(R.id.ll_checks);
        llEntries = findViewById(R.id.ll_entries);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            reload();
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_selftest).setOnClickListener(v -> fireTestNotification());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            NotifLog.clearList();
            reload();
        });

        btnFix.setOnClickListener(v -> openFixTarget());
        btnAutoStart.setOnClickListener(v -> openAutoStart());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从权限页回来时会重新走这里 —— 保证看到的是最新状态，不用手动刷
        KeepAliveService.start(this);
        reload();
        // 如果监听还没连上，尝试拉一下。
        // 真实场景：用户在系统设置里刚勾完「通知使用权」返回，
        // 系统不一定立刻绑定服务；主动触发一次能省掉用户的困惑。
        if (!listenerAlive() && notifAccessGranted()) {
            rebindListener();
            tvHealth.postDelayed(this::reload, 2000);
        }
    }

    // ==================== 刷新 ====================

    private void reload() {
        long now = System.currentTimeMillis();
        String want = firstProblem();

        // ---- 顶部结论 ----
        tvHealth.setText(NotifLog.healthLine(now));

        // ---- 统计行 ----
        StringBuilder sb = new StringBuilder();
        sb.append("收到 ").append(NotifLog.total())
                .append(" 条 · 收下 ").append(NotifLog.accepted())
                .append(" · 过滤 ").append(NotifLog.filtered());
        if (NotifLog.started()) {
            long sec = Math.max(0, (now - NotifLog.startAt()) / 1000);
            sb.append(" · 已运行 ").append(fmtDur(sec));
        }
        if (NotifLog.lastAt() > 0) {
            sb.append("\n最后一条：").append(hms.format(new Date(NotifLog.lastAt())));
            String lp = NotifLog.lastPkg();
            if (!lp.isEmpty()) sb.append("  来自 ").append(friendlyPkg(lp));
        }
        tvStats.setText(sb.toString());

        // ---- 逐项检查 ----
        llChecks.removeAllViews();
        checkRow("通知使用权", notifAccessGranted(),
                notifAccessGranted() ? "已开启" : "未开启（服务无法启动）");
        checkRow("监听服务", listenerAlive(),
                listenerAlive() ? "在岗" : "未连接（收不到任何通知）");
        checkRow("后台自启", autoStartAllowed(),
                autoStartAllowed() ? "已允许" : "被系统禁止（重启后收不到）");
        checkRow("常驻保活", Prefs.autoEnabled(this) ? "已请求" : "未启用",
                Prefs.autoEnabled(this) ? "常驻通知栏，降低被系统清理的概率" : "自动记账已关闭");
        checkRow("自动记账", Prefs.autoEnabled(this),
                Prefs.autoEnabled(this) ? "开启" : "已关闭（不解析任何通知）");

        // ---- 自启动修复入口：只在被禁时显示 ----
        if (!autoStartAllowed()) {
            btnAutoStart.setVisibility(View.VISIBLE);
        } else {
            btnAutoStart.setVisibility(View.GONE);
        }

        // ---- 修复按钮 ----
        if (want != null) {
            btnFix.setVisibility(View.VISIBLE);
            btnFix.setText(want);
        } else {
            btnFix.setVisibility(View.GONE);
        }

        // ---- 通知记录 ----
        llEntries.removeAllViews();
        List<NotifLog.Entry> list = NotifLog.recent();
        if (list.isEmpty()) {
            llEntries.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        llEntries.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        for (NotifLog.Entry e : list) {
            llEntries.addView(entryView(e));
        }
    }

    /** 一行检查项：左边名称，右边状态（带颜色） */
    private void checkRow(String name, boolean ok, String detail) {
        checkRow(name, ok ? "✓" : "✗", detail, ok);
    }

    private void checkRow(String name, String state, String detail) {
        checkRow(name, state, detail, false);
    }

    private void checkRow(String name, String state, String detail, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(14);
        n.setTextColor(getColor(R.color.ink));
        n.setMinWidth(dp(84));
        row.addView(n);

        TextView s = new TextView(this);
        s.setText(state);
        s.setTextSize(13);
        s.setTypeface(Typeface.DEFAULT_BOLD);
        s.setTextColor(getColor(ok ? R.color.primary : R.color.warn));
        row.addView(s);

        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextSize(12);
        d.setTextColor(getColor(R.color.ink_sub));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(8);
        d.setLayoutParams(lp);
        row.addView(d);

        llChecks.addView(row);
    }

    /** 一条通知记录 */
    private View entryView(NotifLog.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        // 第一行：时间 + 判定 + 包名
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText(hms.format(new Date(e.time)));
        t.setTextSize(11);
        t.setTextColor(getColor(R.color.ink_sub));
        head.addView(t);

        TextView v = new TextView(this);
        v.setText("  " + NotifLog.verdictName(e.verdict) + "  ");
        v.setTextSize(11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        int color;
        switch (e.verdict) {
            case NotifLog.ACCEPT: color = R.color.primary; break;
            case NotifLog.FILTER: color = R.color.warn; break;
            default:              color = R.color.ink_sub; break;
        }
        v.setTextColor(getColor(color));
        head.addView(v);

        TextView p = new TextView(this);
        p.setText(friendlyPkg(e.pkg));
        p.setTextSize(11);
        p.setTextColor(getColor(R.color.ink_sub));
        head.addView(p);

        box.addView(head);

        // 第二行：内容摘要
        TextView body = new TextView(this);
        body.setText(e.summary());
        body.setTextSize(13);
        body.setTextColor(getColor(R.color.ink));
        body.setPadding(0, dp(3), 0, 0);
        box.addView(body);

        // 第三行：判定原因 —— 这是排查的关键信息
        if (!e.reason.isEmpty()) {
            TextView r = new TextView(this);
            r.setText(e.reason);
            r.setTextSize(11);
            r.setTextColor(getColor(R.color.ink_sub));
            r.setPadding(0, dp(2), 0, 0);
            box.addView(r);
        }

        return box;
    }

    // ==================== 状态探测 ====================

    /** 通知使用权是否已授予 */
    private boolean notifAccessGranted() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (flat == null || flat.isEmpty()) return false;
            ComponentName me = new ComponentName(this, BillNotificationListener.class);
            for (String s : flat.split(":")) {
                if (s == null) continue;
                ComponentName cn = ComponentName.unflattenFromString(s.trim());
                if (me.equals(cn)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 监听服务此刻是否在岗。
     *
     * 判据用 NotifLog.started()：它由 onListenerConnected() 置位，
     * 是系统真正绑定成功时才发生的事件。
     * 这比“权限已授予”准确得多 —— 权限开着但服务被杀，这里是 false。
     */
    private boolean listenerAlive() {
        return NotifLog.started();
    }

    /** 返回第一个需要修的问题的按钮文案；全好则返回 null */
    private String firstProblem() {
        if (!Prefs.autoEnabled(this)) return "开启自动记账";
        if (!notifAccessGranted()) return "去开通知使用权";
        if (!listenerAlive()) return "重新绑定监听";
        return null;
    }

    /**
     * MIUI 自启动是否已允许。
     *
     * 判断依据是 RUN_ANY_IN_BACKGROUND 这个 appop：
     * 实测 MIUI 在「自启动」里关掉某 App 时，会把它设成 ignore；
     * 打开时是 allow。系统本身不暴露「自启动」的查询接口，
     * 这个 appop 是唯一可靠的间接信号。
     *
     * 查不到（其他品牌）时返回 true —— 宁可少提示，也不要误报吓人。
     */
    private boolean autoStartAllowed() {
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return true;
            // 用字面量而不是 OPSTR_* 常量：那个常量在低版本 API 上不存在，
            // 而 appop 的名字在各版本是稳定的字符串。
            // checkOpNoThrow(int, int, String) 在所有 API 级别都存在，最稳。
            int mode = aom.checkOpNoThrow(
                    "RUN_ANY_IN_BACKGROUND",
                    android.os.Process.myUid(), getPackageName());
            // MODE_IGNORED = 1, MODE_DEFAULT = 0（默认即允许）
            return mode != 1;
        } catch (Throwable t) {
            return true;
        }
    }

    private void openFixTarget() {
        if (!Prefs.autoEnabled(this)) {
            Prefs.setAutoEnabled(this, true);
            KeepAliveService.start(this);
            reload();
            Toast.makeText(this, "已开启自动记账", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!notifAccessGranted()) {
            // 跳系统的通知使用权列表，用户自己勾选本 App
            try {
                startActivity(new Intent(
                        "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                return;
            } catch (Throwable ignored) {}
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {}
            return;
        }
        // 权限在、服务没连上：先请求系统重绑，2 秒后检查结果
        rebindListener();
        KeepAliveService.start(this);
        Toast.makeText(this, "已请求重新绑定…", Toast.LENGTH_SHORT).show();
        tvHealth.postDelayed(() -> {
            reload();
            if (!listenerAlive()) {
                // requestRebind 对「从未绑上过」的服务无效（官方语义如此）。
                // 真机已验证有效的兜底：让用户在系统设置里把使用权
                // 关掉再打开 —— toggle 会立即触发系统重新绑定。
                promptToggleAccess();
            }
        }, 2000);
    }

    /** requestRebind 无效时的兜底引导：跳系统设置让用户 toggle 一下 */
    private void promptToggleAccess() {
        new AlertDialog.Builder(this)
                .setTitle("还需要一步")
                .setMessage("自动重绑没有生效（服务此前从未成功连接过）。"
                        + "\n\n请在接下来打开的页面里，找到「自动记账」，"
                        + "把开关先关闭再打开 —— 这会让系统立刻重新绑定监听服务。")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 重新绑定监听服务。
     *
     * 排查结论（详见 NotifBinder）：MIUI 把带监听管理方法的接口拆进了
     * services.jar（只有系统进程能加载），App 内反射调 binder 不可行。
     * 所以这里只用官方的 requestRebind；它无效时靠上面的 promptToggleAccess
     * 引导用户 toggle —— toggle 已真机验证会立即触发系统绑定。
     */
    private void rebindListener() {
        com.aron.autobill.core.NotifBinder.rebind(this, BillNotificationListener.class);
    }

    private void openAutoStart() {
        Intent[] candidates = {
                new Intent("miui.intent.action.OP_AUTO_START")
                        .putExtra("package_name", getPackageName())
                        .putExtra("package_label", "自动记账"),
                new Intent("miui.intent.action.MIUI_PERM_AUTO_START")
                        .putExtra("package_name", getPackageName()),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", getPackageName(), null)),
        };
        for (Intent i : candidates) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "请允许「自动记账」自启动，否则重启后收不到通知",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "请手动到 应用管理 → 自动记账 → 自启动 打开", Toast.LENGTH_LONG).show();
    }

    // ==================== 自检 ====================

    /**
     * 发一条模拟支付通知。
     *
     * 注意它是**真的发到通知栏**，走系统通知链路，再由我们自己的监听服务收回来。
     * 所以它验证的是完整链路，不是打桩。
     *
     * 包里刻意写「支付成功」这种真实措辞，确保能过语义预筛 ——
     * 如果写「测试通知」，会被判无支付语义而过滤掉，
     * 那样即使链路正常也会显示「过滤」，反而误导。
     */
    private void fireTestNotification() {
        try {
            // 直接调用接收器的逻辑，不走广播。
            // 原因：MIUI 对 App 内发广播也可能拦截（实测点击无反应），
            // 而这个自检的核心诉求是「验证通知链路通不通」，
            // 用不可靠的通道去验证链路本身就成了本末倒置。
            MainActivity.NotifSelftestReceiver.post(this);
            Toast.makeText(this, "已发出，2 秒后刷新查看", Toast.LENGTH_SHORT).show();
            // 给它一点时间走完链路再刷新
            tvHealth.postDelayed(this::reload, 2500);
        } catch (Throwable t) {
            android.util.Log.e("AutoBillSelftest", "发送失败", t);
            Toast.makeText(this, "发送失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 小工具 ====================

    private static String friendlyPkg(String pkg) {
        if (pkg == null) return "";
        switch (pkg) {
            case "com.tencent.mm": return "微信";
            case "com.eg.android.AlipayGphone": return "支付宝";
            case "com.android.shell": return "系统自检";
            case "com.android.systemui": return "系统界面";
            default: return pkg;
        }
    }

    private static String fmtDur(long sec) {
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分钟";
        return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
