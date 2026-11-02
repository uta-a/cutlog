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
- 書き出しは 2〜4 秒のクリップで数秒以内
- パッチが当たらない版では何もしない（公式の撮影フローを壊さない）
- アプリ更新への追従が楽なよう、フック点は fingerprint で探し、クラス名直書きを避ける

## アーキテクチャ

公式の撮影画面と投稿処理はそのまま使い、その間の「録画完了」の1点だけをフックする。編集も任意動画も、この1点に別のファイルを渡すだけで成立する。

フック点は Phase 0 で特定した `vb0.G0(oc0)`（録画完了の単一 choke point）。詳細は [analysis/phase0-recording-upload.md](analysis/phase0-recording-upload.md)。

## モジュール設計

パッチ側は「フックを差し込むだけ」に留め、ロジックはすべて extension に置く。linimal と同じ構成。extension の言語は、Kotlin stdlib が本体と衝突するのを避けるため Java を基本とする（linimal の方針を踏襲。Phase 1 で本体の Kotlin 同梱状況を確認して確定）。

```
cutlog/
├─ patches/src/main/kotlin/.../cutlog/
│   ├─ shared/Fingerprints.kt        # 録画完了・投稿・カメラ画面の fingerprint
│   ├─ editor/VideoEditorPatch.kt    # 録画結果の横取り（F1〜F3）
│   ├─ picker/GalleryPostPatch.kt    # カメラ画面に「ファイルから」ボタン（F4）
│   ├─ manifest/RegisterActivityPatch.kt  # 編集 Activity をマニフェストに追加
│   └─ settings/SettingsPatch.kt     # 設定画面に項目追加（F6）
└─ extensions/cutlog/src/main/java/.../
    ├─ hook/CaptureInterceptor.java  # パッチから呼ばれる入口
    ├─ editor/EditorActivity.java    # プレビュー＋編集 UI
    ├─ editor/EditSpec.java          # フィルター・テキストの設定値
    ├─ render/Renderer.java          # Media3 Transformer で書き出し
    ├─ render/OutputProfile.java     # 公式撮影分に合わせる出力仕様
    └─ pref/Prefs.java               # ON/OFF 設定
```

主なインターフェース:

- `CaptureInterceptor.onCaptured(ctx, result, resume)`：パッチが `vb0.G0(oc0)` の入口で呼ぶ。元の続きの処理を `resume` として受け取り、編集後の `oc0`（差し替えた mp4 を指す）で呼び直す
- `Renderer.render(...)`：Media3 Transformer は Looper スレッドの非同期 API なので、戻り値で File を返さず、完了コールバックで編集済みファイルを渡す形にする
- 編集画面は独立 Activity。マニフェスト登録が要るため Morphe のリソースパッチで追加する。登録が重ければ、既存 Activity 上の全画面 Dialog に切り替える
- 編集 Activity 起動でカメラ画面が onPause/解放される挙動と、`resume` クロージャがプロセス kill で失われる点は Phase 1 で検証する
- 依存ライブラリ（Media3 Transformer / Effect / ExoPlayer）は、本体が既に media3 を同梱しているため同梱要否を Phase 1 で判断する。同梱する場合は shadow でリネーム

## 編集エンジン

書き出しは AndroidX Media3 Transformer、プレビューは ExoPlayer に同じ Effect を渡して作る。編集内容は `EditSpec` だけで表し、プレビューと書き出しで共通にする。

| 機能 | 実装 |
| --- | --- |
| モノクロ / セピア | `RgbFilter.createGrayscaleFilter()`、`RgbMatrix` で色行列 |
| 明るさ・コントラスト・彩度 | `Brightness`、`Contrast`、`HslAdjustment` |
| LUT プリセット | `SingleColorLut`（.cube を assets に同梱） |
| テキスト | `OverlayEffect` ＋ `TextOverlay`（SpannableString で色・サイズ、`OverlaySettings` で位置） |
| スタンプ・画像 | `BitmapOverlay`（将来拡張） |
| トリム | `MediaItem.ClippingConfiguration` |
| リサイズ・クロップ | `Presentation.createForWidthAndHeight()`、縦横比は公式撮影分に合わせる |
| 音声 | 公式が無音なら `setRemoveAudio(true)` |

注: 本体の media3 は R8 で切り詰められているため、上記 API（`RgbFilter`、`OverlayEffect` 等）が本体側に残っているかは Phase 1 で確認する。残っていなければ extension に同梱する。

編集 UI:

- 上半分にループ再生プレビュー、下半分にタブ（フィルター / テキスト / トリム）
- テキストはプレビュー上でドラッグして位置決め。位置は -1〜1 の正規化座標で `EditSpec` に持つ
- 「投稿」で書き出し → 完了したら `resume(編集後の oc0)`。失敗時は元ファイルで続行するか選ばせる
- 最後に使ったフィルターを Prefs に保存し、次回の初期値にする

出力仕様（`OutputProfile`）は Phase 0 で `ffprobe` した公式撮影分の値を既定値にし、入力に関わらずそこへ揃える。

## 任意動画の投稿と時刻の扱い

任意動画（F4）は「録画の代わりにファイルを渡す」だけで実現し、投稿処理は公式のまま使う。過去の時間帯（F5）は Phase 0 で「投稿時刻はクライアント送信値」と判明したため技術的には書き換え可能だが、サーバー検証とスコープ定義（サーバー送信値の操作）の両面で Phase 4 に判断を持ち越す。

任意動画の流れ:

1. カメラ画面に「ファイルから」ボタンを足す（シャッター長押し等でも可）
2. Photo Picker（`ActivityResultContracts.PickVisualMedia`、VideoOnly）で動画を選ぶ。ストレージ権限は不要
3. 編集画面で開始位置を選び、公式と同じ秒数にトリム＋フィルター＋テキスト
4. `OutputProfile` に揃えて書き出し、`vb0.G0(oc0)` に差し替えた `oc0` を渡す
5. 以降は公式の投稿フロー（グループ選択・アップロード）をそのまま通す

カメラを開かずに録画完了を偽装できない実装なら、一度公式で短く撮影させ、完了時に差し替える「撮影→差し替え」方式に落とす。

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
| PairIP licensecheck で起動不可 | 大 | Phase 0 で署名検証・整合性保護は無しと判明。ただし起動時 Play ライセンスチェックあり。Phase 1 冒頭で無改変リパッケージの起動可否を確認する。詳細は [compatibility/pairip-integrity.md](compatibility/pairip-integrity.md) |
| アプリ更新でフック点が変わる | 中 | fingerprint は文字列・命令パターン基準。対応バージョンを `compatibleWith` で固定 |
| 編集 Activity 遷移でカメラ解放・resume 無効化 | 中 | Phase 1 で遷移後の resume 成否を検証。だめなら Dialog 方式 |
| Media3 の依存衝突・ APK サイズ増 | 小 | 本体同梱分を再利用、足りなければ shadow でリネーム |
| 端末によって GPU エフェクトやエンコーダが失敗 | 小 | 失敗時は元ファイルで続行を選べるようにする |

## マイルストーンと未決事項

Phase 1 の「無編集で差し替えた動画が普通に投稿できる」が通れば、残りは編集機能を積むだけになる。

1. Phase 0 調査（完了）: フレームワーク判定、録画完了点と投稿 API の特定、PairIP 確認。残りは公式動画の `ffprobe` と UI フロー実機確認
2. Phase 1 差し替え PoC:
   - (a) 無改変リパッケージ APK が起動・ログインできるか（PairIP 関門、Go/No-Go その1）
   - (b) `vb0.G0` 直前で固定の動画ファイルに差し替え、サブアカウントで投稿が通るか（Go/No-Go その2）
3. Phase 2 編集画面: EditorActivity ＋ Renderer、フィルターとテキスト（F1〜F3）
4. Phase 3 任意動画: 「ファイルから」ボタンとトリム（F4）
5. Phase 4 仕上げ: 設定画面（F6）、F5 の可否判断、バンドルとして公開するか決める

未決事項:

- [x] パッケージ名は `com.newchat.setlog` → 確定（v1.3 / versionCode 76）
- [x] フレームワークはネイティブ / RN / Flutter → ネイティブ（CameraX + Camera2）
- [ ] 公式動画の秒数・解像度・コーデック・音声の有無（実機 ffprobe 待ち）
- [x] 投稿時刻はサーバー決定かクライアント送信か → クライアント送信（body の timestamp）
- [x] 署名チェック・ Play Integrity の有無 → 署名検証・Play Integrity は無し。PairIP licensecheck（起動時 Play ライセンス確認）のみ
- [x] linimal と同じリポジトリか別か → 別リポジトリ（cutlog 単独）
- [ ] 本体同梱の media3 を再利用できるか（R8 で必要 API が残っているか）
- [ ] 編集 Activity 方式か Dialog 方式か（resume 検証次第）
