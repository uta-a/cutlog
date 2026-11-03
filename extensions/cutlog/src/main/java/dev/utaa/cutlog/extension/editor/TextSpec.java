package dev.utaa.cutlog.extension.editor;

/** 焼き込むテキスト 1 件。 */
public final class TextSpec extends Layer {
    public String text;
    public int color;

    public TextSpec(String text, int color, float size, float x, float y) {
        super(size, x, y);
        this.text = text;
        this.color = color;
    }

    @Override
    public String label() {
        return text;
    }
}
