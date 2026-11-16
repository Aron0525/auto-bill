package com.aron.autobill.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.Locale;

/** 自绘图表：环形图 + 折线图（零第三方依赖） */
public class Charts {

    /** 环形图的一个扇区 */
    public static class Segment {
        public final int color;
        public final float ratio;   // 0~1
        public Segment(int color, float ratio) {
            this.color = color;
            this.ratio = ratio;
        }
    }

    /** 环形图（分类构成） */
    public static class DonutView extends View {
        private List<Segment> segments;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float strokePx;
        private int emptyColor = 0xFFEDF1F7;

        public DonutView(Context c, float strokeDp) {
            super(c);
            strokePx = Ui.dp(c, strokeDp);
        }

        public void setSegments(List<Segment> segs) {
            segments = segs;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h) - strokePx - Ui.dp(getContext(), 8);
            RectF rect = new RectF((w - size) / 2, (h - size) / 2,
                    (w + size) / 2, (h + size) / 2);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx);
            paint.setStrokeCap(Paint.Cap.BUTT);

            if (segments == null || segments.isEmpty()) {
                paint.setColor(emptyColor);
                canvas.drawArc(rect, 0, 360, false, paint);
                return;
            }
            float start = -90f;
            for (Segment s : segments) {
                float sweep = Math.max(0.5f, s.ratio * 360f);
                paint.setColor(s.color);
                canvas.drawArc(rect, start, sweep, false, paint);
                start += sweep;
            }
        }
    }

    /** 实心饼图（分类占比，扇区带百分比标注） */
    public static class PieView extends View {
        private List<Segment> segments;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gap = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float gapPx;
        private int labelColor = 0xFFFFFFFF;

        public PieView(Context c) {
            super(c);
            gapPx = Ui.dp(c, 1.5f);
            gap.setStyle(Paint.Style.STROKE);
            gap.setStrokeWidth(gapPx);
            gap.setColor(0xFFFFFFFF);
        }

        public void setSegments(List<Segment> segs) {
            segments = segs;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h) - Ui.dp(getContext(), 12);
            RectF rect = new RectF((w - size) / 2, (h - size) / 2,
                    (w + size) / 2, (h + size) / 2);
            fill.setStyle(Paint.Style.FILL);

            if (segments == null || segments.isEmpty()) {
                fill.setColor(0xFFEDF1F7);
                canvas.drawArc(rect, 0, 360, true, fill);
                return;
            }

            float total = 0;
            for (Segment s : segments) total += s.ratio;
            float start = -90f;
            float cx = rect.centerX(), cy = rect.centerY();
            float radius = size / 2;
            text.setTextSize(Ui.dp(getContext(), 12));
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(labelColor);

            for (Segment s : segments) {
                float sweep = total > 0 ? s.ratio / total * 360f : 0;
                if (sweep <= 0) continue;
                fill.setColor(s.color);
                canvas.drawArc(rect, start, sweep, true, fill);
                // 百分比标注（扇区足够大才画，避免重叠）
                if (sweep > 24f) {
                    double mid = Math.toRadians(start + sweep / 2);
                    float lx = cx + (float) Math.cos(mid) * radius * 0.62f;
                    float ly = cy + (float) Math.sin(mid) * radius * 0.62f;
                    canvas.drawText(Math.round(s.ratio / total * 100) + "%",
                            lx, ly + Ui.dp(getContext(), 4), text);
                }
                // 扇区白色分隔
                canvas.drawArc(rect, start, sweep, true, gap);
                start += sweep;
            }
        }
    }

    /** 折线图（趋势）：平滑曲线 + 点击任一点显示金额 */
    public static class LineChartView extends View {
        private float[] values;      // 各点金额（元）
        private String[] labels;     // X 轴标签
        private String[] tips;       // 每个点的完整说明（点击气泡文字）
        private int lineColor = 0xFF2E7CF6;
        private int labelColor = 0xFF8A93A6;
        private int gridColor = 0xFFEDF1F7;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float labelPx;
        private int touchedIndex = -1;   // 当前选中点
        private int labelStep = 2;       // X 轴标签显示步长
        private float[] xs, ys;          // 各点屏幕坐标（点击命中和气泡定位用）

        public LineChartView(Context c) {
            super(c);
            labelPx = Ui.dp(c, 10);
            textPaint.setTextSize(labelPx);
            textPaint.setTextAlign(Paint.Align.CENTER);
            bubblePaint.setColor(0xF0222222);
            bubbleTextPaint.setColor(0xFFFFFFFF);
            bubbleTextPaint.setTextSize(Ui.dp(c, 11));
            bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
        }

        public void setData(float[] values, String[] labels, int touchedIndex) {
            setData(values, labels, touchedIndex, 2, null);
        }

        public void setData(float[] values, String[] labels, int touchedIndex, int labelStep) {
            setData(values, labels, touchedIndex, labelStep, null);
        }

        /** tips：每个点的气泡文字；传 null 则自动显示「x 元」 */
        public void setData(float[] values, String[] labels, int touchedIndex, int labelStep,
                            String[] tips) {
            this.values = values;
            this.labels = labels;
            this.tips = tips;
            this.touchedIndex = touchedIndex;
            this.labelStep = Math.max(1, labelStep);
            invalidate();
        }

        /** 点到最近的数据点：命中范围不小于半格宽，手指粗也选得中 */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (values == null || values.length == 0 || xs == null) return super.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    int nearest = nearestIndex(ev.getX());
                    if (nearest >= 0 && nearest != touchedIndex) {
                        touchedIndex = nearest;
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    performClick();
                    return true;
                default:
                    break;
            }
            return super.onTouchEvent(ev);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private int nearestIndex(float x) {
            if (xs == null || xs.length == 0) return -1;
            int best = 0;
            float bestD = Math.abs(x - xs[0]);
            for (int i = 1; i < xs.length; i++) {
                float d = Math.abs(x - xs[i]);
                if (d < bestD) { bestD = d; best = i; }
            }
            float limit = xs.length > 1
                    ? Math.max(Ui.dp(getContext(), 22), (xs[1] - xs[0]) * 0.75f)
                    : Ui.dp(getContext(), 60);
            return bestD <= limit ? best : -1;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (values == null || values.length == 0) return;
            float w = getWidth(), h = getHeight();
            float padL = Ui.dp(getContext(), 8);
            float padB = Ui.dp(getContext(), 22);
            float padT = Ui.dp(getContext(), 12);
            float chartW = w - padL * 2;
            float chartH = h - padB - padT;

            // 网格横线
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 0.8f));
            paint.setColor(gridColor);
            for (int i = 0; i <= 3; i++) {
                float y = padT + chartH * i / 3f;
                canvas.drawLine(padL, y, w - padL, y, paint);
            }

            // 纵轴基准恒为 0：曲线永远画在基线上方，不下负轴
            float max = 0;
            for (float v : values) if (v > max) max = v;
            if (max <= 0) max = 1;   // 全 0 时给最小刻度，避免除零

            // Catmull-Rom 样条转三次贝塞尔：真正的平滑曲线，
            // 每个数据点的切线由相邻点决定，曲线过点且处处光滑
            Path path = new Path();
            float step = values.length > 1 ? chartW / (values.length - 1) : chartW;
            xs = new float[values.length];
            ys = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                xs[i] = padL + step * i;
                float v = Math.max(0, values[i]);   // 负值钳到 0
                ys[i] = padT + chartH * (1 - v / max);
            }
            path.moveTo(xs[0], ys[0]);
            for (int i = 0; i < values.length - 1; i++) {
                float x0 = i == 0 ? xs[0] : xs[i - 1];
                float y0 = i == 0 ? ys[0] : ys[i - 1];
                float x1 = xs[i],     y1 = ys[i];
                float x2 = xs[i + 1], y2 = ys[i + 1];
                float x3 = i + 2 < values.length ? xs[i + 2] : xs[i + 1];
                float y3 = i + 2 < values.length ? ys[i + 2] : ys[i + 1];
                // 切线向量 / 6 = Catmull-Rom 张力 0.5 的贝塞尔控制点
                float c1x = x1 + (x2 - x0) / 6f;
                float c1y = y1 + (y2 - y0) / 6f;
                float c2x = x2 - (x3 - x1) / 6f;
                float c2y = y2 - (y3 - y1) / 6f;
                // 控制点纵向钳在绘图区内，防止样条过冲冲出基线/上边界
                c1y = clamp(c1y, padT, padT + chartH);
                c2y = clamp(c2y, padT, padT + chartH);
                path.cubicTo(c1x, c1y, c2x, c2y, x2, y2);
            }
            paint.setColor(lineColor);
            paint.setStrokeWidth(Ui.dp(getContext(), 3f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);

            // 选中点：竖向指引线
            if (touchedIndex >= 0 && touchedIndex < xs.length) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 1f));
                paint.setColor(0x332E7CF6);
                canvas.drawLine(xs[touchedIndex], padT, xs[touchedIndex], padT + chartH, paint);
            }

            // 数据点
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < values.length; i++) {
                boolean sel = i == touchedIndex;
                paint.setColor(lineColor);
                canvas.drawCircle(xs[i], ys[i], Ui.dp(getContext(), sel ? 5f : 2.5f), paint);
                if (sel) {   // 选中点挖白心，更醒目
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(xs[i], ys[i], Ui.dp(getContext(), 2.2f), paint);
                }
            }

            // X 轴标签（按 labelStep 间隔显示，避免拥挤）
            textPaint.setColor(labelColor);
            textPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < values.length; i += labelStep) {
                if (labels != null && i < labels.length) {
                    canvas.drawText(labels[i], xs[i], h - Ui.dp(getContext(), 6), textPaint);
                }
            }

            // 选中气泡：该点金额
            if (touchedIndex >= 0 && touchedIndex < values.length) {
                String tip = (tips != null && touchedIndex < tips.length && tips[touchedIndex] != null)
                        ? tips[touchedIndex] : fmtYuan(values[touchedIndex]) + " 元";
                drawBubble(canvas, tip, xs[touchedIndex], ys[touchedIndex], padT);
            }
        }

        private void drawBubble(Canvas canvas, String text, float px, float py, float padT) {
            float padH = Ui.dp(getContext(), 8);
            float bw = bubbleTextPaint.measureText(text) + padH * 2;
            float bh = Ui.dp(getContext(), 22);
            // 默认画在点上方；贴顶则翻到下方
            float by = py - bh - Ui.dp(getContext(), 8);
            if (by < padT - Ui.dp(getContext(), 4)) by = py + Ui.dp(getContext(), 8);
            float bx = px - bw / 2;
            // 左右不出界
            float left = Ui.dp(getContext(), 2);
            float right = getWidth() - Ui.dp(getContext(), 2);
            if (bx < left) bx = left;
            if (bx + bw > right) bx = right - bw;

            RectF r = new RectF(bx, by, bx + bw, by + bh);
            canvas.drawRoundRect(r, Ui.dp(getContext(), 6), Ui.dp(getContext(), 6), bubblePaint);
            Paint.FontMetrics fm = bubbleTextPaint.getFontMetrics();
            float ty = by + bh / 2 - (fm.ascent + fm.descent) / 2;
            canvas.drawText(text, bx + bw / 2, ty, bubbleTextPaint);
        }

        private static float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        private static String fmtYuan(float v) {
            if (Math.abs(v - Math.round(v)) < 0.005f) {
                return String.format(Locale.CHINA, "%d", Math.round(v));
            }
            return String.format(Locale.CHINA, "%.2f", v);
        }
    }
}
