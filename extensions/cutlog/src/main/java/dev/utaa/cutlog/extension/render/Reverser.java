package dev.utaa.cutlog.extension.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import dev.utaa.cutlog.extension.hook.CaptureInterceptor;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 動画の区間を逆再生にした中間ファイル（H.264 + AAC の mp4）を作る。Media3 に逆再生が無いため、
 * MediaMetadataRetriever で後ろから少しずつフレームを取り出して逆順にエンコードし、音声は PCM に戻して
 * 逆順にしてから AAC にし直す。メモリに載せるのは {@link #CHUNK} 枚分のフレームと、区間の PCM だけ。
 *
 * <p>裏のスレッドで動き、進み具合と結果は main thread へ返す。途中でやめたら出力を消す。
 */
public final class Reverser {
    public interface Callback {
        void onProgress(float progress);

        void onSuccess(File output);

        void onError(Exception error);
    }

    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    /** 中間ファイルなので、最後の書き出しでの劣化が目立たないよう高めにする。 */
    private static final int VIDEO_BITRATE = 12_000_000;
    private static final int AUDIO_BITRATE = 128_000;
    private static final int MAX_SIDE = 1280;
    /** 一度に取り出すフレームの数。フル HD でも 1 枚 8MB ほどなので、この程度に抑える。 */
    private static final int CHUNK = 8;
    private static final long TIMEOUT_US = 10_000;

    private final Context context;
    private final Uri source;
    private final long startMs;
    private final long lengthMs;
    private final int displayWidth;
    private final int displayHeight;
    private final File output;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private Reverser(Context context, Uri source, long startMs, long lengthMs, int displayWidth, int displayHeight,
            File output, Callback callback) {
        this.context = context.getApplicationContext();
        this.source = source;
        this.startMs = startMs;
        this.lengthMs = lengthMs;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.output = output;
        this.callback = callback;
    }

    /** source の [startMs, startMs + lengthMs) を逆再生にして output へ書く。displayWidth・Height は表示上の向き。 */
    public static Reverser start(Context context, Uri source, long startMs, long lengthMs, int displayWidth,
            int displayHeight, File output, Callback callback) {
        Reverser reverser = new Reverser(context, source, startMs, lengthMs, displayWidth, displayHeight, output, callback);
        new Thread(reverser::run, "cutlog-reverse").start();
        return reverser;
    }

    /** やめる。callback はもう呼ばない。 */
    public void cancel() {
        cancelled.set(true);
    }

    private void run() {
        try {
            reverse();
            main.post(() -> {
                if (!cancelled.get()) {
                    callback.onSuccess(output);
                } else {
                    output.delete();
                }
            });
        } catch (Exception e) {
            output.delete();
            if (!cancelled.get()) {
                Log.w(CaptureInterceptor.TAG, "Cannot reverse the video.", e);
                main.post(() -> callback.onError(e));
            }
        }
    }

    private void checkCancelled() {
        if (cancelled.get()) {
            throw new CancellationException();
        }
    }

    private void reverse() throws IOException {
        output.delete();
        AudioTrack audio = reverseAudio();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaMuxer muxer = null;
        MediaCodec encoder = null;
        try {
            retriever.setDataSource(context, source);
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int frameCount = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            int rotation = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            int[] range = ReverseMath.frameRange(startMs, lengthMs, durationMs, frameCount);
            int count = range[1] - range[0];
            if (count <= 0) {
                throw new IOException("No frames to reverse: duration=" + durationMs + "ms, frames=" + frameCount);
            }
            double fps = frameCount * 1000.0 / durationMs;
            int[] size = ReverseMath.encodeSize(displayWidth, displayHeight, MAX_SIDE);
            int width = size[0];
            int height = size[1];

            MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.max(1, Math.round(fps)));
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(VIDEO_MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Muxing muxing = new Muxing(muxer, audio);

            int[] pixels = new int[width * height];
            int written = 0;
            for (int chunkEnd = range[1]; chunkEnd > range[0]; chunkEnd -= CHUNK) {
                checkCancelled();
                int chunkStart = Math.max(range[0], chunkEnd - CHUNK);
                List<Bitmap> frames = retriever.getFramesAtIndex(chunkStart, chunkEnd - chunkStart);
                for (int k = frames.size() - 1; k >= 0; k--) {
                    checkCancelled();
                    Bitmap frame = orient(frames.get(k), rotation);
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                    scaled.getPixels(pixels, 0, width, 0, 0, width, height);
                    if (scaled != frame) {
                        scaled.recycle();
                    }
                    frame.recycle();
                    long ptsUs = Math.round(written * 1_000_000.0 / fps);
                    queueFrame(encoder, muxing, pixels, width, height, ptsUs);
                    written++;
                    float progress = 0.1f + 0.9f * written / count;
                    main.post(() -> {
                        if (!cancelled.get()) {
                            callback.onProgress(progress);
                        }
                    });
                }
                frames.clear();
            }
            int index;
            while ((index = encoder.dequeueInputBuffer(TIMEOUT_US)) < 0) {
                drain(encoder, muxing, false);
            }
            long endUs = Math.round(written * 1_000_000.0 / fps);
            encoder.queueInputBuffer(index, 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(encoder, muxing, true);
            Log.i(CaptureInterceptor.TAG, "Reversed " + written + " frames (" + width + "x" + height + ", "
                    + String.format(java.util.Locale.ROOT, "%.1f", fps) + "fps), audio=" + (audio != null));
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                    // 失敗の途中で止める場合は、stop できない状態のことがある。
                }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                    // 始まる前に失敗した場合は stop できない。出力は呼び出し側で消す。
                }
                muxer.release();
            }
            try {
                retriever.release();
            } catch (IOException ignored) {
                // 片付けの失敗は結果に影響しない。
            }
        }
    }

    /** 90 / 270 度の回転付き動画で、取り出したフレームが回転前の向きなら、表示上の向きに回す。 */
    private Bitmap orient(Bitmap frame, int rotation) {
        boolean frameLandscape = frame.getWidth() > frame.getHeight();
        boolean displayLandscape = displayWidth > displayHeight;
        if (rotation % 180 == 0 || frameLandscape == displayLandscape) {
            return frame;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), matrix, true);
        frame.recycle();
        return rotated;
    }

    /** ARGB のフレームを YUV420 にしてエンコーダーへ入れる。プレーンの並びは Image の stride に従う。 */
    private void queueFrame(MediaCodec encoder, Muxing muxing, int[] pixels, int width, int height, long ptsUs) {
        int index;
        while ((index = encoder.dequeueInputBuffer(TIMEOUT_US)) < 0) {
            checkCancelled();
            drain(encoder, muxing, false);
        }
        Image image = encoder.getInputImage(index);
        if (image == null) {
            throw new IllegalStateException("Encoder has no input image");
        }
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yPlane = planes[0].getBuffer();
        ByteBuffer uPlane = planes[1].getBuffer();
        ByteBuffer vPlane = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPixel = planes[2].getPixelStride();
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                int p = pixels[rowStart + x];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                yPlane.put(y * yRow + x * yPixel, (byte) ReverseMath.y(r, g, b));
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int cy = y >> 1;
                    int cx = x >> 1;
                    uPlane.put(cy * uRow + cx * uPixel, (byte) ReverseMath.u(r, g, b));
                    vPlane.put(cy * vRow + cx * vPixel, (byte) ReverseMath.v(r, g, b));
                }
            }
        }
        encoder.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0);
        drain(encoder, muxing, false);
    }

    /** エンコーダーの出力を muxer へ書く。映像の形式が決まったところで muxer を始める。 */
    private void drain(MediaCodec encoder, Muxing muxing, boolean untilEnd) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEnd) {
                    return;
                }
                checkCancelled();
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxing.start(encoder.getOutputFormat());
                continue;
            }
            if (index < 0) {
                continue;
            }
            ByteBuffer data = encoder.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) {
                muxing.writeVideo(data, info);
            }
            encoder.releaseOutputBuffer(index, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    /** 映像のトラックが決まってから、音声のトラックと合わせて muxer を始め、先に作った音声を書く。 */
    private static final class Muxing {
        private final MediaMuxer muxer;
        private final AudioTrack audio;
        private int videoTrack = -1;

        Muxing(MediaMuxer muxer, AudioTrack audio) {
            this.muxer = muxer;
            this.audio = audio;
        }

        void start(MediaFormat videoFormat) {
            if (videoTrack >= 0) {
                return;
            }
            videoTrack = muxer.addTrack(videoFormat);
            int audioTrack = audio != null ? muxer.addTrack(audio.format) : -1;
            muxer.start();
            if (audio != null) {
                for (int i = 0; i < audio.samples.size(); i++) {
                    muxer.writeSampleData(audioTrack, ByteBuffer.wrap(audio.samples.get(i)), audio.infos.get(i));
                }
            }
        }

        void writeVideo(ByteBuffer data, MediaCodec.BufferInfo info) {
            if (videoTrack < 0) {
                throw new IllegalStateException("Video sample before format");
            }
            muxer.writeSampleData(videoTrack, data, info);
        }
    }

    /** 逆順にして AAC にし直した音声。 */
    private static final class AudioTrack {
        final MediaFormat format;
        final List<byte[]> samples;
        final List<MediaCodec.BufferInfo> infos;

        AudioTrack(MediaFormat format, List<byte[]> samples, List<MediaCodec.BufferInfo> infos) {
            this.format = format;
            this.samples = samples;
            this.infos = infos;
        }
    }

    /** 区間の音声を PCM に戻して逆順にし、AAC にし直す。音声が無ければ null。 */
    private AudioTrack reverseAudio() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        try {
            extractor.setDataSource(context, source, null);
            int track = -1;
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    input = format;
                    break;
                }
            }
            if (track < 0) {
                return null;
            }
            extractor.selectTrack(track);
            long startUs = startMs * 1000;
            long endUs = (startMs + lengthMs) * 1000;
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();
            int channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                checkCancelled();
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer != null ? extractor.readSampleData(buffer, 0) : -1;
                        long time = extractor.getSampleTime();
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = decoder.getOutputFormat();
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IOException("Unsupported PCM encoding: " + format);
                    }
                } else if (index >= 0) {
                    ByteBuffer data = decoder.getOutputBuffer(index);
                    if (data != null && info.size > 0) {
                        appendClipped(pcm, data, info, startUs, endUs, channels, rate);
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            byte[] reversed = ReverseMath.reversePcm16(pcm.toByteArray(), channels);
            if (reversed.length == 0) {
                return null;
            }

            MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, rate, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            encoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            return encodeAac(encoder, reversed, channels, rate);
        } finally {
            if (decoder != null) {
                decoder.release();
            }
            if (encoder != null) {
                encoder.release();
            }
            extractor.release();
        }
    }

    /** 出力バッファのうち、区間 [startUs, endUs) に入る部分だけを pcm に足す。 */
    private static void appendClipped(ByteArrayOutputStream pcm, ByteBuffer data, MediaCodec.BufferInfo info,
            long startUs, long endUs, int channels, int rate) {
        int frameBytes = 2 * channels;
        int frames = info.size / frameBytes;
        long first = Math.max(0, (long) Math.ceil((startUs - info.presentationTimeUs) * (double) rate / 1_000_000));
        long last = Math.min(frames, (long) Math.floor((endUs - info.presentationTimeUs) * (double) rate / 1_000_000));
        if (last <= first) {
            return;
        }
        byte[] bytes = new byte[(int) ((last - first) * frameBytes)];
        data.position(info.offset + (int) first * frameBytes);
        data.get(bytes);
        pcm.write(bytes, 0, bytes.length);
    }

    private AudioTrack encodeAac(MediaCodec encoder, byte[] pcm, int channels, int rate) {
        int frameBytes = 2 * channels;
        int chunk = 1024 * frameBytes;
        List<byte[]> samples = new ArrayList<>();
        List<MediaCodec.BufferInfo> infos = new ArrayList<>();
        MediaFormat outputFormat = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int offset = 0;
        boolean inputDone = false;
        while (true) {
            checkCancelled();
            if (!inputDone) {
                int index = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (index >= 0) {
                    ByteBuffer buffer = encoder.getInputBuffer(index);
                    long ptsUs = (offset / frameBytes) * 1_000_000L / rate;
                    if (offset >= pcm.length || buffer == null) {
                        encoder.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int size = Math.min(Math.min(chunk, buffer.remaining()), pcm.length - offset);
                        size -= size % frameBytes;
                        buffer.put(pcm, offset, size);
                        encoder.queueInputBuffer(index, 0, size, ptsUs, 0);
                        offset += size;
                    }
                }
            }
            int index = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = encoder.getOutputFormat();
            } else if (index >= 0) {
                ByteBuffer data = encoder.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) {
                    byte[] bytes = new byte[info.size];
                    data.position(info.offset);
                    data.get(bytes);
                    MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
                    copy.set(0, info.size, info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    samples.add(bytes);
                    infos.add(copy);
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
        if (outputFormat == null || samples.isEmpty()) {
            return null;
        }
        return new AudioTrack(outputFormat, samples, infos);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
