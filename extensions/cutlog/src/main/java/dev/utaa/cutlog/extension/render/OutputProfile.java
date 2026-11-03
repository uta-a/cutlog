package dev.utaa.cutlog.extension.render;

/**
 * 書き出しの出力仕様。公式撮影分（Phase 0 で ffprobe）に揃える。
 *
 * <p>解像度・長さ・音声は入力の録画をそのまま引き継ぐため、ここでは映像コーデックだけを固定する。
 */
public final class OutputProfile {
    /** 公式撮影分は HEVC Main。Media3 Transformer の既定は H.264 なので明示する。 */
    public static final String VIDEO_MIME_TYPE = "video/hevc";

    private OutputProfile() {}
}
