package dev.utaa.cutlog.extension.editor;

/** ツールのパネルから編集画面への窓口。 */
interface EditorHost {
    EditSpec spec();

    /** 動画全体にかかる効果（色など）を変えた。プレビューを作り直す。 */
    void videoEffectsChanged();

    /** 文字・スタンプの見た目や数を変えた。プレビューとタイムラインを描き直す。 */
    void layersChanged();

    Layer selectedLayer();

    /** 要素を選ぶ。null で選択を外す。 */
    void selectLayer(Layer layer);

    /** 端末の画像を選んで縮小して読み、main thread で渡す。選ばなかった・読めなかったときは呼ばない。 */
    void pickImage(java.util.function.Consumer<android.graphics.Bitmap> callback);

    /** 要素の入り（entering）または出のアニメーションの少し前から再生して見せる。 */
    void previewMotion(Layer layer, boolean entering);
}
