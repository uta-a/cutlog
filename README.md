# cutlog

setlog（New Chat Inc. の Android アプリ、`com.newchat.setlog`）向けの Morphe パッチバンドル。「撮った動画を投稿前に編集する」「端末内の任意の動画をその時間のログとして投稿する」を追加する。linimal と同じ構成（patches + extension）。

Phase 0 の静的調査は完了。Phase 1 のローカルインストール元チェックと録画完了時の動画差し替え PoC は実装済み。差し替えた動画を dev グループへ投稿し、別端末で再生できることはユーザー確認済みで、Phase 1 は Go。途中のロック問題は受信側の鍵再生成で解消した。Phase 2 の投稿前編集（F1〜F3）と Phase 3 のファイルから投稿（F4）は実装済みで、実機で編集・書き出し・本体の送信先画面への受け渡しまで確認した。編集済み動画と取り込み動画を dev グループへ投稿し、別端末で再生できることも確認した。

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

## 投稿前編集のパッチ

`投稿前編集` パッチは、録画完了時（`vb0.G0`）に録画結果を預かり、全画面の編集画面を出す。見た目は setlog の送信先選択画面に合わせている。

- プレビュー左上の ✕: 確認のうえ録画を破棄し、本体のキャンセル処理（`vb0.F0`）で撮影画面を閉じる
- プレビュー右上のピンクの ↑: 編集内容を焼き込んで書き出し、本体の送信先選択画面へ進む
- 下の「そのまま投稿」: 編集せずに本体の送信先選択画面へ進む
- フィルター: なし／モノクロ／セピア／ウォーム／クール／フェード。最後に使ったフィルターを次回の初期値にする
- 調整: 明るさ・コントラスト・彩度
- テキスト: 複数追加でき、色・サイズを変えられる。プレビュー上でドラッグして位置を決める

書き出しは同梱の Media3 Transformer で HEVC にエンコードする。解像度・長さ・音声（AAC）は録画を引き継ぐ。HEVC 以外にフォールバックした場合は失敗として扱い、元の動画で進むかを選ばせる。編集画面を開けない場合（Activity が無い等）は何もせず本体の処理を続ける。

## ファイルから投稿のパッチ

`ファイルから投稿` パッチは、撮影画面のシャッターの右（フラッシュと左右対称の位置）に「ファイルから」ボタンを足す。押すと写真選択画面（動画のみ）が開き、選んだ動画を編集画面で扱う。

- 取り込み用の編集画面には「トリム」タブが加わり、公式と同じ 2.066 秒の区間を選ぶ。動画が短い場合は全体を使う
- 書き出しは必ず公式撮影分の仕様へ揃える: HEVC、縦 720x1280 / 横 1280x720（はみ出しは切り落とし）、30fps 以下、AAC-LC 48kHz モノラル（音声が無ければ無音トラック）、HDR は SDR へ変換
- 「そのまま投稿」は出さない。✕ は撮影画面へ戻るだけ
- 書き出した動画は本体の録画と同じ `cacheDir/camera/collection_camera_<時刻>.mp4` に置き、本体の撮影開始処理（`vb0.j1`）で撮影時刻・UTC オフセット・国コードを作ってから `CollectionCameraResult` を組み立てて渡す。レンズ向きは背面として扱う

実機で確認したこと（Android 13）: ボタンの表示位置、写真選択画面からの選択、トリム、縦 1080x1920・60fps・H.264・ステレオ 44.1kHz・6 秒の動画を 720x1280・約 30fps・HEVC Main・AAC-LC 48kHz モノラル・約 2.08 秒へ変換して本体の送信先選択画面へ渡すところまで（本体の「保存」で書き出したファイルを ffprobe）。dev グループへの投稿と別端末での再生も確認済み。未確認: 横向き・回転付き・音声なし・HDR の動画、写真選択画面から戻る間に撮影画面が作り直された場合。

## 撮影秒数の選択のパッチ

`撮影秒数の選択` パッチは、撮影画面のフラッシュの左に長さのボタン（「2秒」）を足す。押すたびに 2 → 3 → 5 → 10 → 15 秒と切り替わり、公式と違う長さの間はピンクの縁取りが付く。選んだ長さは保存され、次回も使う。「ファイルから投稿」の切り出しの長さもこれに合わせる（2 秒なら公式の 2.066 秒）。

本体は録画設定 `CollectionCameraConfig.recordDurationMs`（公式 2000ms）を「最低これだけ録る長さ」として使い、シャッターを押すとすぐ停止を要求してこの長さに達したところで止める。パッチはこの値を読む箇所すべて（録画開始・メタデータ取得・進捗表示など）の直後で選んだ長さに置き換え、停止要求から強制停止までの予備のタイマー（CameraX・Camera2 の各バックエンドで 2000ms 固定）を選んだ長さ＋1 秒まで延ばす。タイムラプスの設定は変えない。

実機で確認したこと（Android 13）: 5 秒で 5064ms、30 秒（当時の選択肢）で 30048ms、2 秒に戻して 2065ms の録画になること。選択肢は最大 15 秒に絞った（以前 30 秒を選んで保存していた場合は 2 秒に戻る）。撮影中の進捗表示も選んだ長さで進む。5 秒・10 秒の動画は dev グループへ投稿・別端末で再生できることを確認済み（差し替え動画で検証）。15 秒の投稿は未確認。

### 同梱する media3

setlog 本体の media3 は R8 で難読化されているため流用できない。一方で `ExoPlayer` など一部は元の名前で残っているため、そのまま同梱するとクラス名が衝突する。そこで `extensions/cutlog/build.gradle.kts` で media3 1.11.1 と依存（Guava・Kotlin・kotlinx 等）を `dev.utaa.cutlog.shaded.*` へ付け替えてから extension に入れる。AAR の keep 規則も付け替えて R8 に渡す。

media3-effect はシェーダーを `R.raw` から読むが、extension は resource を持ち込めない。パッチがシェーダーを `res/raw/cutlog_media3_*.glsl` として APK に追加し、extension 側の `R` クラスが実行時に ID を引く。シェーダーは `patches/src/main/resources/cutlog/media3-shaders/`（media3 1.11.1 の AAR から取得。Apache License 2.0）。media3 を更新するときは、シェーダーと `R` のフィールドも合わせて更新する。

### 検証用の差し替え動画

次のファイルを置くと、録画をこの動画に置き換えてから編集画面を開く（Phase 1 の差し替え PoC を引き継いだもの）。置いている間はすべての撮影が置き換わるので、検証が済んだら必ず消す。

`/sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4`

```sh
adb push scratch/replacement.mp4 /sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4
adb logcat -s cutlog   # "Replaced capture: ..." / "Export completed: ..." / "Delivering edited capture: ..."
adb shell rm /sdcard/Android/data/com.newchat.setlog/files/cutlog/replacement.mp4
```

検証用の動画は OutputProfile に合わせて ffmpeg で作る（`scratch/replacement.mp4`、HEVC Main 1280x720 30fps、AAC-LC 48kHz モノラル、約 2.07 秒）。

### 実機で確認したこと（Android 13、23043RP34G）

- 撮影直後に編集画面が開き、プレビューがループ再生される
- フィルター（セピア・ウォームの LUT を含む）がプレビューに反映される
- テキストの追加・色変更・ドラッグがプレビューと書き出し結果で一致する
- ↑ で約 0.35 秒で書き出し（`OMX.qcom.video.encoder.hevc`、1280x720、HEVC）、編集済み動画が本体の送信先選択画面に表示される
- 「そのまま投稿」で元の動画が、✕ →「破棄する」で撮影画面が閉じてログ画面に戻る
- 編集済み動画を dev グループへ投稿し、別端末で再生できることを確認した
- 未確認: 縦向き撮影、カメラ映像（差し替え動画以外）の編集、プロセス終了時の挙動

## 注意

- APK・逆コンパイル結果・署名鍵はコミットしない（`.gitignore` 済み。`setlog-apk/` はローカル専用の読み取り入力）
- 検証はサブアカウントで行う。利用規約・アカウント停止のリスクがある
