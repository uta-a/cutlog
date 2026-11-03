package dev.utaa.cutlog.extension.editor;

import java.util.ArrayList;
import java.util.List;

/** 編集内容。プレビューと書き出しの両方がこの値だけを見る。 */
public final class EditSpec {
    public FilterPreset filter = FilterPreset.NONE;
    /** 明るさ・コントラスト・彩度。いずれも -1〜1、0 で無調整。 */
    public float brightness;
    public float contrast;
    public float saturation;
    public final List<TextSpec> texts = new ArrayList<>();

    public boolean hasColorEdits() {
        return filter != FilterPreset.NONE || brightness != 0f || contrast != 0f || saturation != 0f;
    }

    public boolean isEmpty() {
        return !hasColorEdits() && texts.isEmpty();
    }
}
