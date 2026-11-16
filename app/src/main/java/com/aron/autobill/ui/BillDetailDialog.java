package com.aron.autobill.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Date;
import java.util.Locale;

/** 账单详情：查看明细 / 改分类 / 删除（Material 风格） */
public class BillDetailDialog {

    public interface OnChanged {
        void onChanged();
    }

    private final Context ctx;
    private final BillStore store;
    private final long billId;
    private final OnChanged callback;

    private Bill bill;
    private AlertDialog dialog;
    private TextView catValue;

    public BillDetailDialog(Context ctx, BillStore store, long billId, OnChanged callback) {
        this.ctx = ctx;
        this.store = store;
        this.billId = billId;
        this.callback = callback;
    }

    public void show() {
        bill = store.findById(billId);
        if (bill == null) return;

        View view = LayoutInflater.from(ctx).inflate(R.layout.dialog_bill_detail, null, false);
        TextView tvAmount = view.findViewById(R.id.tv_amount);
        TextView tvDir = view.findViewById(R.id.tv_dir);
        LinearLayout rows = view.findViewById(R.id.rows_container);

        boolean income = bill.type == 2;
        tvAmount.setText((income ? "+" : "-") + bill.amountYuan());
        tvAmount.setTextColor(ContextCompat.getColor(ctx,
                income ? R.color.income : R.color.expense));
        tvDir.setText(income ? "收入" : "支出");

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        addRow(rows, "时间", fmt.format(new Date(bill.ts)));

        catValue = addRow(rows, "分类", bill.category == null ? Category.OTHER : bill.category);
        catValue.setTextColor(ContextCompat.getColor(ctx, R.color.brand_orange));
        catValue.setTypeface(null, Typeface.BOLD);
        catValue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseCategory(); }
        });

        addRow(rows, "商户", bill.merchant == null || bill.merchant.isEmpty() ? "未识别" : bill.merchant);
        if (bill.note != null && !bill.note.isEmpty() && !"手动记账".equals(bill.raw)) {
            addRow(rows, "条目", bill.note);
        }
        addRow(rows, "来源", BillParser.platformName(bill.platform));
        if (bill.confidence > 0 && bill.confidence < 100) {
            addRow(rows, "识别度", bill.confidence + "%");
        }
        if (bill.raw != null && !bill.raw.isEmpty() && !"手动记账".equals(bill.raw)) {
            TextView raw = addRow(rows, "原文", bill.raw);
            raw.setTextSize(12);
        }

        dialog = new MaterialAlertDialogBuilder(ctx)
                .setView(view)
                .setNegativeButton("关闭", null)
                .setNeutralButton("编辑", null)
                .setPositiveButton("删除", null)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dialog.dismiss();
                        new BillEditDialog(ctx, store, billId, new BillEditDialog.OnSaved() {
                            @Override public void onSaved() {
                                if (callback != null) callback.onChanged();
                            }
                        }).show();
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmDelete(); }
                });
            }
        });
        dialog.show();
    }

    private void chooseCategory() {
        final String[] cats = Category.of(bill.type);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("选择分类")
                .setItems(cats, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String cat = cats[which];
                        if (store.updateCategory(bill.id, cat)) {
                            bill.category = cat;
                            catValue.setText(cat);
                            if (callback != null) callback.onChanged();
                            Toast.makeText(ctx, "已改为「" + cat + "」", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("删除这笔记录？")
                .setMessage(bill.amountYuan() + " 元 · "
                        + (bill.merchant == null ? "未识别" : bill.merchant))
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (store.delete(bill.id)) {
                            Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show();
                            if (dialog != null) dialog.dismiss();
                            if (callback != null) callback.onChanged();
                        } else {
                            Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(ctx, 9), 0, Ui.dp(ctx, 9));

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(ctx, 54),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        v.setMaxLines(3);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);

        View line = new View(ctx);
        line.setBackgroundColor(ContextCompat.getColor(ctx, R.color.line));
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 0.6f)));
        return v;
    }
}
