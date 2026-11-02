# Phase 0 互換性: PairIP / 改ざん検知・整合性保護

対象: setlog `com.newchat.setlog` v1.3 / versionCode 76（Google Play 版）
手段: `jadx` 静的解析 + `unzip -l`（読み取りのみ）

## 結論

PairIP は **licensecheck（Google Play ライセンス確認）止まり**。コード仮想化（VMRunner）やネイティブ整合性保護は無く、APK 自身の署名ハッシュを検証する処理も見当たらない。再署名した改変 APK の起動・ログイン可否は、アプリ内チェックではなく **Play ライセンス応答がどう返るか**にほぼ依存する。

→ linimal と同じく「署名検査を変更しない」方針で進められる見込み。ただし起動時の Play ライセンスチェックが別の関門になる。Phase 1 冒頭で無改変リパッケージの起動可否を確認する。

## 1. PairIP の構成

- Manifest の `application android:name` = `com.pairip.application.Application`（AndroidManifest.xml:46）
- `pairip/application/Application.java:8-13` は `SetlogApp` を継承し `attachBaseContext` で `LicenseClient.checkLicense(context)` を呼ぶだけ。実体の Application は `com.newchat.setlog.SetlogApp`（Firebase 等を初期化する通常実装、ネイティブロード無し）
- `LicenseContentProvider` は Manifest 未登録（クラスは存在するが未使用）。登録は `LicenseActivity`（AndroidManifest.xml:369-370, exported=false）のみ
- ネイティブ `.so` は APK 内に 1 つも無い（`lib/` 空、`extractNativeLibs=false`）。dex は `classes.dex` 単一。暗号化された追加 dex/assets 無し（assets は `dexopt/baseline.prof`/`baseline.profm` のみ）
- `libpairipcore.so`・VMRunner・バイトコード仮想化の痕跡なし（find/grep ヒット 0）

→ PairIP の「ライセンスチェックのみ」軽量バリアント。アクティブなコード改ざん検知の中核（ネイティブ VM）は無い。

## 2. 署名・整合性チェック

- **アプリ自身の APK 署名検証は無し。** `com/newchat` 配下に `getPackageInfo`/`signatures`/`GET_SIGNING_CERTIFICATES`/`signingInfo` の使用なし
- `defpackage/df2.java`（署名 API ヒット）は GMS の `GoogleSignatureVerifier`。対象は `com.android.vending`/`com.google.android.gms` の署名で GMS 内部 IPC 相手確認。setlog 自身の署名検証ではない
- **Play Integrity / StandardIntegrityManager / SafetyNet は未使用**（grep ヒットは Room のスキーマハッシュや WebAuthn/FIDO2 の誤検出）
- PairIP ライセンス検証（`LicenseResponseHelper.java`）: Play licensing service が返す JWS を、ハードコードされた RSA 公開鍵（`LicenseClient.licensePubKey`）で RS256 署名検証し、payload の `packageName` が `com.newchat.setlog` と一致するか確認（`LicenseResponseHelper.java:27-44`）。これはライセンス応答の検証であって APK 署名の検証ではない
- 失敗時の挙動（`LicenseClient.java`）:
  - `handleError` → `FULL_CHECK_OK` でもローカル合格でもなければ `startErrorDialogActivity`
  - `LicenseActivity` が「Something went wrong …」ダイアログを出し、`eventualShutdownEnabled` により 30 秒後 `System.exit(0)`（`LicenseActivity.java:103-114`）
  - 応答コード 2（NOT_LICENSED）は Play ペイウォール Intent 起動後アプリ終了
  - `performLocalInstallerCheck`（`LicenseClient.java:259-295`）は installer が `com.android.vending` かを確認。**Play 以外からのインストール（サイドロード）ではローカルチェック不合格 → Play へのフルライセンスチェックが必須になる**

## 3. その他の環境検知

- root / su / RootBeer / Magisk 検知: なし
- デバッガ / Frida / Xposed 検知: なし
- エミュレータ: `defpackage/lm6.java:19-23` が goldfish/ranchu 等を判定するが、GMS/datatransport(Firelog) のテレメトリ用でセキュリティゲートではない

## 4. 総合評価（確度付き）

- コード仮想化・ネイティブ整合性保護は無い — 高確度
- APK 署名ハッシュの検証は無い — 高確度
- 起動/ログイン可否は Play ライセンス応答次第 — 中確度。起動時（attachBaseContext）に Play licensing service へ問い合わせる。サイドロードした再署名 APK は installer が Play でないためローカルチェックを通らずフルチェックに依存する。使用する Google アカウントがこのアプリを Play 上で「入手済み」なら LICENSED が返り起動可能。未入手・Play 無効環境では NOT_LICENSED/バインド失敗 → エラーダイアログ + 30 秒 exit で起動できない可能性。端末・アカウント・ネットワーク依存で静的には確定不能

## Phase 1 への含意

1. 最初の関門は「無改変リパッケージ APK（パッチ内容ゼロで再署名のみ）が起動・ログインできるか」。この端末は Play からの入手済み（installerPackageName=com.android.vending）なので、同一 Google アカウントでネットワークがあれば LICENSED が返る見込み
2. 通らない場合の選択肢: 起動時の `LicenseClient.checkLicense` 呼び出しを無効化する（`pairip.application.Application.attachBaseContext` から checkLicense を呼ばせない、または checkLicense を no-op にパッチ）。これはサーバー通信への介入ではなく起動時ローカル分岐の無効化なので、linimal の通信不介入原則とは別枠で判断できる。実施するかは起動確認の結果を見て相談する

## 未検証

- Play licensing service が特定アカウント・端末で実際に返すコードは動的要素で静的判定不能
- `defpackage` は R8 難読化済みで全クラス未精査。整合性保護関連キーワードの横断 grep では PairIP と GMS 以外のヒット無し
