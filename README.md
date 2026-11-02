# cutlog

setlog（New Chat Inc. の Android アプリ、`com.newchat.setlog`）向けの Morphe パッチバンドル。「撮った動画を投稿前に編集する」「端末内の任意の動画をその時間のログとして投稿する」を追加する。linimal と同じ構成（patches + extension）。

現在 Phase 0（事前調査）。実装はまだ無い。

## ドキュメント

- [docs/DESIGN.md](docs/DESIGN.md) — 設計書（要件・アーキテクチャ・マイルストーン）
- [docs/analysis/phase0-recording-upload.md](docs/analysis/phase0-recording-upload.md) — 録画完了点とアップロード経路の静的解析
- [docs/compatibility/pairip-integrity.md](docs/compatibility/pairip-integrity.md) — PairIP / 改ざん検知・整合性保護の調査

## Phase 0 の到達点

- 対象: `com.newchat.setlog` v1.3 / versionCode 76 / arm64-v8a（Google Play 版）
- フレームワーク: ネイティブ Android（CameraX + Camera2 フォールバック）。Flutter / React Native ではない
- フック点: 録画完了の単一 choke point `vb0.G0(oc0)`（難読化後の名前）
- アップロード: 署名付き URL への HTTP PUT。メディアは暗号化される
- 投稿時刻: クライアント送信値（body の `timestamp`）
- 整合性保護: 署名検証・Play Integrity は無し。PairIP licensecheck（起動時 Play ライセンス確認）のみ
- 残件: 公式撮影分の動画仕様（`ffprobe`）、UI フロー実機確認

## 注意

- APK・逆コンパイル結果・署名鍵はコミットしない（`.gitignore` 済み。`setlog-apk/` はローカル専用の読み取り入力）
- 検証はサブアカウントで行う。利用規約・アカウント停止のリスクがある
