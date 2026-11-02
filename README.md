# cutlog

setlog（New Chat Inc. の Android アプリ、`com.newchat.setlog`）向けの Morphe パッチバンドル。「撮った動画を投稿前に編集する」「端末内の任意の動画をその時間のログとして投稿する」を追加する。linimal と同じ構成（patches + extension）。

Phase 0 の静的調査は完了。現在は Phase 1 の起動確認用に、ローカルインストール元チェックのパッチを実装済み。録画完了時の動画差し替え（Phase 1(b) の PoC）を実装済みで、実機での差し替えと、自分だけの vlog への投稿まで確認済み。投稿前編集は未実装。

## ドキュメント

- [docs/DESIGN.md](docs/DESIGN.md) — 設計書（要件・アーキテクチャ・マイルストーン）
- [docs/analysis/phase0-recording-upload.md](docs/analysis/phase0-recording-upload.md) — 録画完了点とアップロード経路の静的解析
- [docs/analysis/phase0-output-profile.md](docs/analysis/phase0-output-profile.md) — 公式撮影分の出力仕様（OutputProfile の基準値）
- [docs/compatibility/pairip-integrity.md](docs/compatibility/pairip-integrity.md) — PairIP / 改ざん検知・整合性保護の調査

## Phase 0 の到達点

- 対象: `com.newchat.setlog` v1.3 / versionCode 76 / arm64-v8a（Google Play 版）
- フレームワーク: ネイティブ Android（CameraX + Camera2 フォールバック）。Flutter / React Native ではない
- フック点: 録画完了の単一 choke point `vb0.G0(oc0)`（難読化後の名前）
- アップロード: 署名付き URL への HTTP PUT。メディアは暗号化される
- 投稿時刻: クライアント送信値（body の `timestamp`）
- 整合性保護: 署名検証・Play Integrity は無し。PairIP licensecheck（起動時 Play ライセンス確認）のみ
- 動画仕様: HEVC Main / 短辺720・長辺1280 / 約30fps / AAC-LC 48kHz モノラル / 2.065978 秒固定（「保存」経路の値）
- 残件: UI フロー実機確認、投稿経路の動画仕様の確認（Phase 1）

## インストール元チェックのパッチ

- 対象: setlog v1.3 / versionCode 76 / arm64-v8a の APKM
- `cutlog` パッチは `LicenseClient.performLocalInstallerCheck(): boolean` の本体を `return true` に置換する
- fingerprint は宣言クラス・メソッド名・signature・インストール元 API 呼び出し・判定文字列で照合。一致が一件でなければ適用を中止する
- PackageManager API 全体や Firebase のインストール元収集は変更しない。Play ライセンス通信・応答検証・ペイウォールも残るため、起動成功を保証するものではない

ビルドには Morphe の GitHub Packages 認証（環境変数 `GITHUB_ACTOR` / `GITHUB_TOKEN`、または `~/.gradle/gradle.properties` の `gpr.user` / `gpr.key`）が必要。

```sh
./gradlew :patches:build :patches:buildAndroid
java -jar <morphe-desktop-all.jar> patch \
  --patches patches/build/libs/patches-0.0.1.mpp \
  --out scratch/setlog-installer-check.apk \
  --keystore scratch/cutlog-dev.keystore \
  setlog-apk/setlog-1.3-76.apkm
```

検証済み: バンドルのビルド、実 APKM への適用・既存開発鍵での署名、`apksigner verify`、生成 APK の jadx 再解析で `return true` を確認。署名済み検証 APK は `scratch/setlog-installer-check.apk`。Android 13 の実機（23043RP34G）へ `adb install -r` で上書きし、コールド起動でログイン画面への到達、40 秒後も同一プロセスが生存することを確認。観測中に「安全設定を完了」画面へ進んだ。暗号鍵の移行・新規作成と、過去動画へのアクセス・投稿は未確認。

## 動画差し替え PoC のパッチ

`動画差し替え PoC` パッチは、録画完了時（`vb0.G0`）に次のファイルがあれば録画ファイルへ上書きし、長さ・解像度・音声有無を差し替え後の値で `CollectionCameraResult` を作り直す。ファイルが無いとき、読めないときは録画をそのまま使う（どちらもログに出す）。

`/sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4`

ファイルを置いている間は、すべての撮影がこの動画に置き換わる。検証が済んだら必ず消す。

```sh
adb push scratch/replacement.mp4 /sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4
adb logcat -s cutlog   # 成功時は "Replaced capture: ..."
adb shell rm /sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4
```

検証用の動画は OutputProfile に合わせて ffmpeg で作る（`scratch/replacement.mp4`、HEVC Main 1280x720 30fps、AAC-LC 48kHz モノラル、約 2.07 秒）。

## 注意

- APK・逆コンパイル結果・署名鍵はコミットしない（`.gitignore` 済み。`setlog-apk/` はローカル専用の読み取り入力）
- 検証はサブアカウントで行う。利用規約・アカウント停止のリスクがある
