# Loupe

Android向けの個人用写真ギャラリーです。RAWの埋め込みJPEGとJPEG写真の閲覧、連写の比較・ベストショット選定、原本の共有に対応しています。

## プロジェクト

Androidアプリは [`outputs/gallery-app`](outputs/gallery-app) にあります。機能の詳細と過去の検証記録は[アプリのREADME](outputs/gallery-app/README.md)、設計の経緯は[設計文書](outputs/gallery-app/docs/design.md)を参照してください。

- Android 11以降（minSdk 30）
- バージョン: 0.15.1
- RAWは現像せず、内蔵プレビューを表示します。
- Amazon Photos連携は非公式の接続方法を使用した試作です。自動同期・端末原本の整理は初期状態では無効です。

## ビルド

JDK 17、Android SDK Platform 35、Build Tools 35.0.0を用意し、`ANDROID_HOME` を設定してください。

```sh
cd outputs/gallery-app
./gradlew assembleDebug lintDebug
```

開発用APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## テスト

```sh
cd outputs/gallery-app
./tests/ranking.sh
./tests/cloud-policy.sh
```

RAW抽出の実ファイル検証には、別途用意した写真と参照JPEGが必要です。手順はアプリのREADMEを参照してください。写真原本、認証情報、署名鍵、APK、端末の検証ログはこのリポジトリに含めません。
