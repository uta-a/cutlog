package dev.utaa.cutlog.extension.record;

import android.content.Context;
import android.view.View;
import dev.utaa.cutlog.extension.editor.DurationButton;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;
import dev.utaa.cutlog.extension.pref.Prefs;

/**
 * 撮影する長さの選択。パッチが本体の録画設定（{@code CollectionCameraConfig.recordDurationMs}、公式は 2000ms）
 * を読む箇所すべての直後に {@link #recordDurationMs} を挟み、選んだ長さに置き換える。
 *
 * <p>本体はシャッターを押すと録画を始めてすぐ停止を要求し、この長さに達するまで録り続ける。
 * 停止要求から一定時間で強制的に止める予備のタイマー（公式は 2000ms 固定）も {@link #stopFallbackDelayMs} で延ばす。
 */
public final class RecordDuration {
    /** 公式の録画設定。これ以外の値（タイムラプス等）は置き換えない。 */
    private static final long OFFICIAL_RECORD_MS = 2000L;
    /** 予備のタイマーは、録画が自然に止まるより少し後に鳴らす。 */
    private static final long FALLBACK_MARGIN_MS = 1000L;

    private RecordDuration() {}

    /** 選んでいる長さ（ミリ秒）。まだ Activity を見ていなければ公式の長さ。 */
    public static long selectedMs() {
        Context context = CaptureInterceptor.appContextOrNull();
        return context == null ? OFFICIAL_RECORD_MS : Prefs.recordSeconds(context) * 1000L;
    }

    /** 本体が録画設定の長さを読んだ直後に呼ばれる。 */
    public static long recordDurationMs(long official) {
        return official == OFFICIAL_RECORD_MS ? selectedMs() : official;
    }

    /** 本体の予備の停止タイマーを仕掛ける直前に呼ばれる。 */
    public static long stopFallbackDelayMs(long official) {
        return Math.max(official, selectedMs() + FALLBACK_MARGIN_MS);
    }

    /** 撮影画面の View ができたときに呼ばれる。長さを選ぶボタンを足す。 */
    public static void onCameraViewCreated(View view) {
        try {
            DurationButton.attach(view);
        } catch (RuntimeException e) {
            android.util.Log.w(CaptureInterceptor.TAG, "Cannot add the duration button.", e);
        }
    }
}
