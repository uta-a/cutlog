package dev.utaa.cutlog.extension.hook;

/**
 * 録画画面（setlog の CollectionCamera フラグメント）。パッチがこの interface を実装させる。
 *
 * <p>どちらのメソッドも main thread から、録画結果ごとに一度だけ呼ぶ。
 */
public interface CaptureHost {
    /** 録画結果を本体の投稿フローへ渡す。replacement が null なら録画結果をそのまま渡す。 */
    void cutlogDeliver(Object result, CaptureReplacement replacement);

    /** 本体のキャンセル処理を呼び、録画を破棄して撮影画面を閉じる。 */
    void cutlogCancel();
}
