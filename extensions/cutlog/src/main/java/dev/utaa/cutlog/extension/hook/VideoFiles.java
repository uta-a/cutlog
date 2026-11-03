package dev.utaa.cutlog.extension.hook;

import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 録画ファイルの読み取りと置き換え。 */
public final class VideoFiles {
    private VideoFiles() {}

    public static CaptureReplacement readMetadata(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            if (!"video/mp4".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))) {
                throw new IOException("Not an mp4 file: " + file);
            }
            long durationMs = Long.parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            if (durationMs <= 0 || width <= 0 || height <= 0) {
                throw new IOException("Invalid video metadata: " + file);
            }
            boolean hasAudio = "yes".equals(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            return new CaptureReplacement(durationMs, width, height, hasAudio);
        } catch (RuntimeException e) {
            throw new IOException("Cannot read video metadata: " + file, e);
        } finally {
            retriever.release();
        }
    }

    /** 途中で失敗しても target を壊さないよう、同じディレクトリの一時ファイルへ全量を書いてから置き換える。 */
    public static void copyOver(File source, File target) throws IOException {
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
            moveOver(temp, target);
        } finally {
            temp.delete();
        }
    }

    /** source を target の位置へ移す。同じファイルシステム上での rename なので途中状態は残らない。 */
    public static void moveOver(File source, File target) throws IOException {
        if (!source.renameTo(target)) {
            throw new IOException("Failed to replace " + target);
        }
    }
}
