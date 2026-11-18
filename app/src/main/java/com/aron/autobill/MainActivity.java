package com.aron.autobill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.Bill;
import com.aron.autobill.ui.BillDetailDialog;
import com.aron.autobill.ui.Charts;
import com.aron.autobill.ui.OcrHelper;
import com.aron.autobill.ui.Ui;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记账主界面（参照设计稿）：
 *  底部导航：记账 | 记一笔 | 报表
 *  记账页：蓝色汇总卡（今日支出/本月支出/本月收入）+ 按日分组账单（图标+来源标签）
 *  报表页：周/月/年汇总 + 12 个月趋势折线 + 分类构成环形图 + 排行
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGE = 5001;

    private BillStore store;

    private LinearLayout contentHome, contentReport;
    private View pageHome, pageReport;
    private TextView navHomeText, navReportText;

    /** 报表 tab：0=周报 1=月报 2=年报 */
    private int reportTab = 1;
    /** 报表方向：1=支出 2=收入 */
    private int reportType = 1;
    /** 报表选中年份（默认当前年） */
    private int selectedYear = Calendar.getInstance().get(Calendar.YEAR);

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (contentHome != null) reload();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new BillStore(this);

        contentHome = findViewById(R.id.content_home);
        contentReport = findViewById(R.id.content_report);
        pageHome = findViewById(R.id.page_home);
        pageReport = findViewById(R.id.page_report);
        navHomeText = findViewById(R.id.nav_home_text);
        navReportText = findViewById(R.id.nav_report_text);

        findViewById(R.id.nav_home).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(true); }
        });
        findViewById(R.id.nav_report).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(false); }
        });
        View.OnClickListener openAdd = new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AddBillActivity.class));
            }
        };
        findViewById(R.id.nav_add).setOnClickListener(openAdd);
        findViewById(R.id.btn_add).setOnClickListener(openAdd);

        IntentFilter f = new IntentFilter("com.aron.autobill.BILL_ADDED");
        registerReceiver(refreshReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        ensureNotificationPermission();
        // 每次打开 App 都确保保活服务在岗：MIUI 上服务被清后不会自愈，
        // 这是最便宜的一次补救（幂等，重复调用无副作用）。
        KeepAliveService.start(this);
        reload();
        showPage(true);
    }

    /**
     * Android 13+ 发通知需要运行时授权，否则横幅永远弹不出来
     * （渠道建得再对也没用——系统会静默丢弃）。
     * 没授权就弹系统框问一次；用户拒了也不纠缠，只在日志里留痕。
     */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9001);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 9001 && results.length > 0
                && results[0] != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("MainActivity", "用户拒绝通知权限，记账横幅将不可见");
        }
    }

    /**
     * 自检入口：发一条「记账成功」横幅，用来验证通知链路是否通。
     * adb shell am broadcast -a com.aron.autobill.TEST_NOTIFY -p com.aron.autobill
     */
    public static class TestNotifyReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new android.app.Notification.Builder(c, "autobill_bill")
                        : new android.app.Notification.Builder(c).setPriority(
                                android.app.Notification.PRIORITY_HIGH);
                b.setSmallIcon(R.drawable.ic_notify_bill)
                        .setContentTitle("支出 ¥36.50")
                        .setContentText("华莱士 · 餐饮")
                        .setSubText("支出")
                        .setColor(0xFFE74C3C)
                        .setStyle(new android.app.Notification.BigTextStyle()
                                .bigText("华莱士 · 餐饮\n条目：香辣鸡腿堡\n来源：微信支付 · 12:30\n识别度 100% · 已记入账本"))
                        .setAutoCancel(true);
                nm.notify(9999, b.build());
            } catch (Throwable t) {
                android.util.Log.w("MainActivity", "测试通知失败: " + t);
            }
        }
    }

    private void showPage(boolean home) {
        pageHome.setVisibility(home ? View.VISIBLE : View.GONE);
        pageReport.setVisibility(home ? View.GONE : View.VISIBLE);
        navHomeText.setTextColor(col(home ? R.color.primary : R.color.ink_sub));
        navReportText.setTextColor(col(!home ? R.color.primary : R.color.ink_sub));
        if (home) reloadHome(); else reloadReport();
    }

    private void reload() {
        reloadHome();
        reloadReport();
    }

    // ============================================================
    // 记账页
    // ============================================================

    private void reloadHome() {
        contentHome.removeAllViews();

        // ---- 顶栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(14), dp(18), dp(6));
        TextView title = new TextView(this);
        title.setText("记账");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(col(R.color.ink));
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView ocr = new TextView(this);
        ocr.setText("识图");
        ocr.setTextSize(13);
        ocr.setTypeface(null, Typeface.BOLD);
        ocr.setTextColor(col(R.color.primary));
        ocr.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_chip_ocr));
        ocr.setPadding(dp(12), dp(4), dp(12), dp(4));
        ocr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });
        top.addView(ocr);
        TextView setting = new TextView(this);
        setting.setText("⚙");
        setting.setTextSize(18);
        setting.setPadding(dp(14), 0, 0, 0);
        setting.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        top.addView(setting);
        contentHome.addView(top);

        // ---- 蓝色汇总卡 ----
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setBackground(Ui.round(this, col(R.color.primary), 20));
        summary.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.setMargins(dp(14), dp(6), dp(14), 0);
        contentHome.addView(summary, sumLp);

        long dayStart = dayStart();
        long[] today = store.sumByType(dayStart);
        long[] month = store.sumByType(monthStart());

        addLabel(summary, "今日支出（元）  👁", 0xCCFFFFFF, 12);
        TextView big = new TextView(this);
        big.setText(fmt(today[0]));
        big.setTextSize(34);
        big.setTypeface(null, Typeface.BOLD);
        big.setTextColor(Color.WHITE);
        big.setPadding(0, dp(2), 0, dp(10));
        summary.addView(big);

        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        summary.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(0.8f)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);
        summary.addView(row);
        row.addView(summaryCol("本月支出（元）", fmt(month[0])), weightLp());
        row.addView(summaryCol("本月收入（元）", fmt(month[1])), weightLp());

        // ---- 状态行（无障碍）----
        TextView status = new TextView(this);
        boolean enabled = isAccessibilityEnabled();
        status.setText(enabled ? "自动记账生效中 ✓   若没记上：截图分享到本应用补记"
                : "无障碍服务未开启 ✗  点此开启");
        status.setTextSize(11);
        status.setTextColor(col(enabled ? R.color.ink_sub : R.color.expense));
        status.setPadding(dp(18), dp(8), dp(18), dp(4));
        if (!enabled) {
            status.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            });
        }
        contentHome.addView(status);

        // ---- 账单列表 ----
        List<Bill> bills = store.recent(200);
        TextView head = new TextView(this);
        head.setText(bills.isEmpty() ? "账单明细" : "账单明细 · 共 " + bills.size() + " 笔");
        head.setTextSize(14);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(col(R.color.ink));
        head.setPadding(dp(18), dp(14), 0, dp(8));
        contentHome.addView(head);

        if (bills.isEmpty()) {
            MaterialCardView card = whiteCard();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(14), 0, dp(14), 0);
            contentHome.addView(card, lp);
            LinearLayout inner = cardInner();
            card.addView(inner);
            TextView empty = new TextView(this);
            empty.setText("还没有账单\n支付一笔会自动记账；截图分享到本应用也能补记");
            empty.setTextSize(14);
            empty.setTextColor(col(R.color.ink_sub));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(26), 0, dp(26));
            empty.setLineSpacing(dp(4), 1f);
            inner.addView(empty);
            return;
        }

        SimpleDateFormat dayKeyFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        Map<String, List<Bill>> groups = new LinkedHashMap<>();
        for (Bill b : bills) {
            String key = dayKeyFmt.format(new Date(b.ts));
            List<Bill> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(key, list);
            }
            list.add(b);
        }

        for (Map.Entry<String, List<Bill>> e : groups.entrySet()) {
            // 日期头
            long expense = 0, income = 0;
            for (Bill b : e.getValue()) {
                if (b.type == 2) income += b.amountFen; else expense += b.amountFen;
            }
            LinearLayout dh = new LinearLayout(this);
            dh.setOrientation(LinearLayout.HORIZONTAL);
            dh.setPadding(dp(18), dp(12), dp(18), dp(6));
            TextView day = new TextView(this);
            day.setText(dayHeader(e.getKey()));
            day.setTextSize(15);
            day.setTypeface(null, Typeface.BOLD);
            day.setTextColor(col(R.color.ink));
            dh.addView(day, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView sum = new TextView(this);
            sum.setText("支" + fmt(expense) + " 收" + fmt(income));
            sum.setTextSize(12);
            sum.setTextColor(col(R.color.ink_sub));
            dh.addView(sum);
            contentHome.addView(dh);

            // 白卡账单
            MaterialCardView card = whiteCard();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(14), 0, dp(14), dp(4));
            contentHome.addView(card, lp);
            LinearLayout inner = cardInner();
            card.addView(inner);
            List<Bill> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                addBillRow(inner, list.get(i), i == list.size() - 1);
            }
        }
    }

    private void addBillRow(LinearLayout parent, final Bill b, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        // 圆形图标
        TextView icon = new TextView(this);
        icon.setText(Category.iconOf(b.category));
        icon.setTextSize(19);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(this, Category.iconBgOf(b.category), 25));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // 中间
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(11);
        row.addView(mid, mlp);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(b.category);
        name.setTextSize(15);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(col(R.color.ink));
        line1.addView(name);
        String tag = Category.sourceTag(b.platform, b.raw);
        if (tag != null && !tag.isEmpty()) {
            TextView tagView = new TextView(this);
            tagView.setText(" " + tag + " ");
            tagView.setTextSize(10);
            tagView.setTextColor(col(R.color.primary));
            tagView.setBackground(Ui.round(this, col(R.color.primary_container), 6));
            tagView.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(6);
            line1.addView(tagView, tlp);
        }
        mid.addView(line1);

        String merchant = b.merchant == null || b.merchant.isEmpty() ? "" : b.merchant;
        if ("手动记账".equals(merchant)) merchant = "";
        String sub = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(b.ts))
                + (merchant.isEmpty() ? "" : " | " + merchant);
        TextView subView = new TextView(this);
        subView.setText(sub);
        subView.setTextSize(11);
        subView.setTextColor(col(R.color.ink_sub));
        subView.setMaxLines(1);
        subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(subView);

        // 金额
        boolean income = b.type == 2;
        TextView amt = new TextView(this);
        amt.setText((income ? "+" : "-") + b.amountYuan());
        amt.setTextSize(16);
        amt.setTypeface(null, Typeface.BOLD);
        amt.setTextColor(col(income ? R.color.income : R.color.expense));
        row.addView(amt);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new BillDetailDialog(MainActivity.this, store, b.id,
                        new BillDetailDialog.OnChanged() {
                            @Override public void onChanged() { reload(); }
                        }).show();
            }
        });

        parent.addView(row);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(col(R.color.line));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f));
            llp.leftMargin = dp(55);
            parent.addView(line, llp);
        }
    }

    private LinearLayout summaryCol(String label, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(0xCCFFFFFF);
        cell.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(17);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(Color.WHITE);
        v.setPadding(0, dp(2), 0, 0);
        cell.addView(v);
        return cell;
    }

    private void addLabel(LinearLayout parent, String text, int color, float size) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(size);
        l.setTextColor(color);
        parent.addView(l);
    }

    // ============================================================
    // 报表页
    // ============================================================

    private void reloadReport() {
        contentReport.removeAllViews();

        // 顶栏
        TextView title = new TextView(this);
        title.setText("收支报表");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(col(R.color.ink));
        title.setPadding(dp(18), dp(14), dp(18), dp(6));
        contentReport.addView(title);

        // 周/月/年 tab
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(14), 0, dp(14), 0);
        String[] names = {"周报", "月报", "年报"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15);
            t.setTypeface(null, reportTab == i ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(col(reportTab == i ? R.color.primary : R.color.ink_sub));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    reportTab = idx;
                    reloadReport();
                }
            });
            tabs.addView(t, lp);
        }
        contentReport.addView(tabs);

        // 年份 + 支出/收入切换
        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.setGravity(Gravity.CENTER_VERTICAL);
        selRow.setPadding(dp(18), dp(4), dp(18), dp(8));
        TextView year = new TextView(this);
        year.setText(selectedYear + "年 ▼");
        year.setTextSize(15);
        year.setTypeface(null, Typeface.BOLD);
        year.setTextColor(col(R.color.ink));
        year.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseYear(year); }
        });
        selRow.addView(year, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout toggle = new LinearLayout(this);
        toggle.setBackground(Ui.round(this, R.color.card, 20));
        String[] tNames = {"支出", "收入"};
        for (int i = 0; i < 2; i++) {
            final int t = i == 0 ? 1 : 2;
            boolean active = reportType == t;
            TextView b = new TextView(this);
            b.setText(tNames[i]);
            b.setTextSize(13);
            b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            b.setTextColor(active ? 0xFFFFFFFF : col(R.color.ink));
            b.setBackground(active ? Ui.round(this, col(R.color.primary), 18) : null);
            b.setPadding(dp(16), dp(5), dp(16), dp(5));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    reportType = t;
                    reloadReport();
                }
            });
            toggle.addView(b);
        }
        selRow.addView(toggle);
        contentReport.addView(selRow);

        // 统计范围、上期范围与趋势粒度：周报=本周按天；月报=选中年当月按天；年报=选中年按月
        long from, to, prevFrom, prevTo;
        String rangeName, avgLabel;
        int bucket;          // 0=按天 1=按月
        int bucketCount;
        String[] labels;
        if (reportTab == 0) {
            from = weekStart(); to = now() + 60_000L;
            prevFrom = from - 7L * 86400000L; prevTo = from - 1;
            rangeName = "本周"; avgLabel = "日均";
            bucket = 0; bucketCount = 7;
            labels = new String[]{"一", "二", "三", "四", "五", "六", "日"};
        } else if (reportTab == 1) {
            from = yearMonthStart(selectedYear);
            to = monthEndOf(selectedYear);
            prevTo = from - 1;
            Calendar pc = Calendar.getInstance();
            pc.setTimeInMillis(prevTo);
            pc.set(Calendar.DAY_OF_MONTH, 1);
            pc.set(Calendar.HOUR_OF_DAY, 0);
            pc.set(Calendar.MINUTE, 0);
            pc.set(Calendar.SECOND, 0);
            pc.set(Calendar.MILLISECOND, 0);
            prevFrom = pc.getTimeInMillis();
            rangeName = selectedYear + "年本月"; avgLabel = "日均";
            bucket = 0;
            Calendar dc = Calendar.getInstance();
            dc.setTimeInMillis(to);
            bucketCount = dc.getActualMaximum(Calendar.DAY_OF_MONTH);
            labels = new String[bucketCount];
            for (int i = 0; i < bucketCount; i++) labels[i] = String.valueOf(i + 1);
        } else {
            from = yearStartOf(selectedYear);
            to = yearEndOf(selectedYear);
            prevFrom = yearStartOf(selectedYear - 1);
            prevTo = yearEndOf(selectedYear - 1);
            rangeName = selectedYear + "年"; avgLabel = "月均";
            bucket = 1; bucketCount = 12;
            labels = new String[]{"1月", "2月", "3月", "4月", "5月", "6月",
                    "7月", "8月", "9月", "10月", "11月", "12月"};
        }

        long[] sums = store.sumByTypeRange(from, to);
        long[] prevSums = store.sumByTypeRange(prevFrom, prevTo);
        long total = reportType == 1 ? sums[0] : sums[1];
        long prevTotal = reportType == 1 ? prevSums[0] : prevSums[1];
        long balance = sums[1] - sums[0];      // 收入 - 支出
        // 均值：周报/月报按已过天数；年报按已过月数（历史年份按整年 12 月）
        long passedUnits;
        if (bucket == 0) {
            passedUnits = Math.max(1, (Math.min(now(), to) - from) / 86400000L + 1);
        } else {
            passedUnits = selectedYear == Calendar.getInstance().get(Calendar.YEAR)
                    ? Calendar.getInstance().get(Calendar.MONTH) + 1 : 12;
        }
        long avg = total / passedUnits;
        long diff = total - prevTotal;
        String diffStr = diff == 0 ? "0" : (diff > 0 ? "+" : "-") + fmt(Math.abs(diff));
        int diffColor = diff > 0 ? col(R.color.balance_negative)
                : diff < 0 ? col(R.color.income) : col(R.color.ink);

        // 汇总卡（2×2）
        MaterialCardView sumCard = whiteCard();
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumLp.setMargins(dp(14), 0, dp(14), dp(10));
        contentReport.addView(sumCard, sumLp);
        LinearLayout sumInner = cardInner();
        sumCard.addView(sumInner);
        sumInner.addView(cell2x2(rangeName + (reportType == 1 ? "支出" : "收入") + "（元）",
                fmt(total), col(R.color.ink),
                avgLabel + "（元）", fmt(avg), col(R.color.ink),
                "比上期（元）", diffStr, diffColor,
                "收支结余（元）", (balance >= 0 ? "" : "-") + fmt(Math.abs(balance)),
                col(balance >= 0 ? R.color.income : R.color.balance_negative)));

        // 趋势卡：周报=本周 7 天；月报=当月每天；年报=12 个月
        MaterialCardView trendCard = whiteCard();
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        tLp.setMargins(dp(14), 0, dp(14), dp(10));
        contentReport.addView(trendCard, tLp);
        LinearLayout trendInner = cardInner();
        trendCard.addView(trendInner);
        TextView trendTitle = new TextView(this);
        trendTitle.setText(rangeName + (reportType == 1 ? "支出" : "收入") + "趋势");
        trendTitle.setTextSize(13);
        trendTitle.setTextColor(col(R.color.ink_sub));
        trendInner.addView(trendTitle);
        Charts.LineChartView line = new Charts.LineChartView(this);
        float[] values = new float[bucketCount];
        // 每个点按笔数计：同一时间段可能多笔，点击气泡要显示合计 + 笔数
        int[] counts = new int[bucketCount];
        List<Bill> all = store.recent(2000);
        Calendar cal = Calendar.getInstance();
        for (Bill b : all) {
            if (b.type != reportType || b.ts < from || b.ts > to) continue;
            int idx;
            if (bucket == 0) {
                idx = (int) ((b.ts - from) / 86400000L);
            } else {
                cal.setTimeInMillis(b.ts);
                idx = cal.get(Calendar.MONTH);
            }
            if (idx >= 0 && idx < bucketCount) {
                values[idx] += b.amountFen / 100f;
                counts[idx]++;
            }
        }
        // 气泡文字：日期/月份 + 金额 + 笔数
        String[] tips = new String[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            String when = (labels != null && i < labels.length) ? labels[i] : ("第" + (i + 1) + "期");
            if (counts[i] == 0) {
                tips[i] = when + " · 无记录";
            } else {
                tips[i] = when + " · " + yuanText(values[i]) + " 元 · " + counts[i] + " 笔";
            }
        }
        int hi = -1;
        if (bucket == 0) {
            Calendar today = Calendar.getInstance();
            long todayStart = dayStart();
            if (today.getTimeInMillis() >= from && today.getTimeInMillis() <= to) {
                hi = (int) ((dayStart() - from) / 86400000L);
            }
        } else if (selectedYear == Calendar.getInstance().get(Calendar.YEAR)) {
            hi = Calendar.getInstance().get(Calendar.MONTH);
        }
        line.setData(values, labels, hi, bucket == 0 ? (bucketCount > 10 ? 5 : 1) : 2, tips);
        trendInner.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 分类构成
        List<String[]> stats = store.statsByCategory(from, to, reportType);
        if (!stats.isEmpty()) {
            MaterialCardView catCard = whiteCard();
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.setMargins(dp(14), 0, dp(14), dp(10));
            contentReport.addView(catCard, cLp);
            LinearLayout catInner = cardInner();
            catCard.addView(catInner);

            TextView catTitle = new TextView(this);
            catTitle.setText((reportType == 1 ? "支出" : "收入") + "分类构成");
            catTitle.setTextSize(15);
            catTitle.setTypeface(null, Typeface.BOLD);
            catTitle.setTextColor(col(R.color.ink));
            catInner.addView(catTitle);

            Charts.PieView pie = new Charts.PieView(this);
            List<Charts.Segment> segs = new ArrayList<>();
            for (String[] s : stats) {
                long fen = Long.parseLong(s[1]);
                segs.add(new Charts.Segment(Category.colorOf(s[0]), total > 0 ? fen * 1f / total : 0));
            }
            pie.setSegments(segs);
            catInner.addView(pie, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

            long max = 0;
            for (String[] s : stats) max = Math.max(max, Long.parseLong(s[1]));
            int limit = Math.min(stats.size(), 12);
            for (int i = 0; i < limit; i++) {
                addRankRow(catInner, i + 1, stats.get(i)[0],
                        Long.parseLong(stats.get(i)[1]), total, max, i == limit - 1);
            }
        }
    }

    private void addRankRow(LinearLayout parent, int rank, String cat, long fen, long total, long max, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        TextView no = new TextView(this);
        no.setText(String.valueOf(rank));
        no.setTextSize(14);
        no.setTypeface(null, Typeface.BOLD);
        no.setTextColor(col(R.color.ink_sub));
        row.addView(no, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView icon = new TextView(this);
        icon.setText(Category.iconOf(cat));
        icon.setTextSize(17);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(this, Category.iconBgOf(cat), 22));
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(10);
        row.addView(mid, mlp);

        LinearLayout l1 = new LinearLayout(this);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(cat);
        name.setTextSize(14);
        name.setTextColor(col(R.color.ink));
        l1.addView(name);
        TextView pct = new TextView(this);
        pct.setText(total > 0 ? "  " + String.format("%.2f%%", fen * 100.0 / total) : "");
        pct.setTextSize(11);
        pct.setTextColor(col(R.color.ink_sub));
        l1.addView(pct);
        mid.addView(l1);

        // 进度条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.round(this, 0xFFF0F2F5, 2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        blp.topMargin = dp(4);
        mid.addView(bar, blp);
        int p = max > 0 ? (int) Math.max(4, Math.round(fen * 100.0 / max)) : 0;
        View fill = new View(this);
        fill.setBackground(Ui.round(this, Category.colorOf(cat), 2));
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, p));
        View rest = new View(this);
        bar.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - p));

        TextView amt = new TextView(this);
        amt.setText("¥" + fmt(fen));
        amt.setTextSize(14);
        amt.setTypeface(null, Typeface.BOLD);
        amt.setTextColor(col(R.color.ink));
        row.addView(amt);

        parent.addView(row);
        if (!last) {
            View line = new View(this);
            line.setBackgroundColor(col(R.color.line));
            parent.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f)));
        }
    }

    // ============================================================
    // 首页识图：选图 → OCR → 确认记账
    // ============================================================

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "选择截图"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Toast.makeText(this, "正在识别图片…", Toast.LENGTH_SHORT).show();
        // 用行结构识别：金额与商户按「同一行」归属，比压成一段准得多
        OcrHelper.recognizeLines(this, data.getData(), new OcrHelper.LineCallback() {
            @Override public void onLines(java.util.List<com.aron.autobill.core.OcrLine> lines, String fullText) {
                final java.util.List<String> texts = new java.util.ArrayList<>(lines.size());
                for (com.aron.autobill.core.OcrLine l : lines) texts.add(l.text);
                runOnUiThread(new Runnable() {
                    @Override public void run() { confirmParsed(fullText, texts); }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void confirmParsed(final String ocrText) {
        confirmParsed(ocrText, null);
    }

    private void confirmParsed(final String ocrText, final java.util.List<String> lines) {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            Toast.makeText(this, "没有识别到文字", Toast.LENGTH_SHORT).show();
            return;
        }
        final com.aron.autobill.core.BillParser parser = new com.aron.autobill.core.BillParser();
        final java.util.List<com.aron.autobill.model.ParseResult> list =
                new java.util.ArrayList<>();
        // ① 先按行结构解析一次（最准：金额与商户同行归属）
        if (lines != null && !lines.isEmpty()) {
            com.aron.autobill.model.ParseResult one = parser.parseOcr(lines, "ocr");
            if (one.ok) list.add(one);
        }
        // ② 行结构没解析出结果，退回多金额扫描（账单列表截图会有多笔）
        if (list.isEmpty()) {
            list.addAll(parser.parseAll(ocrText, "ocr"));
        }
        if (list.isEmpty()) {
            Toast.makeText(this, "没能识别出交易信息，可到「记一笔」手动补录", Toast.LENGTH_LONG).show();
            return;
        }

        if (list.size() == 1) {
            confirmSingle(ocrText, list.get(0));
            return;
        }

        // 多金额候选：勾选式对话框（单选/多选/不选，可修改金额/商户）
        com.aron.autobill.ui.OcrCandidateDialog.show(this, list, ocrText,
                "识别到 " + list.size() + " 笔可能的账单",
                new com.aron.autobill.ui.OcrCandidateDialog.OnConfirm() {
                    @Override public void onConfirmed(java.util.List<com.aron.autobill.model.ParseResult> selected) {
                        if (selected == null || selected.isEmpty()) {
                            Toast.makeText(MainActivity.this, "未选择任何账单", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int saved = 0, dup = 0;
                        long now = System.currentTimeMillis();
                        for (com.aron.autobill.model.ParseResult r : selected) {
                            if (store.findSimilar(r.type, r.amountFen, r.merchant,
                                    now - 5 * 60 * 1000L, now + 60_000L) != null) { dup++; continue; }
                            Bill bill = new Bill(System.currentTimeMillis(), "ocr", r.merchant,
                                    r.amountFen, r.type, ocrText,
                                    Category.classifyFor(r, ocrText),
                                    r.note == null ? "" : r.note, r.confidence);
                            if (store.insert(bill)) saved++; else dup++;
                            now = System.currentTimeMillis();
                        }
                        Toast.makeText(MainActivity.this,
                                saved == 0 ? "选中的账单都已存在，未重复记账"
                                        : ("已记账 " + saved + " 笔" + (dup > 0 ? "，跳过重复 " + dup + " 笔" : "")),
                                Toast.LENGTH_LONG).show();
                        reload();
                    }
                });
    }

    private void confirmSingle(final String ocrText, final com.aron.autobill.model.ParseResult r) {
        if (r.confidence < 50) {
            Toast.makeText(this, "识别可信度偏低，可到「记一笔」手动补录", Toast.LENGTH_LONG).show();
            return;
        }
        final String category = Category.classifyFor(r, ocrText);
        String msg = (r.type == 2 ? "收入 " : "支出 ") + fmt(r.amountFen) + " 元\n商户："
                + (r.merchant == null ? "未识别" : r.merchant)
                + "\n分类：" + category + " · 识别度 " + r.confidence + "%";
        new MaterialAlertDialogBuilder(this)
                .setTitle("识别到一笔交易")
                .setMessage(msg)
                .setPositiveButton("确认记账", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        long now = System.currentTimeMillis();
                        Bill similar = store.findSimilar(r.type, r.amountFen, r.merchant,
                                now - 5 * 60 * 1000L, now + 60_000L);
                        if (similar != null) {
                            Toast.makeText(MainActivity.this, "这笔已存在，未重复记账", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Bill bill = new Bill(now, "ocr", r.merchant, r.amountFen, r.type,
                                ocrText, category, r.note == null ? "" : r.note, r.confidence);
                        if (store.insert(bill)) {
                            Toast.makeText(MainActivity.this, "已记账", Toast.LENGTH_SHORT).show();
                            reload();
                        } else {
                            Toast.makeText(MainActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============================================================
    // 工具
    // ============================================================

    private MaterialCardView whiteCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(0);
        card.setCardBackgroundColor(Color.WHITE);
        card.setStrokeWidth(0);
        return card;
    }

    private LinearLayout cardInner() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(6), dp(14), dp(6));
        return inner;
    }

    /** 2×2 汇总格 */
    private LinearLayout cell2x2(String l1, String v1, int c1, String l2, String v2, int c2,
                                 String l3, String v3, int c3, String l4, String v4, int c4) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.addView(cellRow(l1, v1, c1, l2, v2, c2));
        grid.addView(cellRow(l3, v3, c3, l4, v4, c4));
        return grid;
    }

    private LinearLayout cellRow(String l1, String v1, int c1, String l2, String v2, int c2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(cell(l1, v1, c1), weightLp());
        row.addView(cell(l2, v2, c2), weightLp());
        return row;
    }

    private LinearLayout cell(String label, String value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(col(R.color.ink_sub));
        cell.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(20);
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(color);
        v.setPadding(0, dp(2), 0, 0);
        cell.addView(v);
        return cell;
    }

    private String dayHeader(String dayKey) {
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            Date d = fmt.parse(dayKey);
            return new SimpleDateFormat("yyyy.MM.dd EEEE", Locale.CHINA).format(d);
        } catch (Exception e) {
            return dayKey;
        }
    }

    /** 年份选择弹窗：从最早账单年份到当前年（新→旧） */
    private void chooseYear(TextView yearView) {
        long minTs = store.earliestTs();
        int curYear = Calendar.getInstance().get(Calendar.YEAR);
        int minYear = curYear;
        if (minTs != Long.MAX_VALUE) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(minTs);
            minYear = Math.min(c.get(Calendar.YEAR), curYear);
        }
        java.util.List<String> names = new ArrayList<>();
        final java.util.List<Integer> years = new ArrayList<>();
        for (int y = curYear; y >= minYear; y--) {
            years.add(y);
            names.add(y + "年");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择年份")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        selectedYear = years.get(which);
                        yearView.setText(selectedYear + "年 ▼");
                        reloadReport();
                    }
                })
                .show();
    }

    private long yearStartOf(int y) {
        Calendar cal = Calendar.getInstance();
        cal.set(y, Calendar.JANUARY, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long yearEndOf(int y) {
        Calendar cal = Calendar.getInstance();
        cal.set(y, Calendar.DECEMBER, 31, 23, 59, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }

    /** 选中年份的「当前月」起始（月报场景：年份可选，月份跟随今天） */
    private long yearMonthStart(int y) {
        Calendar cal = Calendar.getInstance();
        int m = cal.get(Calendar.MONTH);
        cal.clear();
        cal.set(y, m, 1, 0, 0, 0);
        return cal.getTimeInMillis();
    }

    private long monthEndOf(int y) {
        Calendar cal = Calendar.getInstance();
        int m = cal.get(Calendar.MONTH);
        cal.clear();
        cal.set(y, m, 1, 0, 0, 0);
        cal.add(Calendar.MONTH, 1);
        return cal.getTimeInMillis() - 1;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private long dayStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long weekStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long monthStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long yearStart() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    /** 分 → 元字符串（无 ¥ 前缀，负数自动带 -） */
    private String fmt(long fen) {
        boolean neg = fen < 0;
        long abs = Math.abs(fen);
        long y = abs / 100;
        long f = abs % 100;
        String s = f == 0 ? String.valueOf(y) : String.format(Locale.CHINA, "%d.%02d", y, f);
        return (neg ? "-" : "") + s;
    }

    /** 元（float）→ 显示文本：整数不带小数点，小数保留两位 */
    private static String yuanText(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.CHINA, "%.2f", v);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private int col(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(refreshReceiver);
        store.close();
        super.onDestroy();
    }
}
