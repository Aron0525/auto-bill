package com.aron.autobill;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;
import com.aron.autobill.core.Category;
import com.aron.autobill.model.Bill;
import com.aron.autobill.model.ParseResult;
import com.aron.autobill.ui.OcrHelper;
import com.aron.autobill.ui.Ui;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;

/**
 * 截图记账：接收其他应用分享来的图片（ACTION_SEND image/*），
 * 本地 OCR 识别文字 → 解析成账目 → 用户确认后记账。
 *
 * 使用场景：自动记账失败时，用户截取支付成功页/通知栏，
 * 在相册或截图预览里「分享」到本应用即可补记。
 */
public class ShareActivity extends AppCompatActivity {

    private BillStore store;
    private BillParser parser;

    private TextView tvStatus;
    private LinearProgressIndicator progress;
    private MaterialCardView cardResult;
    private LinearLayout resultContainer;
    private TextView tvRawLabel, tvRaw;
    private LinearLayout buttonRow;
    private com.google.android.material.button.MaterialButton btnManual, btnConfirm, btnCancel;

    private String ocrText = "";
    private java.util.List<String> ocrLines = null;   // OCR 行结构（金额/商户同行归属用）
    private ParseResult parsed;
    private TextRecognizer recognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);
        store = new BillStore(this);
        parser = new BillParser();
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

        tvStatus = findViewById(R.id.tv_status);
        progress = findViewById(R.id.progress);
        cardResult = findViewById(R.id.card_result);
        resultContainer = findViewById(R.id.result_container);
        tvRawLabel = findViewById(R.id.tv_raw_label);
        tvRaw = findViewById(R.id.tv_raw);
        buttonRow = findViewById(R.id.button_row);
        btnManual = findViewById(R.id.btn_manual);
        btnConfirm = findViewById(R.id.btn_confirm);
        btnCancel = findViewById(R.id.btn_cancel);

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (parsed != null && parsed.ok) insertOne(parsed);
            }
        });
        btnManual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ShareActivity.this, MainActivity.class));
                finish();
            }
        });

        handleIntent(getIntent());
    }

    /**
     * 已经在前台时又收到分享（singleTop 复用实例）：
     * 必须重新走 handleIntent，否则第二张图不会被识别，界面还停在第一张的结果上。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 重置上一次的结果状态，避免两张图的结果叠在一起
        ocrText = "";
        ocrLines = null;
        parsed = null;
        progress.setVisibility(View.VISIBLE);
        cardResult.setVisibility(View.GONE);
        buttonRow.setVisibility(View.GONE);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ink));
        tvStatus.setText("正在识别图片…");
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            fail("没有收到图片，请从截图分享里选择「自动记账」");
            return;
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            fail("没有收到图片，请从截图分享里选择「自动记账」");
            return;
        }
        recognize(uri);
    }

    private void recognize(Uri uri) {
        try {
            Bitmap bitmap = decodeBitmap(uri);
            if (bitmap == null) {
                fail("无法读取这张图片");
                return;
            }
            // 复用 OcrHelper 的增强链路（放大 + 对比度 + 小数点保底重试），
            // 并保留行结构用于金额/商户的同行归属
            Bitmap prepared = OcrHelper.upscale(bitmap, 1200);
            InputImage image = InputImage.fromBitmap(prepared, 0);
            recognizer.process(image)
                    .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<com.google.mlkit.vision.text.Text>() {
                        @Override public void onSuccess(com.google.mlkit.vision.text.Text visionText) {
                            // 带包围盒收集 → 按视觉位置排序（ML Kit 的 block 顺序
                            // 不是视觉顺序：标签列/金额列会被切开分块）
                            java.util.List<com.aron.autobill.core.OcrLine> rows = new java.util.ArrayList<>();
                            for (com.google.mlkit.vision.text.Text.TextBlock b : visionText.getTextBlocks()) {
                                for (com.google.mlkit.vision.text.Text.Line ln : b.getLines()) {
                                    String t = ln.getText();
                                    if (t == null || t.trim().isEmpty()) continue;
                                    android.graphics.Rect r = ln.getBoundingBox();
                                    rows.add(new com.aron.autobill.core.OcrLine(
                                            t.trim(),
                                            r == null ? 0 : r.left,
                                            r == null ? 0 : r.top,
                                            r == null ? 0 : r.right,
                                            r == null ? 0 : r.bottom));
                                }
                            }
                            com.aron.autobill.core.OcrLine.sortVisual(rows);
                            ocrText = com.aron.autobill.core.OcrLine.joinText(rows);
                            ocrLines = new java.util.ArrayList<>(rows.size());
                            for (com.aron.autobill.core.OcrLine r2 : rows) ocrLines.add(r2.text);
                            afterOcr();
                        }
                    })
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            fail("文字识别失败：" + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            fail("读取图片失败：" + e.getMessage());
        }
    }

    /** 解码图片，大图按 2 的幂采样，避免 OOM */
    private Bitmap decodeBitmap(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, bounds);
        is.close();

        int maxDim = 2000;
        int sample = 1;
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        is = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
        if (is != null) is.close();
        return bmp;
    }

    private void afterOcr() {
        progress.setVisibility(View.GONE);
        tvRawLabel.setVisibility(View.VISIBLE);
        tvRaw.setText(ocrText.isEmpty() ? "（没有识别到文字）" : ocrText);
        tvRaw.setVisibility(View.VISIBLE);

        // 便于在真机上用 logcat 核对识别结果（无需读屏）
        Log.i("AutoBillOcr", "行数=" + (ocrLines == null ? 0 : ocrLines.size())
                + " 文本=" + ocrText.replace("\n", " | "));

        if (ocrText.isEmpty()) {
            tvStatus.setText("这张图里没有识别到文字");
            btnManual.setVisibility(View.VISIBLE);
            return;
        }

        // 多金额候选：一张截图可能含多笔交易，全部列出让用户勾选
        // ① 先按行结构解析一次（金额与商户同行归属，最准）
        java.util.List<ParseResult> list = new java.util.ArrayList<>();
        if (ocrLines != null && !ocrLines.isEmpty()) {
            ParseResult one = parser.parseOcr(ocrLines, "ocr");
            if (one.ok) list.add(one);
        }
        // ② 兜底：多金额扫描（账单列表截图会有多笔）
        if (list.isEmpty()) list.addAll(parser.parseAll(ocrText, "ocr"));

        for (ParseResult pr : list) {
            Log.i("AutoBillOcr", "结果 金额=" + pr.amountFen / 100.0
                    + " 商户=" + pr.merchant + " 方向=" + pr.type
                    + " 渠道=" + pr.channel + " 条目=" + pr.note
                    + " 置信=" + pr.confidence);
        }

        if (list.isEmpty()) {
            tvStatus.setText("没能识别出交易信息（金额/商户），可以手动记一笔");
            btnManual.setVisibility(View.VISIBLE);
            return;
        }

        if (list.size() == 1) {
            parsed = list.get(0);
            if (parsed.confidence < 50) {
                tvStatus.setText("识别到了金额，但可信度偏低（" + parsed.confidence
                        + "%），建议核对后手动记一笔");
                btnManual.setVisibility(View.VISIBLE);
                return;
            }
            // 语义去重：5 分钟内同方向+同金额+同商户视为同一笔
            long now = System.currentTimeMillis();
            Bill similar = store.findSimilar(parsed.type, parsed.amountFen, parsed.merchant,
                    now - 5 * 60 * 1000L, now + 60_000L);
            if (similar != null) {
                tvStatus.setText("这笔已经记过了：" + similar.amountYuan() + " 元 · "
                        + (similar.merchant == null ? "未识别" : similar.merchant));
                btnManual.setVisibility(View.GONE);
                buttonRow.setVisibility(View.GONE);
                return;
            }
            showResult();
            return;
        }

        // 多候选：勾选式对话框（支持单选、多选、不选，可修改金额/商户）
        tvStatus.setText("识别到 " + list.size() + " 笔可能的账单，请在弹窗中勾选（可多选/不选/修改）");
        com.aron.autobill.ui.OcrCandidateDialog.show(this, list, ocrText,
                "识别到 " + list.size() + " 笔可能的账单",
                new com.aron.autobill.ui.OcrCandidateDialog.OnConfirm() {
                    @Override public void onConfirmed(java.util.List<ParseResult> selected) {
                        saveCandidates(selected);
                    }
                });
    }

    /** 批量落账：逐条语义去重后插入勾选的候选 */
    private void saveCandidates(java.util.List<ParseResult> selected) {
        if (selected == null || selected.isEmpty()) {
            Toast.makeText(this, "未选择任何账单", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        int saved = 0, skippedDup = 0;
        long now = System.currentTimeMillis();
        for (ParseResult r : selected) {
            if (store.findSimilar(r.type, r.amountFen, r.merchant,
                    now - 5 * 60 * 1000L, now + 60_000L) != null) { skippedDup++; continue; }
            Bill bill = new Bill(System.currentTimeMillis(), "ocr", r.merchant,
                    r.amountFen, r.type, ocrText,
                    Category.classifyFor(r, ocrText),
                    r.note == null ? "" : r.note, r.confidence);
            if (store.insert(bill)) saved++;
            else skippedDup++;
            now = System.currentTimeMillis();
        }
        if (saved > 0) {
            Intent refresh = new Intent("com.aron.autobill.BILL_ADDED");
            refresh.setPackage(getPackageName());
            sendBroadcast(refresh);
        }
        Toast.makeText(this,
                saved == 0 ? "选中的账单都已存在，未重复记账" : ("已记账 " + saved + " 笔"
                        + (skippedDup > 0 ? "，跳过重复 " + skippedDup + " 笔" : "")),
                Toast.LENGTH_LONG).show();
        finish();
    }

    private void insertOne(ParseResult r) {
        long now = System.currentTimeMillis();
        Bill similar = store.findSimilar(r.type, r.amountFen, r.merchant,
                now - 5 * 60 * 1000L, now + 60_000L);
        if (similar != null) {
            Toast.makeText(this, "这笔已存在，未重复记账", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Bill bill = new Bill(now, "ocr", r.merchant, r.amountFen, r.type, ocrText,
                Category.classify(ocrText, r.merchant, r.type),
                r.note == null ? "" : r.note, r.confidence);
        if (store.insert(bill)) {
            Toast.makeText(this, "已记账：" + fmtYuan(r.amountFen) + " 元", Toast.LENGTH_SHORT).show();
            Intent refresh = new Intent("com.aron.autobill.BILL_ADDED");
            refresh.setPackage(getPackageName());
            sendBroadcast(refresh);
        }
        finish();
    }

    private void showResult() {
        tvStatus.setText("识别完成，请核对后确认记账");
        cardResult.setVisibility(View.VISIBLE);
        resultContainer.removeAllViews();

        boolean income = parsed.type == 2;
        String category = Category.classify(ocrText, parsed.merchant, parsed.type);

        TextView amount = new TextView(this);
        amount.setText((income ? "+" : "-") + fmtYuan(parsed.amountFen));
        amount.setTextSize(32);
        amount.setTypeface(null, Typeface.BOLD);
        amount.setTextColor(ContextCompat.getColor(this,
                income ? R.color.income : R.color.expense));
        amount.setGravity(Gravity.CENTER);
        resultContainer.addView(amount);

        addInfo(resultContainer, "方向", income ? "收入" : "支出");
        addInfo(resultContainer, "商户",
                (parsed.merchant == null || parsed.merchant.isEmpty()) ? "未识别" : parsed.merchant);
        addInfo(resultContainer, "分类", category);
        if (parsed.note != null && !parsed.note.isEmpty()) {
            addInfo(resultContainer, "条目", parsed.note);
        }
        addInfo(resultContainer, "识别度", parsed.confidence + "%");

        buttonRow.setVisibility(View.VISIBLE);
        btnManual.setVisibility(View.GONE);
    }

    private void fail(String msg) {
        Log.w("AutoBillOcr", "失败: " + msg);
        progress.setVisibility(View.GONE);
        tvStatus.setText(msg);
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.expense));
        btnManual.setVisibility(View.VISIBLE);
    }

    private void addInfo(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(ContextCompat.getColor(this, R.color.ink_sub));
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(this, 54),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(ContextCompat.getColor(this, R.color.ink));
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);

        View line = new View(this);
        line.setBackgroundColor(ContextCompat.getColor(this, R.color.line));
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 0.6f)));
    }

    private static String fmtYuan(long fen) {
        long abs = Math.abs(fen);
        long y = abs / 100;
        long f = abs % 100;
        return f == 0 ? String.valueOf(y) : String.format("%d.%02d", y, f);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            try { recognizer.close(); } catch (Throwable ignored) {}
        }
        if (store != null) store.close();
        super.onDestroy();
    }
}
