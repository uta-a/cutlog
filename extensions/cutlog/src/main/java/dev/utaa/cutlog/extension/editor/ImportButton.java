package dev.utaa.cutlog.extension.editor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;

/**
 * 撮影画面のシャッターの右に置く「ファイルから」ボタン。
 *
 * <p>setlog の撮影画面は、シャッター（{@code CameraSmileButton}）の左にフラッシュボタン（50dp）を置き、
 * 画面の大きさに合わせて位置を動かしている。レイアウトのたびに、シャッターを挟んでフラッシュと
 * 左右対称の位置へ合わせる。
 */
@SuppressLint("ViewConstructor")
public final class ImportButton extends View {
    private static final String SHUTTER_CLASS = "com.newchat.setlog.feature.camera.CameraSmileButton";
    private static final String TAG = "cutlog-import-button";

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path mountain = new Path();

    private ImportButton(Context context, Runnable onClick) {
        super(context);
        setTag(TAG);
        setContentDescription("ファイルから");
        setClickable(true);
        setOnClickListener(v -> onClick.run());
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 撮影画面の View にボタンを足す。シャッターが見つからなければ何もしない。 */
    public static void attach(View cameraRoot, Runnable onClick) {
        View shutter = find(cameraRoot);
        if (shutter == null || !(shutter.getParent() instanceof FrameLayout)) {
            Log.w(CaptureInterceptor.TAG, "Shutter button not found; skipping the import button.");
            return;
        }
        FrameLayout parent = (FrameLayout) shutter.getParent();
        if (parent.findViewWithTag(TAG) != null) {
            return;
        }
        Context context = parent.getContext();
        ImportButton button = new ImportButton(context, onClick);
        int size = Ui.dp(context, 50);
        parent.addView(button, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> button.alignTo(shutter, flashOf(parent, shutter)));
    }

    /** シャッターと同じ親にある、シャッター以外の ImageButton（フラッシュ）。 */
    private static View flashOf(ViewGroup parent, View shutter) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != shutter && child instanceof ImageButton) {
                return child;
            }
        }
        return null;
    }

    private void alignTo(View shutter, View flash) {
        float shutterX = shutter.getLeft() + shutter.getTranslationX() + shutter.getWidth() / 2f;
        float shutterY = shutter.getTop() + shutter.getTranslationY() + shutter.getHeight() / 2f;
        float offset = Ui.dp(getContext(), 89);
        float centerY = shutterY;
        if (flash != null && flash.getWidth() > 0) {
            offset = shutterX - (flash.getLeft() + flash.getTranslationX() + flash.getWidth() / 2f);
            centerY = flash.getTop() + flash.getTranslationY() + flash.getHeight() / 2f;
            setVisibility(flash.getVisibility() == View.GONE ? View.GONE : View.VISIBLE);
        }
        setTranslationX(shutterX + offset - getWidth() / 2f);
        setTranslationY(centerY - getHeight() / 2f);
    }

    private static View find(View view) {
        if (SHUTTER_CLASS.equals(view.getClass().getName())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 写真ライブラリの形（角丸の枠に山と太陽）。フラッシュボタンと同じく白い線で描く。 */
    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        // フラッシュのアイコンと同じくらいの大きさ（ボタンの約半分）に描く。
        float unit = Math.min(w, h) / 36f;
        stroke.setStrokeWidth(unit * 1.8f);
        float cx = w / 2f;
        float cy = h / 2f;
        frame.set(cx - unit * 8, cy - unit * 7, cx + unit * 8, cy + unit * 7);
        canvas.drawRoundRect(frame, unit * 2.5f, unit * 2.5f, stroke);
        mountain.reset();
        mountain.moveTo(cx - unit * 8, cy + unit * 4);
        mountain.lineTo(cx - unit * 2.5f, cy - unit * 1.5f);
        mountain.lineTo(cx + unit * 2, cy + unit * 3);
        mountain.lineTo(cx + unit * 4, cy + unit * 1);
        mountain.lineTo(cx + unit * 8, cy + unit * 5);
        canvas.drawPath(mountain, stroke);
        canvas.drawCircle(cx + unit * 3.5f, cy - unit * 3, unit * 1.5f, stroke);
    }
}
