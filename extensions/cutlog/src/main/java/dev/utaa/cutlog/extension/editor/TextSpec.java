package dev.utaa.cutlog.extension.editor;

/** 焼き込むテキスト 1 件。位置とサイズは動画の大きさに対する比率で持つ。 */
public final class TextSpec {
    public String text;
    public int color;
    /** 文字の高さ。動画の短辺に対する比率。 */
    public float size;
    /** テキスト中心の位置。左上が (0, 0)、右下が (1, 1)。 */
    public float x;
    public float y;

    public TextSpec(String text, int color, float size, float x, float y) {
        this.text = text;
        this.color = color;
        this.size = size;
        this.x = x;
        this.y = y;
    }
}
