package dev.utaa.cutlog.extension.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * 編集画面のタイムライン。上の段に動画のサムネイル、その下に文字・スタンプの表示区間を 1 行ずつ並べ、
 * 再生位置を縦線で示す。何も無い所や動画の段をドラッグすると再生位置が動き、要素の行をタップすると選ぶ。
 */
@SuppressLint("ViewConstructor")
final class TimelineView extends View {
    interface Listener {
        void onScrubStart();

        void onScrub(long positionMs);

        void onScrubEnd();

        void onLayerTapped(Layer layer);
    }

    private final EditSpec spec;
    private final Listener listener;
    private final float inset;
    private final float videoHeight;
    private final float rowHeight;
    private final float gap;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private List<Bitmap> thumbnails = new ArrayList<>();
    private long durationMs = 1;
    private long positionMs;
    private Layer selected;
    private boolean scrubbing;

    TimelineView(Context context, EditSpec spec, Listener listener) {
        super(context);
        this.spec = spec;
        this.listener = listener;
        inset = Ui.dp(context, 16);
        videoHeight = Ui.dp(context, 44);
        rowHeight = Ui.dp(context, 28);
        gap = Ui.dp(context, 6);
        radius = Ui.dp(context, 8);
        labelPaint.setColor(Ui.TEXT_PRIMARY);
        labelPaint.setTextSize(Ui.dp(context, 12));
    }

    void setDurationMs(long durationMs) {
        this.durationMs = Math.max(1, durationMs);
        invalidate();
    }

    void setPositionMs(long positionMs) {
        if (!scrubbing && positionMs != this.positionMs) {
            this.positionMs = positionMs;
            invalidate();
        }
    }

    void setSelected(Layer layer) {
        selected = layer;
        invalidate();
    }

    void setThumbnails(List<Bitmap> thumbnails) {
        this.thumbnails = thumbnails;
        invalidate();
    }

    /** 要素の数が変わったら呼ぶ。高さが変わる。 */
    void layersChanged() {
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int rows = spec.layers.size();
        int height = Math.round(gap + videoHeight + rows * (gap + rowHeight) + gap);
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
    }

    private float trackWidth() {
        return getWidth() - inset * 2;
    }

    private float xOf(long ms) {
        return inset + trackWidth() * Math.max(0, Math.min(ms, durationMs)) / durationMs;
    }

    private long msOf(float x) {
        float ratio = (x - inset) / trackWidth();
        return Math.round(Math.max(0f, Math.min(1f, ratio)) * durationMs);
    }

    private float rowTop(int index) {
        return gap + videoHeight + gap + index * (rowHeight + gap);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawVideo(canvas);
        for (int i = 0; i < spec.layers.size(); i++) {
            drawLayer(canvas, spec.layers.get(i), rowTop(i));
        }
        drawPlayhead(canvas);
    }

    private void drawVideo(Canvas canvas) {
        rect.set(inset, gap, inset + trackWidth(), gap + videoHeight);
        canvas.save();
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.SURFACE_RAISED);
        canvas.drawRect(rect, paint);
        int count = thumbnails.size();
        if (count > 0) {
            float tileWidth = rect.width() / count;
            RectF tile = new RectF();
            for (int i = 0; i < count; i++) {
                Bitmap bitmap = thumbnails.get(i);
                tile.set(rect.left + tileWidth * i, rect.top, rect.left + tileWidth * (i + 1), rect.bottom);
                centerCrop(bitmap, tile.width() / tile.height());
                canvas.drawBitmap(bitmap, src, tile, bitmapPaint);
            }
        }
        canvas.restore();
    }

    /** 縦横比 aspect の枠に合わせて、bitmap の中央を切り出す範囲を src に入れる。 */
    private void centerCrop(Bitmap bitmap, float aspect) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w / (float) h > aspect) {
            int cropped = Math.round(h * aspect);
            src.set((w - cropped) / 2, 0, (w + cropped) / 2, h);
        } else {
            int cropped = Math.round(w / aspect);
            src.set(0, (h - cropped) / 2, w, (h + cropped) / 2);
        }
    }

    private void drawLayer(Canvas canvas, Layer layer, float top) {
        rect.set(xOf(layer.startMs), top, xOf(layer.endMs(durationMs)), top + rowHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.SURFACE_RAISED);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (layer == selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 2));
            paint.setColor(Ui.ACCENT);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        float padding = Ui.dp(getContext(), 8);
        CharSequence label = TextUtils.ellipsize(
                layer.label(), labelPaint, Math.max(0, rect.width() - padding * 2), TextUtils.TruncateAt.END);
        float baseline = rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f;
        canvas.drawText(label, 0, label.length(), rect.left + padding, baseline, labelPaint);
    }

    private void drawPlayhead(Canvas canvas) {
        float x = xOf(positionMs);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        float half = Ui.dp(getContext(), 1);
        canvas.drawRect(x - half, 0, x + half, getHeight(), paint);
        canvas.drawCircle(x, gap / 2f + half, Ui.dp(getContext(), 4), paint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Layer hit = layerAt(event.getX(), event.getY());
                if (hit != null) {
                    listener.onLayerTapped(hit);
                    return true;
                }
                scrubbing = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                listener.onScrubStart();
                scrubTo(event.getX());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (scrubbing) {
                    scrubTo(event.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    scrubbing = false;
                    listener.onScrubEnd();
                }
                return true;
            default:
                return true;
        }
    }

    private void scrubTo(float x) {
        positionMs = msOf(x);
        invalidate();
        listener.onScrub(positionMs);
    }

    private Layer layerAt(float x, float y) {
        for (int i = 0; i < spec.layers.size(); i++) {
            float top = rowTop(i);
            if (y < top || y > top + rowHeight) {
                continue;
            }
            Layer layer = spec.layers.get(i);
            if (x >= xOf(layer.startMs) && x <= xOf(layer.endMs(durationMs))) {
                return layer;
            }
        }
        return null;
    }
}
