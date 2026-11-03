package dev.utaa.cutlog.extension.render;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import dev.utaa.cutlog.extension.editor.EditSpec;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;
import dev.utaa.cutlog.shaded.androidx.media3.common.MediaItem;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.Composition;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.EditedMediaItem;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.Effects;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.ExportException;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.ExportResult;
import dev.utaa.cutlog.shaded.androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

/**
 * Media3 Transformer で編集内容を焼き込んだ mp4 を書き出す。
 *
 * <p>Transformer は Looper スレッドの非同期 API なので、main thread から呼び、結果は main thread の
 * コールバックで受け取る。音声には何もしないので、録画の AAC がそのまま引き継がれる。
 */
public final class Renderer {
    public interface Callback {
        void onSuccess(File output);

        void onError(Exception error);
    }

    private final Transformer transformer;

    private Renderer(Transformer transformer) {
        this.transformer = transformer;
    }

    public static Renderer start(
            Context context, File input, EditSpec spec, int width, int height, File output, Callback callback) {
        output.delete();
        Transformer transformer = new Transformer.Builder(context)
                .setVideoMimeType(OutputProfile.VIDEO_MIME_TYPE)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        Log.i(CaptureInterceptor.TAG, "Export completed: " + result.width + "x" + result.height
                                + ", " + result.durationMs + "ms, video=" + result.videoMimeType
                                + " (" + result.videoEncoderName + "), audio=" + result.audioMimeType);
                        if (!OutputProfile.VIDEO_MIME_TYPE.equals(result.videoMimeType)) {
                            output.delete();
                            callback.onError(new IOException("Encoder fell back to " + result.videoMimeType));
                            return;
                        }
                        callback.onSuccess(output);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException error) {
                        Log.w(CaptureInterceptor.TAG, "Export failed.", error);
                        output.delete();
                        callback.onError(error);
                    }
                })
                .build();
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(new Effects(Collections.emptyList(), EffectsFactory.exportEffects(spec, width, height)))
                .build();
        transformer.start(item, output.getPath());
        return new Renderer(transformer);
    }

    public void cancel() {
        transformer.cancel();
    }
}
