package dev.utaa.cutlog.extension.editor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import dev.utaa.cutlog.shaded.androidx.media3.common.Effect;
import dev.utaa.cutlog.shaded.androidx.media3.common.MediaItem;
import dev.utaa.cutlog.shaded.androidx.media3.common.Player;
import dev.utaa.cutlog.shaded.androidx.media3.exoplayer.ExoPlayer;
import java.util.List;

/**
 * 編集画面のプレビューを再生する。動画全体にかかる効果は ExoPlayer の video effect で、
 * 文字・スタンプはその上の View で描く。
 */
final class PreviewController {
    interface Source {
        MediaItem mediaItem();

        List<Effect> effects();
    }

    /** スライダーを続けて動かしたときに、プレイヤーを作り直す回数を抑える。 */
    private static final long REBUILD_DELAY_MS = 150;

    private final Context context;
    private final TextureView texture;
    private final Source source;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rebuild = this::rebuild;
    private ExoPlayer player;
    /** 利用者が一時停止したか。本体の Activity が前面に戻ったときに再開するかを決める。 */
    private boolean pausedByUser;

    PreviewController(Context context, TextureView texture, Source source) {
        this.context = context;
        this.texture = texture;
        this.source = source;
    }

    void start() {
        start(0, true);
    }

    private void start(long positionMs, boolean play) {
        player = new ExoPlayer.Builder(context).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.setVideoTextureView(texture);
        player.setVideoEffects(source.effects());
        player.setMediaItem(source.mediaItem(), positionMs);
        player.prepare();
        player.setPlayWhenReady(play);
    }

    /**
     * 再生中のプレイヤーに {@code setVideoEffects} を何度も送ると、ループの境目で映像も音声も止まる
     * （media3 1.11.1 / Android 13 実機で再現）。そのため効果を変えたら、少し待ってから新しい Effect で
     * プレイヤーを作り直し、同じ位置から再生を続ける。
     */
    void effectsChanged() {
        handler.removeCallbacks(rebuild);
        handler.postDelayed(rebuild, REBUILD_DELAY_MS);
    }

    /** 入力の区間を変えたとき。区間の先頭から再生し直す。 */
    void mediaChanged() {
        if (player == null) {
            return;
        }
        handler.removeCallbacks(rebuild);
        boolean play = player.getPlayWhenReady();
        player.release();
        start(0, play);
    }

    private void rebuild() {
        if (player == null) {
            return;
        }
        long positionMs = player.getCurrentPosition();
        boolean play = player.getPlayWhenReady();
        player.release();
        start(positionMs, play);
    }

    long positionMs() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    void setPlaying(boolean playing) {
        pausedByUser = !playing;
        if (player != null) {
            player.setPlayWhenReady(playing);
        }
    }

    void seekTo(long positionMs) {
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    /** 本体の Activity が前面に戻った。 */
    void resume() {
        if (player != null && !pausedByUser) {
            player.play();
        }
    }

    /** 本体の Activity が裏に回った。 */
    void pause() {
        if (player != null) {
            player.pause();
        }
    }

    void release() {
        handler.removeCallbacks(rebuild);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
