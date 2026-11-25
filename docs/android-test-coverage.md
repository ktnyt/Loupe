# Android計測テストの実行条件

対象は `app/src/androidTest/` の `ArchiveInstrumentation`。GitHub Actionsの `verify` は計測APKをビルドし、新しく作ったAndroid 15エミュレータで [CIの合成モード](../tests/ci-android-modes.txt) を実行する。エミュレータには検査用に署名したAPKと、その計測APKだけを導入する。写真オリジナル、カメラ、サービスの認証情報は使わない。

合成モードの一覧は実行順。共有履歴とカメラ資格情報の再起動確認ではseedからverifyへ進み、USBの選択状態は最後に既定へ戻す。計測1回ごとにPASSを確認し、失敗したモードや起動できなかった計測もCIの失敗とする。

## 専用の準備が必要なモード

| モード | 必要な準備と確認先 |
| --- | --- |
| `jpeg` | 読み取り許可のあるJPEG 3枚をMediaStoreに用意する。元データと表示用プレビューの照合を行う。 |
| `burst-real`、`burst-gap-millis` の実RAW指定 | 承認済みのRAWコーパスを検査用領域に用意する。合成fixtureによる連写状態と区別する。[連写番号の検証](design/burst-gap-millis/README.md) |
| `import-latency`、`import-deferred`、`import-interruption`、`import-pause-interruption` | 合成RAW画像とframe commitの時刻・画面遷移を調べる。比較結果を記録する際は専用エミュレータまたは実機で画面状態と記録条件をそろえる。[取り込みから一覧反映まで](diagnostics.md#新規取り込みから一覧反映まで) |
| `camera-benchmark`、`camera-pilot-wifi`、`camera-pilot-paired-wifi` | 実際のカメラへのWi-Fi経路、検査用の写真、ペア接続が必要。[ペア接続の契約](camera-paired-connection.md) |
| `camera-pilot-qr-status`、`camera-pilot-ble-advertisement`、`camera-pilot-ble-saved`、`camera-pilot-ble-diagnostics` | 実カメラの登録状態、BLE通知、無線接続条件を指定。合成QRやペアセッションの検査とは区別する。[BLE起動の契約](camera-ble-wake.md) |
| `camera-pilot-sd`、`camera-pilot-sd-source-diagnostic`、`camera-pilot-cleanup` | 参照元のSD、必要な読み取り許可、承認済みfixture、対応する既存試行の記録が必要。[カメラ取り込みの契約](camera-import-contract.md) |
| `gallery-screen-talkback`、`dashboard-talkback`、`ranking-result-talkback` | TalkBackを使用する実機手順。通常のラベル・状態の計測とは区別し、フォーカス・音声・ジェスチャーを確認する。[TalkBackの検査手順](development-workflow.md#talkbackを一時的に使う検証) |
| モード指定なし、`cleanup`、`jpeg-cloud`、`jpeg-cleanup`、`compact` | 旧Amazon Photos経路。認証済みの隔離環境に検査用のRAW・JPEGと復元記録を用意する。容量整理に必要な権限は隔離環境に限る。[Amazon Photosの接続調査](cloud-research.md) |
| `e2-archive`、`e2-background`、`e2-large-original`、`e2-mixed-{backup,consent,restore}`、`e2-catalog-{publish,republish,restore,previous}` | 認証情報と隔離されたバージョニング有効の検査用バケットが必要。復元専用モードは発行したカタログまたは元の検査IDにも依存する。[e2接続試行](e2-android-pilot.md)、[実運用契約](e2-production-contract.md) |
| `e2-large-cache`、`e2-cleanup-product-{seed,sync,offline,restore,stop}`、`e2-cleanup-shared-{seed,denied,allowed,live,preview}` | `e2verify` や `e2fixture` の別APK、空のライブラリ、権限とユーザーの切り替え、各試行固有のIDが必要。サービスとの照合を含むモードには隔離バケットも用意する。[e2の実運用移行 #117](https://github.com/ktnyt/Loupe/issues/117) |
| `photo-storage`、`photo-storage-{archive,archive-snapshot,archive-ui,inventory,inventory-ui,performance,ui,ui-run,ui-run-skipped}`、`photo-storage-{detach,abandon-detach}-{seed,absent,restored}`、`photo-storage-saf-{seed,verify}` | 書き込み可能な副ストレージを持つ専用エミュレータまたは検査用カードを使う。付け外し・SAF権限・別アプリ所有・移動性能はトリガーと順序をそろえる。[保存先と移動の検証](photo-storage-contract.md#検証)、[移動時間の計測](diagnostics.md#取り込み済み写真の保存先移動issue-162) |
| `RawEditInstrumentation` | `:raw-experiment:assembleDebugAndroidTest` と同モジュールの検査APKを使う。GPUの合成ケースと、`real` または `viewportUri` に承認済みのRAWが必要なケースを区別する。[RAW実験アプリの検証](cnt-look-poc.md#開発と確認) |

上記の専用モードは、対象の入力を準備したあと、該当手順で `adb -s SERIAL shell am instrument -w -e mode MODE jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation` に必要な引数を渡す。端末の選定、実施記録、利用者データの取り扱いは[開発手順](development-workflow.md)に従う。
