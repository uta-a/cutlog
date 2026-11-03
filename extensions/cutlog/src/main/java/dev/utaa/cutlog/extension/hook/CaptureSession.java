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

    /** 書き出した動画で録画ファイルを置き換えてから、投稿フローへ渡す。 */
    public void deliverEdited(File edited) throws IOException {
        CaptureReplacement replacement = VideoFiles.readMetadata(edited);
        VideoFiles.moveOver(edited, capturedFile);
        Log.i(CaptureInterceptor.TAG, "Delivering edited capture: " + replacement);
        finish(replacement);
    }

    public void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        host.cutlogCancel();
    }

    private void finish(CaptureReplacement replacement) {
        if (finished) {
            return;
        }
        finished = true;
        CaptureInterceptor.deliver(host, result, replacement);
    }
}
