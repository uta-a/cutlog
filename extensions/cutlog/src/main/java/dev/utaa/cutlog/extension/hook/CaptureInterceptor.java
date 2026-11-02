package dev.utaa.cutlog.extension.hook;

import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Phase 1(b) の差し替え PoC。録画完了時に、固定パスの動画があれば録画ファイルへ上書きする。
 *
 * <p>Uri を変えないので、録画ファイルの後始末は本体の処理のまま働く。固定パスに動画が無いとき、
 * または差し替えに失敗したときは、録画をそのまま使う。
 */
public final class CaptureInterceptor {
    private static final String TAG = "cutlog";
    private static final String REPLACEMENT_PATH =
            "Android/data/com.newchat.setlog/files/cutlog/replacement.mp4";

    private CaptureInterceptor() {}

    /** @return 差し替えた場合はそのメタデータ。録画をそのまま使う場合は null。 */
    public static CaptureReplacement onCaptured(Uri captured) {
        try {
            File source = new File(Environment.getExternalStorageDirectory(), REPLACEMENT_PATH);
            if (!source.isFile()) {
                Log.i(TAG, "No replacement at " + source + " (exists=" + source.exists() + ")");
                return null;
            }
            if (!"file".equals(captured.getScheme()) || captured.getPath() == null) {
                Log.w(TAG, "Unsupported capture uri: " + captured);
                return null;
            }
            CaptureReplacement replacement = readMetadata(source);
            replaceAtomically(source, new File(captured.getPath()));
            Log.i(TAG, "Replaced capture: " + replacement.width + "x" + replacement.height
                    + ", " + replacement.durationMs + "ms, audio=" + replacement.hasAudio);
            return replacement;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Keeping the original capture.", e);
            return null;
        }
    }

    private static CaptureReplacement readMetadata(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            if (!"video/mp4".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))) {
                throw new IOException("Replacement is not an mp4 file.");
            }
            long durationMs = Long.parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (durationMs <= 0 || width <= 0 || height <= 0) {
                throw new IOException("Replacement has invalid metadata.");
            }
            boolean hasAudio = "yes".equals(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            return new CaptureReplacement(durationMs, width, height, hasAudio);
        } finally {
            retriever.release();
        }
    }

    /** 途中で失敗しても録画を壊さないよう、一時ファイルへ全量を書いてから置き換える。 */
    private static void replaceAtomically(File source, File target) throws IOException {
        File temp = new File(target.getPath() + ".cutlog.tmp");
        try {
            try (InputStream in = new FileInputStream(source);
                    OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Failed to replace " + target);
            }
        } finally {
            temp.delete();
        }
    }
}
