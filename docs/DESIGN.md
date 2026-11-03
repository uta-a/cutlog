# cutlog 設計書（setlog 用 Morphe パッチ）

Nov 3, 2026 · @uta_a

## 概要

cutlog は、setlog（Android 版）を Morphe でパッチし、「撮った動画を投稿前に編集する」「任意の動画をその時間のログとして投稿する」を追加する。方針は、撮影完了で得られる動画ファイルを Java/Kotlin 層で横取りし、自作の編集画面を通してから元の投稿処理に返すこと。

- 対象: setlog（New Chat Inc.）、パッケージ名 `com.newchat.setlog`（Phase 0 で確定）
- 公式仕様: 投稿はアプリ内カメラでその場で撮った2秒前後の動画のみ。カメラロールからの投稿・フィルター・テキストは無い
- 配布形態: linimal と同じく Morphe パッチバンドル（patches + extension）
- スコープ外: iOS 版、サーバー API の直接操作、他ユーザーの投稿の改変

## 要件

機能要件は F1〜F6。F1（編集画面を挟む）が他の土台になる。

| ID | 要件 | 優先度 |
| --- | --- | --- |
| F1 | 撮影完了後、投稿前に編集画面を表示し、「そのまま投稿」「編集して投稿」「キャンセル」を選べる | 必須 |
| F2 | フィルター（モノクロ、セピア、彩度・明るさ・コントラスト、LUT）をかけられる | 必須 |
| F3 | テキストを位置・色・サイズ指定で焼き込める（複数可） | 必須 |
| F4 | 撮影の代わりに端末内の任意の動画を選び、トリムして「今の時間」のログとして投稿できる | 必須 |
| F5 | 過去の時間帯に投稿できる | 調査次第 |
| F6 | 機能ごとの ON/OFF を設定画面から切り替えられる | 任意 |

非機能要件:

- 出力動画は公式撮影分と同じ長さ・解像度・コーデック・音声有無に揃える（サーバー側の検証と再生結合を壊さないため）
- 編集・書き出しは端末内で完結し、外部通信をしない
- 書き出しは公式の固定長（約 2.07 秒）のクリップで数秒以内
- パッチが当たらない版では何もしない（公式の撮影フローを壊さない）
- アプリ更新への追従が楽なよう、編集フック点は fingerprint で探し、難読化クラス名直書きを避ける。起動確認用のインストール元パッチは、非難読化の PairIP クラス・メソッド名と命令列を併用して対象を限定する

## アーキテクチャ

公式の撮影画面と投稿処理はそのまま使い、その間の「録画完了」の1点だけをフックする。編集も任意動画も、この1点に別のファイルを渡すだけで成立する。

フック点は Phase 0 で特定した `vb0.G0(oc0)`（録画完了の単一 choke point）。詳細は [analysis/phase0-recording-upload.md](analysis/phase0-recording-upload.md)。

## モジュール設計

パッチ側は「フックを差し込むだけ」に留め、ロジックはすべて extension に置く。linimal と同じ構成。extension の言語は、Kotlin stdlib が本体と衝突するのを避けるため Java を基本とする（linimal の方針を踏襲。Phase 1 で本体の Kotlin 同梱状況を確認して確定）。

```
cutlog/
├─ patches/src/main/kotlin/.../cutlog/patches/
│   ├─ CutlogPatch.kt                  # PairIP のローカルインストール元判定（Phase 1）
│   ├─ capture/CaptureEditorPatch.kt   # 録画結果の横取り・本体への返却・シェーダー resource の追加（F1〜F3）
│   └─ picker/ImportVideoPatch.kt      # 「ファイルから」ボタン・動画選択結果の受け取り・取り込み動画の返却（F4）
├─ patches/src/main/resources/cutlog/media3-shaders/  # media3-effect のシェーダー
└─ extensions/cutlog/src/main/java/dev/utaa/cutlog/
    ├─ extension/hook/CaptureInterceptor.java  # パッチから呼ばれる入口、Activity の保持
    ├─ extension/hook/CaptureHost.java         # パッチが録画画面に実装させる interface
    ├─ extension/hook/EditSession.java         # 編集画面が扱う 1 件分の入力と返し先
    ├─ extension/hook/CaptureSession.java      # 撮影した動画 1 件（完了・キャンセルを一度だけ返す）
    ├─ extension/hook/ImportSession.java       # 端末内から選んだ動画 1 件（F4）
    ├─ extension/editor/ImportButton.java      # 撮影画面の「ファイルから」ボタン
    ├─ extension/editor/EditorDialog.java      # プレビュー＋編集 UI（全画面 Dialog）
    ├─ extension/editor/EditSpec.java          # フィルター・調整・テキストの設定値
    ├─ extension/editor/TextPainter.java       # テキスト描画（プレビューと書き出しで共通）
    ├─ extension/render/EffectsFactory.java    # EditSpec → Media3 Effect
    ├─ extension/render/Renderer.java          # Media3 Transformer で書き出し
    ├─ extension/render/OutputProfile.java     # 公式撮影分に合わせる出力仕様
    ├─ extension/pref/Prefs.java               # 最後に使ったフィルター
    └─ shaded/androidx/media3/effect/R.java    # 付け替えた media3 が読む R.raw（実行時に ID を引く）
```

主なインターフェース:

- `CaptureInterceptor.onCaptured(host, result, uri)`：パッチが `vb0.G0(oc0)` の入口（二重呼び出し防止の判定の後）で呼ぶ。true を返したら本体はそこで止まり、編集画面の操作後に `CaptureHost.cutlogDeliver(result, replacement)` で `G0` を呼び直す。キャンセルは `CaptureHost.cutlogCancel()` が本体の `F0` を呼ぶ
- パッチは `oc0` に `cutlogWithReplacement(oc0, CaptureReplacement)` を足し、録画ファイルの Uri・撮影時刻・UTC オフセット・国コード・レンズ向きは保ったまま、長さ・解像度・音声有無だけを書き出し結果の値に差し替える
- 編集画面を重ねる Activity は `MainActivity.onResume` のフックで受け取る（setlog は単一 Activity）
- `Renderer.start(...)`：Media3 Transformer は Looper スレッドの非同期 API なので、完了コールバックで編集済みファイルを渡す。書き出し先は録画と同じディレクトリで、完了後に rename で録画ファイルを置き換える
- 編集画面は全画面 Dialog。マニフェスト登録が不要で、カメラ画面の状態を保ったまま本体へ戻せる。プロセスが終了すると預かった録画結果は失われる（本体の撮影からやり直しになる）
- media3 1.11.1 は本体のものを流用できないため、依存ごと `dev.utaa.cutlog.shaded.*` へ付け替えて同梱する（README 参照）

## 編集エンジン

書き出しは AndroidX Media3 Transformer、プレビューは ExoPlayer に同じ色の Effect を渡して作る。テキストはプレビューでは View で重ね、書き出しでは同じ描画コード（`TextPainter`）で作った全面の Bitmap を `BitmapOverlay` で焼き込む。編集内容は `EditSpec` だけで表し、プレビューと書き出しで共通にする。

| 機能 | 実装 |
| --- | --- |
| モノクロ / セピア | `RgbFilter.createGrayscaleFilter()`、`RgbMatrix` で色行列 |
| 明るさ・コントラスト・彩度 | `Brightness`、`Contrast`、`HslAdjustment` |
| LUT プリセット | `SingleColorLut.createFromCube()`。ウォーム／クール／フェードの 17^3 キューブをコードで生成（assets は持ち込めないため） |
| テキスト | `OverlayEffect` ＋ `BitmapOverlay`（`TextPainter` で描いた動画と同サイズの Bitmap） |
| スタンプ・画像 | `BitmapOverlay`（将来拡張） |
| トリム | `MediaItem.ClippingConfiguration` |
| リサイズ・クロップ | `Presentation.createForWidthAndHeight()`、縦横比は公式撮影分に合わせる |
| 音声 | 公式は音声あり（AAC-LC 48kHz モノラル）のため無音化せず、形式を揃える |

注: 本体の media3 は R8 で難読化されていて上記 API を名前で呼べないため、media3 1.11.1 を付け替えて extension に同梱した。

編集 UI:

- 見た目は setlog の送信先選択画面に合わせる（黒背景、`#1C1C1E` の面、アクセントはピンク `#FE75F5`）
- 上に角丸のループ再生プレビュー。左上の ✕ がキャンセル、右上のピンクの ↑ が「編集して投稿」
- 下にタブ（フィルター / 調整 / テキスト）と「そのまま投稿」の行。トリムは Phase 3
- テキストはプレビュー上でドラッグして位置決め。位置は動画に対する 0〜1 の比率（左上が原点）、サイズは短辺に対する比率で `EditSpec` に持つ
- ↑ で書き出し → 完了したら `CaptureHost.cutlogDeliver` で本体へ返す。失敗時は元ファイルで続行するか選ばせる
- 最後に使ったフィルターを Prefs に保存し、次回の初期値にする

出力仕様（`OutputProfile`）は Phase 0 で `ffprobe` した公式撮影分の値を既定値にし、入力に関わらずそこへ揃える。

## 任意動画の投稿と時刻の扱い

任意動画（F4）は「録画の代わりにファイルを渡す」だけで実現し、投稿処理は公式のまま使う。過去の時間帯（F5）は Phase 0 で「投稿時刻はクライアント送信値」と判明したため技術的には書き換え可能だが、サーバー検証とスコープ定義（サーバー送信値の操作）の両面で Phase 4 に判断を持ち越す。

任意動画の流れ（Phase 3 で実装）:

1. 撮影画面のシャッターの右（フラッシュと左右対称）に「ファイルから」ボタンを足す。撮影画面の `onViewCreated`（`vb0.g0`）でシャッターを探して追加し、レイアウトのたびにフラッシュの位置から合わせる
2. Photo Picker（`MediaStore.ACTION_PICK_IMAGES`、`video/*`）で動画を選ぶ。ストレージ権限は不要。結果は MainActivity に足した `onActivityResult` で受け取る
3. 編集画面で開始位置を選び、公式と同じ秒数にトリム＋フィルター＋テキスト
4. `OutputProfile` に揃えて書き出し、本体の撮影開始処理 `vb0.j1()` で CaptureContext を作ってから `CollectionCameraResult` を組み立て、`vb0.G0` に渡す
5. 以降は公式の投稿フロー（グループ選択・アップロード）をそのまま通す

時刻の扱い（Phase 0 で判明。詳細は analysis 参照）:

- 投稿時刻はクライアントが body の `timestamp` として送る（録画開始時の `System.currentTimeMillis()`）。サーバー受信時刻依存ではない
- したがって F5 は timestamp 書き換えで技術的には可能だが、サーバー検証で弾かれるか未検証
- `captureUTCOffsetSeconds`・`captureCountryCode` も同時にクライアントから送られる

## リスクと対策

最大のリスクはアカウントの BAN と、サーバー側の動画検証。どちらもサブアカウントで先に試して潰す。

| リスク | 影響 | 対策 |
| --- | --- | --- |
| 利用規約違反としてアカウント停止 | 大 | 検証はサブアカウントで行う。配布する場合は README で明示 |
| サーバーが長さ・解像度・メタデータを検証して弾く | 大 | `OutputProfile` で公式と同じ仕様に揃える。メディアは暗号化されるため差し替えは G0 より前で行う |
| PairIP licensecheck で起動不可 | 大 | APK 署名検証・Play Integrity は見当たらないが、起動時 Play ライセンスチェックあり。ローカルインストール元判定を通すパッチは実装・適用済み。Android 13 実機で40秒後も生存し、安全設定画面への到達を確認。Play 通信は残る。詳細は [compatibility/pairip-integrity.md](compatibility/pairip-integrity.md) |
| アプリ更新でフック点が変わる | 中 | fingerprint は文字列・命令パターン基準。対応バージョンを `compatibleWith` で固定 |
| 編集 Activity 遷移でカメラ解放・resume 無効化 | 中 | Phase 1 で遷移後の resume 成否を検証。だめなら Dialog 方式 |
| Media3 の依存衝突・ APK サイズ増 | 小 | 本体同梱分を再利用、足りなければ shadow でリネーム |
| 端末によって GPU エフェクトやエンコーダが失敗 | 小 | 失敗時は元ファイルで続行を選べるようにする |

## マイルストーンと未決事項

Phase 1 の「無編集で差し替えた動画が普通に投稿できる」が通れば、残りは編集機能を積むだけになる。

1. Phase 0 調査（完了）: フレームワーク判定、録画完了点と投稿 API の特定、PairIP 確認、公式動画の `ffprobe`。残りは UI フロー実機確認（Phase 1 へ持ち越し）
2. Phase 1 差し替え PoC:
   - (a) インストール元チェックの限定パッチを適用した再署名 APK が起動・ログインできるか（PairIP 関門、Go/No-Go その1。Android 13 実機でログイン画面 → 安全設定画面への到達、40秒後も生存を確認。暗号鍵の移行・作成、過去動画へのアクセスは未確認）
   - (b) `vb0.G0` 直前で固定の動画ファイルに差し替え、サブアカウントで投稿が通るか（Go/No-Go その2: Go。差し替えた動画の dev グループへの投稿・別端末での再生はユーザー確認済み。途中の「ロックされた投稿です」は受信側の鍵再生成で解消したとの報告あり。送信側の一時診断では相手用鍵の付与・鍵 ID の一致・投稿用暗号鍵の一致を確認済み）
3. Phase 2 編集画面: EditorDialog ＋ Renderer、フィルターとテキスト（F1〜F3）。実装済み。実機で編集・書き出し・本体への受け渡しと、dev グループへの投稿・別端末での再生を確認
4. Phase 3 任意動画: 「ファイルから」ボタンとトリム（F4）。実装済み。実機で取り込み・トリム・仕様への変換・本体への受け渡しと、dev グループへの投稿・別端末での再生を確認
5. Phase 4 仕上げ: 設定画面（F6）、F5 の可否判断、バンドルとして公開するか決める

未決事項:

- [x] パッケージ名は `com.newchat.setlog` → 確定（v1.3 / versionCode 76）
- [x] フレームワークはネイティブ / RN / Flutter → ネイティブ（CameraX + Camera2）
- [x] 公式動画の秒数・解像度・コーデック・音声の有無 → HEVC Main / 短辺720長辺1280 / ~30fps / AAC-LC 48k mono / 約2.07秒。詳細は [analysis/phase0-output-profile.md](analysis/phase0-output-profile.md)（保存経路の値。投稿経路との差は Phase 1 で検証）
- [x] 投稿時刻はサーバー決定かクライアント送信か → クライアント送信（body の timestamp）
- [x] 署名チェック・ Play Integrity の有無 → 署名検証・Play Integrity は無し。PairIP licensecheck（起動時 Play ライセンス確認）のみ
- [x] linimal と同じリポジトリか別か → 別リポジトリ（cutlog 単独）
- [x] 本体同梱の media3 を再利用できるか → できない（難読化済みで、一部は元の名前で残るため衝突もする）。media3 1.11.1 を `dev.utaa.cutlog.shaded.*` へ付け替えて同梱し、シェーダーは resource パッチで追加する
- [x] 編集 Activity 方式か Dialog 方式か → 全画面 Dialog。マニフェスト登録が不要で、カメラ画面の状態を保ったまま本体の処理へ戻せる
