package dev.utaa.cutlog.extension.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.List;

/** 文字・スタンプの描画。プレビューと書き出しで同じコードを使い、見た目を揃える。 */
public final class LayerPainter {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LayerPainter() {
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setTextAlign(Paint.Align.CENTER);
        stroke.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.argb(170, 0, 0, 0));
    }

    public void draw(Canvas canvas, int width, int height, List<Layer> layers) {
        for (Layer layer : layers) {
            draw(canvas, width, height, layer);
        }
    }

    public void draw(Canvas canvas, int width, int height, Layer layer) {
        canvas.save();
        canvas.rotate(layer.rotation, layer.x * width, layer.y * height);
        if (layer instanceof TextSpec) {
            drawText(canvas, width, height, (TextSpec) layer);
        }
        canvas.restore();
    }

    private void drawText(Canvas canvas, int width, int height, TextSpec text) {
        float size = text.size * Math.min(width, height);
        fill.setTextSize(size);
        fill.setColor(text.color);
        stroke.setTextSize(size);
        stroke.setStrokeWidth(size * 0.12f);
        float baseline = text.y * height - (fill.ascent() + fill.descent()) / 2f;
        canvas.drawText(text.text, text.x * width, baseline, stroke);
        canvas.drawText(text.text, text.x * width, baseline, fill);
    }

    /** 回転前の外接矩形。タップ判定と選択枠に使う。 */
    public RectF bounds(int width, int height, Layer layer) {
        float size = layer.size * Math.min(width, height);
        float halfWidth = size / 2f;
        if (layer instanceof TextSpec) {
            fill.setTextSize(size);
            halfWidth = fill.measureText(((TextSpec) layer).text) / 2f;
        }
        float cx = layer.x * width;
        float cy = layer.y * height;
        return new RectF(cx - halfWidth, cy - size * 0.7f, cx + halfWidth, cy + size * 0.7f);
    }
}
