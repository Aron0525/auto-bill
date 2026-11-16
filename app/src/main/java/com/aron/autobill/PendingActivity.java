package com.aron.autobill;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.core.PendingStore;
import com.aron.autobill.model.Bill;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 待确认队列。
 *
 * 解决的核心问题：旧逻辑对置信度 < 50 的解析结果直接 return 丢弃。
 * 「宁可不记，不可错记」的精神没错，但用户完全不知道漏了什么 ——
 * 这正是「自动记账效果差」的主观感受来源。
 *
 * 这里把「机器拿不准的交易」摆到用户面前：
 *   · 确认 → 入账
 *   · 修正 → 改金额/商户/方向/分类后入账
 *   · 忽略 → 删除，不入账
 *
 * 账本依然只收用户认可过的账，但「不确定」不再人间蒸发。
 */
public class PendingActivity extends AppCompatActivity {

    private PendingStore pending;
    private BillStore bills;
    private LinearLayout list;
    private LinearLayout empty;
    private TextView tvCount;
    private final List<PendingStore.Item> data = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending);

        pending = new PendingStore(this);
        bills = new BillStore(this);

        list = findViewById(R.id.ll_pending_list);
        empty = findViewById(R.id.ll_empty);
        tvCount = findViewById(R.id.tv_pending_count);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_clear_pending).setOnClickListener(v -> confirmClearAll());

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        data.clear();
        data.addAll(pending.list());

        list.removeAllViews();
        boolean has = !data.isEmpty();
        list.setVisibility(has ? View.VISIBLE : View.GONE);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        tvCount.setText(has ? "共 " + data.size() + " 笔待确认" : "没有待确认的交易");

        for (PendingStore.Item it : data) {
            list.addView(buildRow(it));
        }
    }

    private View buildRow(final PendingStore.Item it) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_pending, list, false);
        TextView tvAmount = v.findViewById(R.id.tv_amount);
        TextView tvMerchant = v.findViewById(R.id.tv_merchant);
        TextView tvMeta = v.findViewById(R.id.tv_meta);
        TextView tvRaw = v.findViewById(R.id.tv_raw);

        boolean income = it.type == 2;
        String sign = income ? "+" : "-";
        tvAmount.setText(sign + String.format("%.2f", it.amountFen / 100.0));
        tvAmount.setTextColor(ContextCompat.getColor(this,
                income ? R.color.income : R.color.expense));
        tvMerchant.setText(it.merchant == null || it.merchant.isEmpty()
                ? "（未识别商户）" : it.merchant);
        tvMeta.setText((it.category == null ? Category.OTHER : it.category)
                + " · 置信度 " + it.confidence
                + (it.channel == null || it.channel.isEmpty() ? "" : " · " + it.channel));
        tvRaw.setText(it.raw == null ? "" : it.raw);

        v.findViewById(R.id.btn_ok).setOnClickListener(x -> confirm(it));
        v.findViewById(R.id.btn_edit).setOnClickListener(x -> edit(it));
        v.findViewById(R.id.btn_ignore).setOnClickListener(x -> {
            pending.remove(it.id);
            Toast.makeText(this, "已忽略", Toast.LENGTH_SHORT).show();
            reload();
        });
        return v;
    }

    /** 确认入账 */
    private void confirm(PendingStore.Item it) {
        Bill b = new Bill(it.ts, it.platform, it.merchant, it.amountFen, it.type,
                it.raw, it.category, it.note, Math.max(it.confidence, 60));
        b.pending = 0;
        if (bills.insert(b)) {
            Toast.makeText(this, "已入账 ¥" + String.format("%.2f", it.amountFen / 100.0),
                    Toast.LENGTH_SHORT).show();
            sendBroadcast(new Intent("com.aron.autobill.BILL_ADDED").setPackage(getPackageName()));
        } else {
            Toast.makeText(this, "这笔已在账本中", Toast.LENGTH_SHORT).show();
        }
        pending.remove(it.id);
        reload();
    }

    /** 修正后入账 */
    private void edit(PendingStore.Item it) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_pending_edit, null);
        EditText etAmount = v.findViewById(R.id.et_amount);
        EditText etMerchant = v.findViewById(R.id.et_merchant);
        TextView tvDir = v.findViewById(R.id.tv_dir);
        TextView tvCat = v.findViewById(R.id.tv_cat);

        etAmount.setText(String.format("%.2f", it.amountFen / 100.0));
        etMerchant.setText(it.merchant == null ? "" : it.merchant);

        final int[] dir = {it.type};
        tvDir.setText(dir[0] == 2 ? "收入" : "支出");
        tvDir.setOnClickListener(x -> {
            dir[0] = dir[0] == 2 ? 1 : 2;
            tvDir.setText(dir[0] == 2 ? "收入" : "支出");
        });

        final String[] cat = {it.category == null ? Category.OTHER : it.category};
        tvCat.setText(cat[0]);
        tvCat.setOnClickListener(x -> {
            final String[] opts = Category.of(dir[0]);
            new MaterialAlertDialogBuilder(this)
                    .setTitle("选择分类")
                    .setItems(opts, (d, which) -> {
                        cat[0] = opts[which];
                        tvCat.setText(cat[0]);
                    })
                    .show();
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("修正后入账")
                .setView(v)
                .setPositiveButton("入账", (d, w) -> {
                    long fen;
                    try {
                        fen = Math.round(Double.parseDouble(
                                etAmount.getText().toString().trim()
                                        .replace("¥", "").replace("￥", "")) * 100);
                    } catch (Exception e) {
                        Toast.makeText(this, "金额格式不对", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (fen <= 0) {
                        Toast.makeText(this, "金额必须大于 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Bill b = new Bill(it.ts, it.platform,
                            etMerchant.getText().toString().trim(), fen, dir[0],
                            it.raw, cat[0], it.note, 100);
                    b.pending = 0;
                    bills.insert(b);
                    pending.remove(it.id);
                    Toast.makeText(this, "已入账", Toast.LENGTH_SHORT).show();
                    sendBroadcast(new Intent("com.aron.autobill.BILL_ADDED").setPackage(getPackageName()));
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearAll() {
        if (data.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空待确认队列？")
                .setMessage("将删除 " + data.size() + " 笔待确认记录，这些交易不会入账。")
                .setPositiveButton("清空", (d, w) -> {
                    pending.clear();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        pending.close();
        bills.close();
        super.onDestroy();
    }
}
