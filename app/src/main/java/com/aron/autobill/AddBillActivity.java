package com.aron.autobill;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.Bill;
import com.aron.autobill.model.ParseResult;
import com.aron.autobill.ui.OcrHelper;
import com.aron.autobill.ui.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 记一笔（全屏，参照设计稿）：
 *  支出/收入 Tab + 金额数字键盘 + 分类网格（彩色图标）+ 识图填充
 */
public class AddBillActivity extends AppCompatActivity {

    private static final int REQ_PICK_IMAGE = 4001;

    private BillStore store;

    private int type = 1;
    private String category = Category.EXPENSE[0];
    private final StringBuilder amountBuf = new StringBuilder();

    private TextView tvAmount, tabExpense, tabIncome, tvDate;
    private LinearLayout categoryGrid;
    private EditText etNote;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_bill);
        store = new BillStore(this);

        tvAmount = findViewById(R.id.tv_amount);
        tabExpense = findViewById(R.id.tab_expense);
        tabIncome = findViewById(R.id.tab_income);
        tvDate = findViewById(R.id.tv_date);
        categoryGrid = findViewById(R.id.category_grid);
        etNote = findViewById(R.id.et_note);

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_ocr).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        });

        tabExpense.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchType(1); }
        });
        tabIncome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchType(2); }
        });

        bindKey(R.id.key_1, "1");
        bindKey(R.id.key_2, "2");
        bindKey(R.id.key_3, "3");
        bindKey(R.id.key_4, "4");
        bindKey(R.id.key_5, "5");
        bindKey(R.id.key_6, "6");
        bindKey(R.id.key_7, "7");
        bindKey(R.id.key_8, "8");
        bindKey(R.id.key_9, "9");
        bindKey(R.id.key_0, "0");
        bindKey(R.id.key_dot, ".");

        findViewById(R.id.key_del).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (amountBuf.length() > 0) {
                    amountBuf.deleteCharAt(amountBuf.length() - 1);
                    renderAmount();
                }
            }
        });
        findViewById(R.id.key_done).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { done(false); }
        });
        findViewById(R.id.key_again).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { done(true); }
        });

        renderAmount();
        renderDate();
        renderCategories();
    }

    private void bindKey(int id, final String ch) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ch.equals(".")) {
                    if (amountBuf.indexOf(".") >= 0) return;      // 只允许一个小数点
                    if (amountBuf.length() == 0) amountBuf.append("0");
                }
                if (!ch.equals(".")) {
                    // 金额上限：整数 7 位 + 小数 2 位
                    if (amountBuf.indexOf(".") >= 0) {
                        int dot = amountBuf.indexOf(".");
                        if (amountBuf.length() - dot > 2) return;
                    } else if (amountBuf.length() >= 7) {
                        return;
                    }
                    if (amountBuf.length() == 1 && amountBuf.charAt(0) == '0') {
                        amountBuf.setLength(0);   // 不允许 0 开头
                    }
                }
                amountBuf.append(ch);
                renderAmount();
            }
        });
    }

    private void switchType(int t) {
        if (type == t) return;
        type = t;
        category = Category.of(type)[0];
        tabExpense.setTextColor(type == 1 ? col(R.color.ink) : col(R.color.ink_sub));
        tabExpense.setTypeface(null, type == 1 ? Typeface.BOLD : Typeface.NORMAL);
        tabIncome.setTextColor(type == 2 ? col(R.color.ink) : col(R.color.ink_sub));
        tabIncome.setTypeface(null, type == 2 ? Typeface.BOLD : Typeface.NORMAL);
        renderCategories();
    }

    private void renderAmount() {
        if (amountBuf.length() == 0) {
            tvAmount.setText("0.00");
            tvAmount.setTextColor(col(R.color.ink_sub));
        } else {
            String s = amountBuf.toString();
            if (s.endsWith(".")) s = s + "00";
            else if (s.contains(".") && s.length() - s.indexOf(".") == 2) s = s + "0";
            tvAmount.setText(s);
            tvAmount.setTextColor(col(R.color.ink));
        }
    }

    private void renderDate() {
        String now = new SimpleDateFormat("'今天' HH:mm", Locale.CHINA)
                .format(new Date(System.currentTimeMillis()));
        tvDate.setText(now);
    }

    private void renderCategories() {
        categoryGrid.removeAllViews();
        String[] cats = Category.of(type);
        int perRow = 5;
        LinearLayout row = null;
        for (int i = 0; i < cats.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                categoryGrid.addView(row);
            }
            final String name = cats[i];
            boolean selected = name.equals(category);

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            TextView icon = new TextView(this);
            icon.setText(Category.iconOf(name));
            icon.setTextSize(22);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.round(this, Category.iconBgOf(name), 26));
            if (selected) {
                icon.setForeground(ContextCompat.getDrawable(this, R.drawable.bg_icon_selected));
            }
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52));
            cell.addView(icon, iconLp);

            TextView label = new TextView(this);
            label.setText(name);
            label.setTextSize(11);
            label.setTextColor(col(selected ? R.color.primary : R.color.ink));
            label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            label.setPadding(0, Ui.dp(this, 5), 0, 0);
            cell.addView(label);

            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    category = name;
                    renderCategories();
                }
            });

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(cell, cellLp);
        }
        // 补齐末行空位
        if (row != null) {
            int filled = cats.length % perRow;
            if (filled != 0) {
                for (int i = filled; i < perRow; i++) {
                    View spacer = new View(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
            }
        }
    }

    private void done(boolean again) {
        String amountStr = amountBuf.toString();
        if (amountStr.isEmpty() || ".".equals(amountStr)) {
            Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show();
            return;
        }
        long fen = BillParser.toFen(amountStr) == null ? 0 : BillParser.toFen(amountStr);
        if (fen <= 0) {
            Toast.makeText(this, "金额必须大于 0", Toast.LENGTH_SHORT).show();
            return;
        }
        String note = etNote.getText() == null ? "" : etNote.getText().toString().trim();
        if (note.isEmpty()) note = "手动记账";

        long now = System.currentTimeMillis();
        // 语义去重：5 分钟内同方向 + 同金额 + 同商户不重复记
        Bill similar = store.findSimilar(type, fen, note, now - 5 * 60 * 1000L, now + 60_000L);
        if (similar != null) {
            Toast.makeText(this, "5 分钟内已记过同额同商户的一笔，未重复记账", Toast.LENGTH_LONG).show();
            if (!again) finish();
            return;
        }

        Bill bill = new Bill(now, "com.aron.autobill", note, fen, type, "手动记账", category, "", 100);
        boolean ok = store.insert(bill);
        if (ok) {
            Toast.makeText(this, "已记账 " + fmtYuan(fen) + " 元", Toast.LENGTH_SHORT).show();
            Intent refresh = new Intent("com.aron.autobill.BILL_ADDED");
            refresh.setPackage(getPackageName());
            sendBroadcast(refresh);
        } else {
            Toast.makeText(this, "保存失败（可能重复）", Toast.LENGTH_SHORT).show();
        }
        if (again) {
            amountBuf.setLength(0);
            etNote.setText("");
            renderAmount();
        } else {
            finish();
        }
    }

    /** 识图：从相册选一张图，OCR 识别后自动填充金额/分类/备注 */
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(Intent.createChooser(intent, "选择截图"), REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        Toast.makeText(this, "正在识别图片…", Toast.LENGTH_SHORT).show();
        OcrHelper.recognizeLines(this, uri, new OcrHelper.LineCallback() {
            @Override public void onLines(java.util.List<com.aron.autobill.core.OcrLine> lines, String fullText) {
                if (fullText == null || fullText.trim().isEmpty()) {
                    Toast.makeText(AddBillActivity.this, "没有识别到文字", Toast.LENGTH_SHORT).show();
                    return;
                }
                final BillParser bp = new BillParser();
                final java.util.List<ParseResult> list = new java.util.ArrayList<>();
                // ① 行结构解析（金额与商户同行归属，最准）
                if (lines != null && !lines.isEmpty()) {
                    java.util.List<String> texts = new java.util.ArrayList<>(lines.size());
                    for (com.aron.autobill.core.OcrLine l : lines) texts.add(l.text);
                    ParseResult one = bp.parseOcr(texts, "ocr");
                    if (one.ok) list.add(one);
                }
                // ② 兜底：多金额扫描
                if (list.isEmpty()) list.addAll(bp.parseAll(fullText, "ocr"));
                if (list.isEmpty()) {
                    Toast.makeText(AddBillActivity.this, "没识别出交易信息，请手动输入", Toast.LENGTH_LONG).show();
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (list.size() == 1) {
                            fillFrom(list.get(0));
                        } else {
                            // 多金额候选：弹窗让用户勾选（单选/多选/不选）
                            pickCandidates(list);
                        }
                    }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(AddBillActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 多候选勾选弹窗：默认勾选第一笔，确认后填充第一笔被选中的 */
    private void pickCandidates(final java.util.List<ParseResult> list) {
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ParseResult r = list.get(i);
            items[i] = (r.type == 2 ? "+" : "-") + fmtYuan(r.amountFen) + " 元 · "
                    + (r.merchant == null || r.merchant.isEmpty() ? "未识别" : r.merchant)
                    + " · " + Category.classify("", r.merchant, r.type);
        }
        final boolean[] checked = new boolean[list.size()];
        checked[0] = true;   // 默认勾选第一笔（最可能是主交易）
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("识别到 " + list.size() + " 笔可能的账单")
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) ->
                        checked[which] = isChecked)
                .setPositiveButton("填充第一笔选中", (d, w) -> {
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            fillFrom(list.get(i));
                            return;
                        }
                    }
                    Toast.makeText(this, "未选择任何账单", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把一笔识别结果填入表单：金额 / 收支 / 分类 / 备注 */
    private void fillFrom(ParseResult r) {
        amountBuf.setLength(0);
        long fen = r.amountFen;
        if (fen % 100 == 0) {
            amountBuf.append(String.valueOf(fen / 100));
        } else if (fen % 10 == 0) {
            amountBuf.append((fen / 100) + "." + (fen % 100) / 10);
        } else {
            amountBuf.append((fen / 100) + "." + String.format("%02d", fen % 100));
        }
        renderAmount();
        String cat = Category.classify("", r.merchant, r.type);
        if (r.type == 2 && type != 2) {
            type = 2;
            tabIncome.setTextColor(col(R.color.ink));
            tabIncome.setTypeface(null, Typeface.BOLD);
            tabExpense.setTextColor(col(R.color.ink_sub));
            tabExpense.setTypeface(null, Typeface.NORMAL);
        }
        // 分类：识别结果里有的才切，否则保持当前
        for (String c : Category.of(type)) {
            if (c.equals(cat)) {
                category = c;
                break;
            }
        }
        renderCategories();
        String merchant = r.merchant == null ? "" : r.merchant;
        if (!"手动记账".equals(merchant) && !merchant.isEmpty() && !"未识别".equals(merchant)) {
            etNote.setText(merchant);
        }
        Toast.makeText(this, "识别成功：¥" + fmtYuan(r.amountFen) + " " + merchant,
                Toast.LENGTH_LONG).show();
    }

    private static String fmtYuan(long fen) {
        long abs = Math.abs(fen);
        long y = abs / 100;
        long f = abs % 100;
        return f == 0 ? String.valueOf(y) : String.format("%d.%02d", y, f);
    }

    private int col(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    @Override
    protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }
}
