package dev.utaa.cutlog.extension.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** プレビューに重ねる文字・スタンプの層。タップで選び、ドラッグで動かす。 */
@SuppressLint("ViewConstructor")
final class LayerOverlayView extends View {
    interface Listener {
        /** 要素をタップした（null なら何もない所をタップした）。 */
        void onLayerTapped(Layer layer);

        /** 要素を動かした。 */
        void onLayerMoved(Layer layer);
    }

    private final EditSpec spec;
    private final Listener listener;
    private final LayerPainter painter = new LayerPainter();
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Layer selected;
    private Layer dragging;
    private float lastX;
    private float lastY;

    LayerOverlayView(Context context, EditSpec spec, Listener listener) {
        super(context);
        this.spec = spec;
        this.listener = listener;
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(3f);
        selectionPaint.setColor(Color.argb(200, 255, 255, 255));
    }

    void setSelected(Layer layer) {
        selected = layer;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        painter.draw(canvas, width, height, spec.layers);
        if (selected != null && spec.layers.contains(selected)) {
            RectF bounds = painter.bounds(width, height, selected);
            bounds.inset(-12f, -8f);
            canvas.save();
            canvas.rotate(selected.rotation, selected.x * width, selected.y * height);
            canvas.drawRoundRect(bounds, 12f, 12f, selectionPaint);
            canvas.restore();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = hit(event.getX(), event.getY(), width, height);
                lastX = event.getX();
                lastY = event.getY();
                listener.onLayerTapped(dragging);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == null) {
                    return true;
                }
                dragging.x = clamp(dragging.x + (event.getX() - lastX) / width);
                dragging.y = clamp(dragging.y + (event.getY() - lastY) / height);
                lastX = event.getX();
                lastY = event.getY();
                invalidate();
                listener.onLayerMoved(dragging);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = null;
                return true;
            default:
                return true;
        }
    }

    /** 手前の要素から順に、回転を戻した座標で当たり判定する。 */
    private Layer hit(float touchX, float touchY, int width, int height) {
        for (int i = spec.layers.size() - 1; i >= 0; i--) {
            Layer layer = spec.layers.get(i);
            float cx = layer.x * width;
            float cy = layer.y * height;
            double radians = Math.toRadians(-layer.rotation);
            float dx = touchX - cx;
            float dy = touchY - cy;
            float localX = cx + (float) (dx * Math.cos(radians) - dy * Math.sin(radians));
            float localY = cy + (float) (dx * Math.sin(radians) + dy * Math.cos(radians));
            RectF bounds = painter.bounds(width, height, layer);
            bounds.inset(-24f, -24f);
            if (bounds.contains(localX, localY)) {
                return layer;
            }
        }
        return null;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
