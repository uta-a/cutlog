package dev.utaa.cutlog.extension.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import dev.utaa.cutlog.extension.editor.Layer;
import dev.utaa.cutlog.extension.editor.LayerPainter;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;
import dev.utaa.cutlog.shaded.androidx.media3.effect.BitmapOverlay;
import java.util.ArrayList;
import java.util.List;

/**
 * 書き出しで文字・スタンプを焼き込む overlay。見た目が時刻で変わる要素があるときだけ毎フレーム描き直す
 * （{@link BitmapOverlay} は bitmap の generation ID が変わったらテクスチャを送り直す）。
 */
final class LayerOverlay extends BitmapOverlay {
    private final List<Layer> layers;
    private final int width;
    private final int height;
    private final boolean animated;
    private final LayerPainter painter = new LayerPainter();
    private Bitmap bitmap;
    private Canvas canvas;
    private boolean drawn;

    LayerOverlay(List<Layer> layers, int width, int height) {
        // 書き出しの途中で編集画面から変えられても影響しないよう、一覧は写しを持つ。
        this.layers = new ArrayList<>(layers);
        this.width = width;
        this.height = height;
        boolean anyAnimated = false;
        for (Layer layer : layers) {
            anyAnimated |= layer.isAnimated();
        }
        this.animated = anyAnimated;
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            Log.i(CaptureInterceptor.TAG, "Overlay first frame at " + presentationTimeUs + "us, animated=" + animated);
        }
        if (!drawn || animated) {
            bitmap.eraseColor(0);
            painter.draw(canvas, width, height, layers, presentationTimeUs / 1000);
            drawn = true;
        }
        return bitmap;
    }
}
