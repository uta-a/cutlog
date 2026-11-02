package dev.utaa.cutlog.patches

import app.morphe.patcher.patch.bytecodePatch
import dev.utaa.cutlog.patches.shared.Constants

/** Phase 1(a) 用。何も書き換えず、再署名だけの APK を作るためのパッチです。 */
@Suppress("unused")
val cutlogPatch = bytecodePatch(
    name = "cutlog",
    description = "setlog に投稿前編集を追加します。現在は再署名の検証用で、何も変更しません。",
) {
    compatibleWith(Constants.SETLOG_COMPATIBILITY)

    execute { }
}
