package dev.utaa.cutlog.extension.editor;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;

/**
 * 撮影画面のシャッターの並びに cutlog のボタンを足す。
 *
 * <p>setlog の撮影画面は、シャッター（{@code CameraSmileButton}）の左にフラッシュボタン（50dp）を置き、
 * 画面の大きさに合わせて位置を動かしている。ボタンの位置は「シャッターからフラッシュまでの距離」を
 * 1 単位とした slot で指定する（フラッシュは -1）。レイアウトのたびに合わせ直す。
 */
final class CameraChrome {
    private static final String SHUTTER_CLASS = "com.newchat.setlog.feature.camera.CameraSmileButton";

    private CameraChrome() {}

    /** @return 追加した場合 true。シャッターが見つからない、または追加済みなら false。 */
    static boolean place(View cameraRoot, View control, String tag, float slot) {
        View shutter = find(cameraRoot);
        if (shutter == null || !(shutter.getParent() instanceof FrameLayout)) {
            Log.w(CaptureInterceptor.TAG, "Shutter button not found; skipping " + tag + ".");
            return false;
        }
        FrameLayout parent = (FrameLayout) shutter.getParent();
        if (parent.findViewWithTag(tag) != null) {
            return false;
        }
        Context context = parent.getContext();
        control.setTag(tag);
        int size = Ui.dp(context, 50);
        parent.addView(control, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));
        parent.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> align(control, shutter, flashOf(parent, shutter), slot));
        return true;
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

    private static void align(View control, View shutter, View flash, float slot) {
        float shutterX = shutter.getLeft() + shutter.getTranslationX() + shutter.getWidth() / 2f;
        float centerY = shutter.getTop() + shutter.getTranslationY() + shutter.getHeight() / 2f;
        float unit = Ui.dp(control.getContext(), 89);
        if (flash != null && flash.getWidth() > 0) {
            unit = shutterX - (flash.getLeft() + flash.getTranslationX() + flash.getWidth() / 2f);
            centerY = flash.getTop() + flash.getTranslationY() + flash.getHeight() / 2f;
            control.setVisibility(flash.getVisibility() == View.GONE ? View.GONE : View.VISIBLE);
        }
        control.setTranslationX(shutterX + unit * slot - control.getWidth() / 2f);
        control.setTranslationY(centerY - control.getHeight() / 2f);
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
}
