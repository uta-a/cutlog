package dev.utaa.cutlog.extension.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.List;

/** テキストの描画。プレビューと書き出しで同じコードを使い、見た目を揃える。 */
public final class TextPainter {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TextPainter() {
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setTextAlign(Paint.Align.CENTER);
        stroke.setTypeface(Typeface.DEFAULT_BOLD);
        stroke.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.argb(170, 0, 0, 0));
    }

    public void draw(Canvas canvas, int width, int height, List<TextSpec> texts) {
        for (TextSpec text : texts) {
            draw(canvas, width, height, text);
        }
    }

    public void draw(Canvas canvas, int width, int height, TextSpec text) {
        float size = text.size * Math.min(width, height);
        fill.setTextSize(size);
        fill.setColor(text.color);
        stroke.setTextSize(size);
        stroke.setStrokeWidth(size * 0.12f);
        float baseline = text.y * height - (fill.ascent() + fill.descent()) / 2f;
        canvas.drawText(text.text, text.x * width, baseline, stroke);
        canvas.drawText(text.text, text.x * width, baseline, fill);
    }

    /** タップ判定用のおおよその外接矩形。 */
    public RectF bounds(int width, int height, TextSpec text) {
        float size = text.size * Math.min(width, height);
        fill.setTextSize(size);
        float halfWidth = fill.measureText(text.text) / 2f;
        float cx = text.x * width;
        float cy = text.y * height;
        return new RectF(cx - halfWidth, cy - size * 0.7f, cx + halfWidth, cy + size * 0.7f);
    }
}
