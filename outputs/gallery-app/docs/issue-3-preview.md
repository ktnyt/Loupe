# Issue #3: 連写の初回表示

## 目的・契約

連写を開いた際に、操作対象の写真をサムネイル全件の処理完了より先に表示する。
2026-10-25、実装前にIssue #3の作業ID `20261025-issue3-preview` の開始コメントで契約を記録した。

- heroをstripより先に投入し、既に待機中の背景画像より優先する。実行中の処理は中断しない。
- 同一優先度では投入順を保ち、ワーカー数は従来の2とする。
- 画面世代が変わった待機中の連写画像は読み込まない。結果・失敗の反映は世代と画像キーが一致する場合に限る。
- 写真原本、順位、タグ、選択状態、UI配置、読み上げの操作名は変更しない。
- 失敗時は既存の読込失敗の読み上げを維持し、戻る・前後移動を阻害しない。

## 根拠と計測

従来はstrip全件を共有FIFOに投入した後にheroを投入していた。
PhotoRepository.previewはsynchronizedなので、RAW抽出・縮小キャッシュ生成も競合する。
PreviewExecutorは待機中のheroを優先する。進行中のI/Oや同期待ちは残る。

`LoupePreview`ログは個人情報やファイル名を含めず、メモリキャッシュ有無、枚数、形式、キュー待ち、生成・デコード時間、ImageViewへの反映までを記録する。
`tap_to_bind_ms`は連写を開くタップからの時間（前後移動時は最初に開いてからの経過）。GPU描画完了時間ではない。ディスクキャッシュの有無はこのログだけでは判断できない。

## 検証

- PreviewExecutorTest: 実行中の2件をラッチで停止し、待機100件よりheroが先になることと同優先度の順序を検証。
- assembleDebug / lintDebug: 成功。
- 実機比較結果は検証後に追記する。

### 2026-10-25 実機結果（JST）

端末25128PNA1G / Android 16、0.17.3(28)。比較用は同じ計測コードで従来の投入順・優先度なしに戻したAPK。いずれも原本・順位・タグを変更せずデータ保持で更新した。

| 対象 | 従来順 tap→反映 | 修正版 tap→反映 | 従来→修正版のキュー待ち |
| --- | ---: | ---: | ---: |
| RAW20枚、生成済みディスクキャッシュ・メモリmiss | 64ms | 22ms | 46→0ms |
| JPEG6枚、メモリmiss | 44ms | 20ms | 21→2ms |

各1回の観測で統計的な保証ではない。JPEGのディスクキャッシュの全件有無は検査していない。
従来順でRAW20枚を最初に開いた際は9577ms（キュー9073ms）だったが、ディスクキャッシュ生成前後の条件が異なるので22msとの同条件比較には使わない。
修正版のRAW20枚再表示は13ms。表示中写真の再選択ではmemory=hit経路を確認した（開いてからの経過値のため初回時間とは比較しない）。
RAW7枚から4枚へ移動し、別写真の表示、4枚の表示範囲、操作の維持を画面で確認。4枚を初めて開いた際は1012ms（キュー479ms、読込529ms）で、実行中の処理を待つ時間は残る。

未検証: 完全にディスクキャッシュを空にした同条件比較、故意の読込失敗、遅延した旧画面の結果を強制的に到着させる試験、他機種、大量連写でのUI構築コスト。
世代とキーによる結果反映の条件はコードを確認したが、これを強制競合試験の代わりとはしない。

再現コマンド（リポジトリルート）:

```sh
mkdir -p /tmp/loupe-preview-tests
javac -d /tmp/loupe-preview-tests outputs/gallery-app/app/src/main/java/jp/nano/rawgallery/PreviewExecutor.java outputs/gallery-app/tests/PreviewExecutorTest.java
java -cp /tmp/loupe-preview-tests jp.nano.rawgallery.PreviewExecutorTest
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools outputs/gallery-app/gradlew -p outputs/gallery-app assembleDebug lintDebug
```

対応の判定: 優先投入の契約は順序テストと実測で確認。失敗と強制競合を含む全完了条件は未検査を残すため、Issueは開いたままとする。
