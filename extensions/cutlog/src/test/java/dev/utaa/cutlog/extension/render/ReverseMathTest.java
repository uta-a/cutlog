package dev.utaa.cutlog.extension.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReverseMathTest {
    @Test
    public void wholeVideoFrameRange() {
        assertArrayEquals(new int[] {0, 62}, ReverseMath.frameRange(0, 2069, 2069, 62));
    }

    @Test
    public void clipFrameRange() {
        // 6 秒・180 フレームの 2〜4 秒は 60〜120。
        assertArrayEquals(new int[] {60, 120}, ReverseMath.frameRange(2000, 2000, 6000, 180));
        // 終わりが動画を越えても最後のフレームまで。
        assertArrayEquals(new int[] {150, 180}, ReverseMath.frameRange(5000, 3000, 6000, 180));
        assertArrayEquals(new int[] {0, 0}, ReverseMath.frameRange(0, 1000, 0, 0));
    }

    /** ステレオでは左右の組を崩さずに逆順にする。 */
    @Test
    public void reversesPcmByFrame() {
        byte[] stereo = {1, 2, 3, 4, 5, 6, 7, 8};
        assertArrayEquals(new byte[] {5, 6, 7, 8, 1, 2, 3, 4}, ReverseMath.reversePcm16(stereo, 2));
        byte[] mono = {1, 2, 3, 4, 5, 6};
        assertArrayEquals(new byte[] {5, 6, 3, 4, 1, 2}, ReverseMath.reversePcm16(mono, 1));
    }

    @Test
    public void encodeSizeIsEvenAndBounded() {
        assertArrayEquals(new int[] {1280, 720}, ReverseMath.encodeSize(1280, 720, 1280));
        assertArrayEquals(new int[] {720, 1280}, ReverseMath.encodeSize(1080, 1920, 1280));
        assertArrayEquals(new int[] {640, 360}, ReverseMath.encodeSize(641, 361, 1280));
    }

    @Test
    public void convertsToLimitedRangeYuv() {
        assertEquals(16, ReverseMath.y(0, 0, 0));
        assertEquals(235, ReverseMath.y(255, 255, 255));
        assertEquals(128, ReverseMath.u(128, 128, 128));
        assertEquals(128, ReverseMath.v(128, 128, 128));
        assertEquals(240, ReverseMath.v(255, 0, 0));
    }
}
