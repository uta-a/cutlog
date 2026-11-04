package dev.utaa.cutlog.extension.render;

/** 逆再生の中間ファイルを作るときの計算。Android に依らないので単体テストで確かめる。 */
final class ReverseMath {
    private ReverseMath() {}

    /**
     * 区間 [startMs, startMs + lengthMs) に入るフレームの番号 {最初, 最後の次}。
     * フレームは動画全体（durationMs, frameCount）に等間隔に並んでいるとみなす。
     */
    static int[] frameRange(long startMs, long lengthMs, long durationMs, int frameCount) {
        if (durationMs <= 0 || frameCount <= 0) {
            return new int[] {0, 0};
        }
        int first = (int) Math.floor((double) startMs * frameCount / durationMs);
        int end = (int) Math.ceil((double) (startMs + lengthMs) * frameCount / durationMs);
        first = Math.max(0, Math.min(frameCount, first));
        end = Math.max(first, Math.min(frameCount, end));
        return new int[] {first, end};
    }

    /** 16bit PCM を、チャンネルの並びを保ったままサンプル単位で逆順にする。 */
    static byte[] reversePcm16(byte[] pcm, int channels) {
        int frameBytes = 2 * channels;
        int frames = pcm.length / frameBytes;
        byte[] reversed = new byte[frames * frameBytes];
        for (int i = 0; i < frames; i++) {
            System.arraycopy(pcm, i * frameBytes, reversed, (frames - 1 - i) * frameBytes, frameBytes);
        }
        return reversed;
    }

    /** 長い辺を maxSide 以下にし、エンコーダーのために幅・高さを偶数にした大きさ。 */
    static int[] encodeSize(int width, int height, int maxSide) {
        float scale = Math.min(1f, maxSide / (float) Math.max(width, height));
        int w = Math.max(2, Math.round(width * scale) & ~1);
        int h = Math.max(2, Math.round(height * scale) & ~1);
        return new int[] {w, h};
    }

    /** BT.601（limited range）の Y。 */
    static int y(int r, int g, int b) {
        return ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
    }

    static int u(int r, int g, int b) {
        return ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
    }

    static int v(int r, int g, int b) {
        return ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
    }
}
