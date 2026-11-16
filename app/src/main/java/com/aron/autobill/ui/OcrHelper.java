package com.aron.autobill.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import com.aron.autobill.core.OcrLine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ML Kit 中文 OCR 封装（本地运行，不联网）。
 *
 * 准确率优化的关键：不再只返回 result.getText() 一坨纯文本，
 * 而是保留 ML Kit 的「行（Text.Line）」结构 + 每行的包围盒。
 * 原因：金额和商户必须在同一行才有因果关系。
 * 压成一段文字后，「商品小计 12.00 / 实付 99.92」的所有数字混在一个池子里，
 * 只能靠关键词猜，商户也会从别行误抓。
 */
public class OcrHelper {

    /**
     * 行模型与视觉排序都在 core.OcrLine（纯 JVM，可在自检里直接验证）：
     * ML Kit 给出的 block 顺序不是视觉顺序，必须按包围盒重排后再交给解析器。
     */
    public interface Callback {
        /** 兼容旧调用：纯文本（行以 \n 分隔） */
        void onText(String text);
        void onError(String message);
    }

    /** 新回调：拿到行结构，供解析器按行归属金额/商户 */
    public interface LineCallback {
        void onLines(List<OcrLine> lines, String fullText);
        void onError(String message);
    }

    public static Bitmap decodeBitmap(Context ctx, Uri uri, int maxDim) {
        InputStream is = openStream(ctx, uri);
        if (is == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(is, null, bounds);
        try { is.close(); } catch (Exception ignored) {}

        int sample = 1;
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        is = openStream(ctx, uri);
        if (is == null) return null;
        Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
        try { is.close(); } catch (Exception ignored) {}
        return bmp;
    }

    /**
     * file:// 直接用 FileInputStream（分区存储下 ContentResolver 对共享存储的
     * file scheme 会被拒 EACCES）；content:// 走 ContentResolver（带授权）。
     */
    private static InputStream openStream(Context ctx, Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                return new java.io.FileInputStream(uri.getPath());
            }
            return ctx.getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            Log.w("OcrHelper", "打开文件失败: " + e);
            return null;
        }
    }

    // ==================== 图像预处理：为「小数点识别」做的增强 ====================

    /**
     * 二值化增强：提高对比度 + 灰度 + 锐化。
     *
     * 为什么能改善小数点识别：
     * ML Kit 把「.」和「，」搞混、或直接丢掉，根本原因是笔画太细——
     * 小数点只有 1~2 个像素高，缩放到 2000px 后糊成一团。
     * 提高对比度 + 轻度锐化能让孤立小点在二值化时保留下来。
     *
     * 注意：不能用纯黑白二值化（会加重对抗锯齿小点的破坏），
     * 这里用「对比度拉伸」+「锐化卷积」，保留灰度过渡。
     */
    public static Bitmap enhance(Bitmap src) {
        if (src == null) return null;
        try {
            int w = src.getWidth(), h = src.getHeight();
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            // 1) 灰度 + 对比度 1.45 倍（围绕中灰拉伸）
            float contrast = 1.45f;
            float translate = (-0.5f * contrast + 0.5f) * 255f;
            ColorMatrix gray = new ColorMatrix();
            gray.setSaturation(0f);
            ColorMatrix con = new ColorMatrix(new float[]{
                    contrast, 0, 0, 0, translate,
                    0, contrast, 0, 0, translate,
                    0, 0, contrast, 0, translate,
                    0, 0, 0, 1, 0
            });
            gray.postConcat(con);
            p.setColorFilter(new ColorMatrixColorFilter(gray));
            cv.drawBitmap(src, 0, 0, p);
            return out;
        } catch (Throwable t) {
            Log.w("OcrHelper", "图像增强失败，退回原图: " + t);
            return src;
        }
    }

    /**
     * 整数倍放大：小数点这种极小特征，放大后 ML Kit 更容易分辨。
     * 只在图片较小时放大（大图放大会爆内存，收益也有限）。
     */
    public static Bitmap upscale(Bitmap src, int targetMinSide) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int minSide = Math.min(w, h);
        if (minSide >= targetMinSide) return src;
        float scale = Math.min(2.0f, (float) targetMinSide / minSide);
        if (scale <= 1.05f) return src;
        try {
            int nw = Math.round(w * scale), nh = Math.round(h * scale);
            // 上限保护：超过 4000px 就算了，内存撑不住
            if (nw > 4000 || nh > 4000) return src;
            Bitmap out = Bitmap.createScaledBitmap(src, nw, nh, true);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            Log.w("OcrHelper", "放大失败: " + t);
            return src;
        }
    }

    // ==================== 识别入口 ====================

    /** 旧入口：只要纯文本（内部走结构化识别后拼行） */
    public static void recognize(Context ctx, Uri uri, Callback callback) {
        recognizeLines(ctx, uri, new LineCallback() {
            @Override public void onLines(List<OcrLine> lines, String fullText) {
                callback.onText(fullText);
            }
            @Override public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /** 识别入口：先原图识别，失败或金额可疑时用增强图重试一次 */
    public static void recognizeLines(Context ctx, Uri uri, LineCallback callback) {
        Bitmap raw = decodeBitmap(ctx, uri, 2200);
        if (raw == null) {
            callback.onError("无法读取这张图片");
            return;
        }
        // 放大到短边至少 1200px：小数点、千分位逗号这些小特征才清晰
        Bitmap prepared = upscale(raw, 1200);
        runOnce(ctx, prepared, new LineCallback() {
            @Override public void onLines(List<OcrLine> lines, String fullText) {
                // 第一遍结果里「一个带小数的金额都没有」= 小数点大概率被吃掉了，
                // 用对比度增强后的图再来一遍
                if (hasDecimalAmount(fullText) || lines.isEmpty()) {
                    callback.onLines(lines, fullText);
                    return;
                }
                Bitmap enhanced = enhance(prepared);
                runOnce(ctx, enhanced, new LineCallback() {
                    @Override public void onLines(List<OcrLine> lines2, String text2) {
                        // 两遍取「更像账单」的那一份：带小数的优先，其次行数多的
                        if (score(text2, lines2) >= score(fullText, lines)) {
                            callback.onLines(lines2, text2);
                        } else {
                            callback.onLines(lines, fullText);
                        }
                    }
                    @Override public void onError(String msg) {
                        // 增强图失败就用第一遍结果，不打扰用户
                        callback.onLines(lines, fullText);
                    }
                });
            }
            @Override public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    /** 文本里是否存在「带 1~2 位小数的金额」 */
    private static boolean hasDecimalAmount(String text) {
        if (text == null) return false;
        return java.util.regex.Pattern.compile("\\d+[.,]\\d{1,2}\\s*(?:元|¥)?")
                .matcher(text).find();
    }

    /** 结果打分：带小数金额加分，行数多加分 */
    private static int score(String text, List<OcrLine> lines) {
        int s = 0;
        if (text != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\d+[.,]\\d{1,2}").matcher(text);
            while (m.find()) s += 10;
            if (text.contains("元")) s += 5;
            if (text.contains("¥") || text.contains("￥")) s += 5;
        }
        if (lines != null) s += Math.min(lines.size(), 20);
        return s;
    }

    private static void runOnce(Context ctx, Bitmap bitmap, LineCallback callback) {
        if (bitmap == null) {
            callback.onError("图片处理失败");
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(new OnSuccessListener<com.google.mlkit.vision.text.Text>() {
                    @Override public void onSuccess(com.google.mlkit.vision.text.Text result) {
                        try { recognizer.close(); } catch (Throwable ignored) {}
                        List<OcrLine> lines = new ArrayList<>();
                        for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
                            for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                String t = line.getText();
                                if (t == null || t.trim().isEmpty()) continue;
                                android.graphics.Rect r = line.getBoundingBox();
                                lines.add(new OcrLine(
                                        t.trim(),
                                        r == null ? 0 : r.left,
                                        r == null ? 0 : r.top,
                                        r == null ? 0 : r.right,
                                        r == null ? 0 : r.bottom));
                            }
                        }
                        // 关键：ML Kit 的 block 顺序不是视觉顺序，先按包围盒恢复
                        // 「上→下、同行左→右」，文本也按排好序的行拼
                        OcrLine.sortVisual(lines);
                        callback.onLines(lines, OcrLine.joinText(lines));
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override public void onFailure(Exception e) {
                        try { recognizer.close(); } catch (Throwable ignored) {}
                        callback.onError("文字识别失败：" + e.getMessage());
                    }
                });
    }
}
