# Loupe

LoupeはAndroid 11以降向けの写真・動画ライブラリです。カメラからの取り込み、連写の選抜、RAW現像、外部アプリへの共有、オリジナルの保管と復元を扱います。

## 主な機能

- RAW・JPEG・動画をアルバムや撮影機種で探し、写真を拡大したり、対応する動画を再生したりできます。連写の比較・順位付け、タグ付け、共有先と形式の指定に対応します。
- USBカメラ、SDカード、登録した対応Wi-Fiカメラから取り込みます。保存先と取り込み履歴を管理します。 [カメラ取り込み](docs/camera-quick-import.md) [保存先と履歴](docs/photo-storage-contract.md)
- IDrive e2へ写真・動画のオリジナルを保管し、復元一覧から取り戻せます。端末の容量整理は利用者が有効にし、保全条件を満たした場合に行います。 [IDrive e2の運用](docs/e2-production-contract.md)
- ライブラリでのRAW閲覧には埋め込みJPEGを使います。実験的なRAW編集では原本を読み取り、現像結果を別のJPEGとして保存します。 [RAW編集](docs/raw-editor-contract.md)
- Nano Bananaで写真を編集し、生成画像を別のJPEGとして保存できます。利用には外部サービスとIDrive e2の設定が必要です。 [Nano Banana](docs/nano-banana.md)
- 「処理の状況」でバックグラウンド処理を確認できます。診断は記録するかを選び、必要な範囲をZIPとして端末内に保存できます。 [診断](docs/diagnostics.md)

LoupeからAmazon Photosへの接続、同期、原本取得は終了しました。これまでの保管記録とプレビューは端末に残ります。IDrive e2に移行済みのオリジナルは、復元一覧から書き出せます。

ビルドする場合は[セットアップ](#ビルド)、普段使う端末の更新は[OTAの手順](docs/ota-updates.md)を参照してください。

## 開発資料

- [現行の目的駆動設計](docs/current-design.md)
- [issue進捗記録](docs/issue-progress.md)

## 履歴資料

- [初期設計の記録](docs/design.md)
- [過去のUIデザイン・検証資料の索引](docs/design/README.md)
- [初期実装と検証の記録](docs/history/initial-implementation.md)

## ビルド

Gitの履歴をすべて取得した作業コピー、JDK 17、Python 3を用意してください。Gradle Wrapperを使います。アプリのビルド番号を決める処理が、構成時に `python3` で `origin/main` の履歴を読みます。

Android SDKには Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1が必要です。SDKの場所は `ANDROID_HOME` またはローカルの `local.properties` で指定します。端末やエミュレータで検証する場合は Platform Tools も使います。

```sh
git fetch origin main
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
./gradlew :raw-experiment:assembleDebug
python3 scripts/check-native-apk-assets.py \
  app/build/outputs/apk/debug/app-debug.apk \
  raw-experiment/build/outputs/apk/debug/raw-experiment-debug.apk
```

`app-debug.apk` は開発用です。普段使う端末には、`main`から既存の配布署名で作った非デバッグのReleaseを導入します。公開版の準備・配布・読み戻しは[開発手順](docs/development-workflow.md#apkとmainの照合)を参照してください。

### RAW抽出の追加検査

抽出処理の検証はJDK 17と実ファイルを使います。
第１引数はRAWフォルダ、第２引数はExifToolで抽出したJPEGフォルダです。
比較用JPEGはRAWの拡張子だけを `.jpg` に置き換えた名前にしてください。
GR IVは `SubIFD1:PreviewImage`、Leitzphoneは `SubIFD:PreviewImage`、OMの２機種は `Olympus:PreviewImage` を使います。

```sh
./tests/run.sh /path/to/raw-samples /path/to/reference-jpegs
```

個人のRAWと写真はソース一式に含めません。

## JPEG acknowledgment

This software is based in part on the work of the Independent JPEG Group.
