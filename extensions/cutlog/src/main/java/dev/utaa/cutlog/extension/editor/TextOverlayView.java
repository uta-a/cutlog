package dev.utaa.cutlog.extension.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** プレビューに重ねるテキスト層。ドラッグで選択中のテキストを動かす。 */
@SuppressLint("ViewConstructor")
final class TextOverlayView extends View {
    interface Listener {
        void onSelectionChanged(TextSpec selected);
    }

    private final EditSpec spec;
    private final Listener listener;
    private final TextPainter painter = new TextPainter();
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextSpec selected;

    TextOverlayView(Context context, EditSpec spec, Listener listener) {
        super(context);
        this.spec = spec;
        this.listener = listener;
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(3f);
        selectionPaint.setColor(Color.argb(200, 255, 255, 255));
    }

    TextSpec selected() {
        return selected;
    }

    void select(TextSpec text) {
        selected = text;
        listener.onSelectionChanged(text);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        painter.draw(canvas, width, height, spec.texts);
        if (selected != null && spec.texts.contains(selected)) {
            RectF bounds = painter.bounds(width, height, selected);
            bounds.inset(-12f, -8f);
            canvas.drawRoundRect(bounds, 12f, 12f, selectionPaint);
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
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            TextSpec hit = null;
            for (int i = spec.texts.size() - 1; i >= 0; i--) {
                TextSpec text = spec.texts.get(i);
                RectF bounds = painter.bounds(width, height, text);
                bounds.inset(-24f, -24f);
                if (bounds.contains(event.getX(), event.getY())) {
                    hit = text;
                    break;
                }
            }
            if (hit != selected) {
                select(hit);
            }
            return hit != null;
        }
        if (selected != null && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            selected.x = clamp(event.getX() / width);
            selected.y = clamp(event.getY() / height);
            invalidate();
            return true;
        }
        return selected != null;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
