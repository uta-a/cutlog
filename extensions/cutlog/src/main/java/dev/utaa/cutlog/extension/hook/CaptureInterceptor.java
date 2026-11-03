package dev.utaa.cutlog.extension.hook;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import dev.utaa.cutlog.extension.editor.EditorDialog;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * 録画完了時（本体の CollectionCamera が結果を返す直前）に呼ばれる入口。
 *
 * <p>編集画面を開けたときは結果を預かり、編集画面の操作が終わってから {@link CaptureHost} で本体へ返す。
 * 開けないときは何もせず、本体の処理をそのまま続けさせる。
 */
public final class CaptureInterceptor {
    public static final String TAG = "cutlog";

    /** Phase 1 の検証用。置いてある間は、すべての録画をこの動画に差し替えてから編集画面を開く。 */
    private static final String REPLACEMENT_PATH =
            "Android/data/com.newchat.setlog/files/cutlog/replacement.mp4";

    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static Context appContext;
    private static boolean delivering;

    private CaptureInterceptor() {}

    /** MainActivity.onResume から呼ばれる。 */
    public static void onActivityResumed(Activity activity) {
        currentActivity = new WeakReference<>(activity);
        appContext = activity.getApplicationContext();
    }

    public static Context appContext() {
        if (appContext == null) {
            throw new IllegalStateException("cutlog has not seen an activity yet.");
        }
        return appContext;
    }

    /**
     * @return true なら cutlog が結果を預かった。本体はここで処理を止める。
     */
    public static boolean onCaptured(CaptureHost host, Object result, Uri captured) {
        if (delivering) {
            return false;
        }
        try {
            Activity activity = currentActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "No activity for the editor; keeping the official flow.");
                return false;
            }
            if (!"file".equals(captured.getScheme()) || captured.getPath() == null) {
                Log.w(TAG, "Unsupported capture uri: " + captured);
                return false;
            }
            File capturedFile = new File(captured.getPath());
            CaptureReplacement replacement = applyTestReplacement(capturedFile);
            new EditorDialog(activity, new CaptureSession(host, result, capturedFile, replacement)).show();
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot open the editor; keeping the official flow.", e);
            return false;
        }
    }

    private static CaptureReplacement applyTestReplacement(File captured) {
        File source = new File(Environment.getExternalStorageDirectory(), REPLACEMENT_PATH);
        if (!source.isFile()) {
            Log.i(TAG, "No test replacement at " + source);
            return null;
        }
        try {
            CaptureReplacement replacement = VideoFiles.readMetadata(source);
            VideoFiles.copyOver(source, captured);
            Log.i(TAG, "Replaced capture: " + replacement);
            return replacement;
        } catch (IOException e) {
            Log.w(TAG, "Keeping the original capture.", e);
            return null;
        }
    }

    /** 本体の G0 を呼び直す間だけ、自分自身のフックを素通りさせる。 */
    static void deliver(CaptureHost host, Object result, CaptureReplacement replacement) {
        delivering = true;
        try {
            host.cutlogDeliver(result, replacement);
        } finally {
            delivering = false;
        }
    }
}
