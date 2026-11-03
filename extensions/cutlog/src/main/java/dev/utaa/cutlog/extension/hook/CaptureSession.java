package dev.utaa.cutlog.extension.hook;

import android.util.Log;
import java.io.File;
import java.io.IOException;

/** 預かった録画結果 1 件。完了・キャンセルのどちらかを一度だけ本体へ返す。 */
public final class CaptureSession {
    private final CaptureHost host;
    private final Object result;
    private final File capturedFile;
    private final CaptureReplacement initialReplacement;
    private boolean finished;

    CaptureSession(CaptureHost host, Object result, File capturedFile, CaptureReplacement initialReplacement) {
        this.host = host;
        this.result = result;
        this.capturedFile = capturedFile;
        this.initialReplacement = initialReplacement;
    }

    public File capturedFile() {
        return capturedFile;
    }

    /** 録画（または検証用の差し替え動画）をそのまま投稿フローへ渡す。 */
    public void deliverOriginal() {
        finish(initialReplacement);
    }

    /**
     * 書き出した動画で録画ファイルを置き換える。まだ本体へは返さない。
     * 失敗した場合、録画ファイルは元のまま残る（置き換えは rename 1 回で行う）。
     */
    public CaptureReplacement installEdited(File edited) throws IOException {
        CaptureReplacement replacement = VideoFiles.readMetadata(edited);
        VideoFiles.moveOver(edited, capturedFile);
        return replacement;
    }

    /** {@link #installEdited} で置き換えた動画を投稿フローへ渡す。 */
    public void deliverEdited(CaptureReplacement replacement) {
        Log.i(CaptureInterceptor.TAG, "Delivering edited capture: " + replacement);
        finish(replacement);
    }

    /**
     * 本体の画面が破棄され、結果を返す先が無くなった。何も返さずに終える。
     * 録画は本体の撮影画面ごと破棄されるため、ユーザーは撮影からやり直す。
     */
    public void abandon() {
        if (finished) {
            return;
        }
        finished = true;
        Log.w(CaptureInterceptor.TAG, "Capture host was destroyed; abandoning the edit session.");
    }

    public void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            host.cutlogCancel();
        } catch (RuntimeException e) {
            Log.w(CaptureInterceptor.TAG, "Cannot cancel the capture.", e);
        }
    }

    private void finish(CaptureReplacement replacement) {
        if (finished) {
            return;
        }
        finished = true;
        CaptureInterceptor.deliver(host, result, replacement);
    }
}
