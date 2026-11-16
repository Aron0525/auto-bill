package com.aron.autobill.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.aron.autobill.R;
import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.ParseResult;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 多金额候选对话框：
 *  - 每行一个候选账单（复选框 + 金额 + 商户·分类·识别度）
 *  - 支持单选 / 多选 / 不选
 *  - 点「修改」可纠正金额与商户（识别不准时兜底）
 */
public class OcrCandidateDialog {

    public interface OnConfirm {
        void onConfirmed(List<ParseResult> selected);
    }

    public static void show(Context ctx, final List<ParseResult> candidates,
                            final String ocrText, String title, final OnConfirm callback) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 6), Ui.dp(ctx, 20), 0);
        scroll.addView(root);

        final List<CheckBox> checks = new ArrayList<>();
        final List<TextView> amountViews = new ArrayList<>();
        final List<TextView> subViews = new ArrayList<>();
        final List<ParseResult> rows = candidates;

        for (int i = 0; i < rows.size(); i++) {
            final ParseResult r = rows.get(i);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8));

            CheckBox cb = new CheckBox(ctx);
            cb.setChecked(i == 0);   // 默认勾选第一笔（最可能是主交易）
            checks.add(cb);
            row.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout mid = new LinearLayout(ctx);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            mlp.leftMargin = Ui.dp(ctx, 6);
            row.addView(mid, mlp);

            TextView amt = new TextView(ctx);
            amt.setTextSize(17);
            amt.setTypeface(null, Typeface.BOLD);
            mid.addView(amt);
            amountViews.add(amt);

            TextView sub = new TextView(ctx);
            sub.setTextSize(11);
            sub.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
            mid.addView(sub);
            subViews.add(sub);

            TextView edit = new TextView(ctx);
            edit.setText("修改");
            edit.setTextSize(13);
            edit.setTypeface(null, Typeface.BOLD);
            edit.setTextColor(ContextCompat.getColor(ctx, R.color.primary));
            edit.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6));
            final int idx = i;
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showEdit(ctx, r, ocrText, amountViews.get(idx), subViews.get(idx));
                }
            });
            row.addView(edit);

            root.addView(row);
            renderRow(r, ocrText, amountViews.get(i), subViews.get(i));
        }

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("记录选中", (d, w) -> {
                    List<ParseResult> selected = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        if (checks.get(i).isChecked()) selected.add(rows.get(i));
                    }
                    if (callback != null) callback.onConfirmed(selected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void renderRow(ParseResult r, String ocrText, TextView amt, TextView sub) {
        boolean income = r.type == 2;
        amt.setText((income ? "+" : "-") + yuan(r.amountFen) + " 元");
        amt.setTextColor(ContextCompat.getColor(amt.getContext(),
                income ? R.color.income : R.color.ink));
        String cat = Category.classifyFor(r, ocrText);
        sub.setText((r.merchant == null || r.merchant.isEmpty() ? "未识别" : r.merchant)
                + " · " + cat + " · 识别度 " + r.confidence + "%");
    }

    /** 修改单条候选：金额 + 商户 */
    private static void showEdit(final Context ctx, final ParseResult r, final String ocrText,
                                 final TextView amtView, final TextView subView) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 10), Ui.dp(ctx, 24), 0);

        TextView l1 = new TextView(ctx);
        l1.setText("金额（元）");
        l1.setTextSize(12);
        l1.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
        box.addView(l1);
        final EditText etAmount = new EditText(ctx);
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setText(yuan(r.amountFen));
        box.addView(etAmount);

        TextView l2 = new TextView(ctx);
        l2.setText("商户");
        l2.setTextSize(12);
        l2.setTextColor(ContextCompat.getColor(ctx, R.color.ink_sub));
        l2.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        box.addView(l2);
        final EditText etMerchant = new EditText(ctx);
        etMerchant.setText(r.merchant == null || "未识别".equals(r.merchant) ? "" : r.merchant);
        box.addView(etMerchant);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("修改这笔账单")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    Long fen = BillParser.toFen(etAmount.getText().toString().trim());
                    if (fen == null || fen <= 0) return;
                    r.amountFen = fen;
                    String m = etMerchant.getText().toString().trim();
                    r.merchant = m.isEmpty() ? "未识别" : m;
                    r.confidence = 100;   // 用户人工确认过
                    renderRow(r, ocrText, amtView, subView);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String yuan(long fen) {
        long y = fen / 100;
        long f = fen % 100;
        return f == 0 ? String.valueOf(y) : String.format("%d.%02d", y, f);
    }
}
