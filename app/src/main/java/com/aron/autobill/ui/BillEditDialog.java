package com.aron.autobill.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.aron.autobill.R;
import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.Bill;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 账单编辑：金额 / 收支方向 / 分类 / 商户 / 条目 / 时间。
 * 保存后重新生成去重指纹，保证「同额同商户」不会因编辑而被永久锁死。
 */
public class BillEditDialog {

    public interface OnSaved {
        void onSaved();
    }

    private final Context ctx;
    private final BillStore store;
    private final long billId;
    private final OnSaved callback;

    private Bill bill;

    private EditText etAmount, etMerchant, etNote, etDate;
    private TextView tvDir, tvCategory;
    private int type;
    private String category;

    public BillEditDialog(Context ctx, BillStore store, long billId, OnSaved callback) {
        this.ctx = ctx;
        this.store = store;
        this.billId = billId;
        this.callback = callback;
    }

    public void show() {
        bill = store.findById(billId);
        if (bill == null) {
            Toast.makeText(ctx, "这笔账已不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        type = bill.type;
        category = bill.category == null ? Category.OTHER : bill.category;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 20);
        root.setPadding(pad, Ui.dp(ctx, 12), pad, 0);

        // ---- 收支方向 ----
        tvDir = new TextView(ctx);
        tvDir.setTextSize(15);
        tvDir.setTypeface(null, Typeface.BOLD);
        tvDir.setGravity(Gravity.CENTER);
        tvDir.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10));
        root.addView(tvDir, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvDir.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                type = type == 1 ? 2 : 1;
                // 换方向后原分类可能不在新方向的列表里
                if (!contains(Category.of(type), category)) category = Category.of(type)[0];
                renderDir();
            }
        });

        // ---- 金额 ----
        etAmount = addField(root, "金额（元）",
                trimZeros(bill.amountYuan()));
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // ---- 分类 ----
        tvCategory = addPicker(root, "分类", category);
        tvCategory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseCategory(); }
        });

        // ---- 商户 ----
        etMerchant = addField(root, "商户", bill.merchant == null ? "" : bill.merchant);

        // ---- 条目 ----
        etNote = addField(root, "条目 / 备注", bill.note == null ? "" : bill.note);

        // ---- 时间 ----
        etDate = addField(root, "时间", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(bill.ts)));
        etDate.setHint("格式 2026-11-10 12:30");

        renderDir();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("修改账单")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { save(); }
                })
                .show();
    }

    private void renderDir() {
        boolean income = type == 2;
        tvDir.setText(income ? "收入" : "支出");
        tvDir.setTextColor(ContextCompat.getColor(ctx, income ? R.color.income : R.color.expense));
        tvDir.setBackground(Ui.round(ctx, income ? 0x1427AE60 : 0x14E74C3C, 8));
        tvCategory.setText(category);
    }

    private void chooseCategory() {
        final String[] cats = Category.of(type);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("选择分类")
                .setItems(cats, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        category = cats[which];
                        tvCategory.setText(category);
                    }
                })
                .show();
    }

    private void save() {
        // 金额
        String amtStr = etAmount.getText() == null ? "" : etAmount.getText().toString().trim();
        Long fenObj = BillParser.toFen(amtStr);
        if (fenObj == null || fenObj <= 0) {
            Toast.makeText(ctx, "金额格式不正确", Toast.LENGTH_SHORT).show();
            return;
        }
        long fen = fenObj;

        // 时间（解析失败则保留原值，不打断用户）
        long ts = bill.ts;
        String dateStr = etDate.getText() == null ? "" : etDate.getText().toString().trim();
        if (!dateStr.isEmpty()) {
            Long parsed = parseDate(dateStr);
            if (parsed == null) {
                Toast.makeText(ctx, "时间格式应为 2026-11-10 12:30", Toast.LENGTH_LONG).show();
                return;
            }
            ts = parsed;
        }

        String merchant = etMerchant.getText() == null ? "" : etMerchant.getText().toString().trim();
        String note = etNote.getText() == null ? "" : etNote.getText().toString().trim();

        boolean ok = store.updateFull(bill.id, ts, fen, type, category, merchant, note);
        if (ok) {
            Toast.makeText(ctx, "已保存修改", Toast.LENGTH_SHORT).show();
            if (callback != null) callback.onSaved();
        } else {
            Toast.makeText(ctx, "保存失败（可能与已有账单重复）", Toast.LENGTH_LONG).show();
        }
    }

    /** 支持 2026-11-10 12:30 / 2026-11-10 / 今天 12:30 */
    private Long parseDate(String s) {
        String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "MM-dd HH:mm"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.CHINA);
                f.setLenient(false);
                Date d = f.parse(s);
                if (d != null) {
                    // 无年份的格式补当前年
                    if (p.startsWith("MM")) {
                        Calendar c = Calendar.getInstance();
                        c.setTime(d);
                        c.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR));
                        return c.getTimeInMillis();
                    }
                    return d.getTime();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String trimZeros(String s) {
        if (s == null) return "0";
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean contains(String[] arr, String v) {
        if (v == null) return false;
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    private EditText addField(LinearLayout parent, String label, String value) {
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
        l.setPadding(0, Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4));
        parent.addView(l);

        EditText et = new EditText(ctx);
        et.setText(value);
        et.setTextSize(15);
        et.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        et.setBackground(Ui.roundStroke(ctx, 0xFFF7F8FA,
                ContextCompat.getColor(ctx, R.color.line), 8));
        et.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        et.setSingleLine(true);
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private TextView addPicker(LinearLayout parent, String label, String value) {
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
        l.setPadding(0, Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4));
        parent.addView(l);

        TextView tv = new TextView(ctx);
        tv.setText(value);
        tv.setTextSize(15);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.brand_orange));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setBackground(Ui.roundStroke(ctx, 0xFFF7F8FA,
                ContextCompat.getColor(ctx, R.color.line), 8));
        tv.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        parent.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }
}
