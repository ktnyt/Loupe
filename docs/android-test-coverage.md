# Android計測テストの実行条件

GitHub Actionsの `verify` は本体と `raw-experiment` のAPK、各モジュールの計測APKをビルドし、Android 15エミュレータで合成データを検査する。本体の `ArchiveInstrumentation` は [CIの合成モード一覧](../tests/ci-android-modes.txt) の順で実行する。`camera-pilot-usb-launch` からは新しく作ったAVDを使う。後半の最後に別のapplicationIdを持つ実験用APKと計測APKを導入し、`RawEditInstrumentation` に `-e full true` を渡す。写真オリジナル、カメラ、サービスの認証情報は使わない。

CIではAndroid SDKの `system-images;android-35;google_apis;x86_64` とANGLEのソフトウェア描画（`-gpu swangle`）を使う。手元では `:app` と `:raw-experiment` それぞれの `assembleDebug` と `assembleDebugAndroidTest` を実行してから `bash tests/ci-android-instrumentation.sh` を実行する。CIと同じ分割で実行する場合は `before-camera` と `camera-onward` を順に指定する。Apple siliconでは `LOUPE_INSTRUMENTATION_IMAGE='system-images;android-35;google_apis;arm64-v8a'` を指定する。既定ポート5580を使用中なら `LOUPE_INSTRUMENTATION_PORT` に空いている偶数ポートを指定する。

合成モードの一覧は実行順。共有履歴とカメラ資格情報の再起動確認ではseedからverifyへ進み、USBの選択状態は最後に既定へ戻す。UIを計測するモードへ幅400dp・文字倍率1.0を渡す。`burst-gap-millis` は連写番号あり・なしを合成JPEGで検証する。`camera-pilot-ble-diagnostics` では、検査用APKへの権限付与後にBLEの事前検査で保存する診断項目を合成入力で検証する。CIは、adbが終了値0を返し、標準出力にPASSを示す結果が1件あり、最後にAndroidの結果コード `-1` が1件あることを確かめる。`camera-history-migration` では結果の行全体を、`confidence-store`・`camera-ledger`・`camera-ledger-teardown` では行の先頭を、モード固有の文言と照合する。標準エラーは判定に使わず、読み返せるように出力する。[ホスト回帰テスト](../tests/test-ci-android-instrumentation.py)では、adbの応答を差し替え、同じ判定処理をエミュレータなしで実行する。

`camera-ledger-teardown` は `camera-ledger` の直後に実行する。合成データ用にアプリの非公開領域に作った実際のSQLiteデータベースを使い、最初の削除を失敗させても後続のデータベースが削除されることを確かめる。検査本体が例外やエラーを出したときは、その失敗が主例外のまま残り、後片付けの失敗も記録されることを確かめる。後片付けだけが失敗する場合と、すべて成功する場合も検査する。

`RawEditInstrumentation` の `full` はEGLの色・向き・比較表示に加え、合成DNGのデコード、原寸タイル、JPEG保存と中止を検査する。本体の `photo-storage-import-guard` は、移動が取り込み履歴を更新する間だけ、新しい取り込みを止めることを確かめる。`diagnostic-failures` は合成エラーを使い、クラッシュや保存失敗の記録と、個人データを出力しないことを検査する。

`album-tags`、`library-tabs`、`gallery-position` はそれぞれ、空のAVDに合成JPEG 72枚と24アルバムを作り、実際の索引を使う。セル8／上端-37px（アルバム）とセル27／上端-49px（3列の写真一覧）の位置復帰、アルバムと写真のセルから開いたビューアとタグ画面の往復、索引を再読込したあとの位置を検査する。CIは素材不足で検査が省略された場合に失敗する。各検査の終了時に作成した写真を削除し、索引を更新する。

`media-observer-lifecycle` は開いている一覧の通常更新と、Activityの破棄後に届くMediaStore通知と更新処理が再実行されないことを確かめる。

`import-latency`、`import-deferred`、`import-interruption`、`import-pause-interruption` は、合成RAWを使って新規入力の表示と中断を確認する。処理速度を比較する場合は、端末、画面、画像、診断の条件をそろえて測定する。[取り込みから一覧反映まで](diagnostics.md#新規取り込みから一覧反映まで)

`camera-pilot-sd-usb-reprobe` はUSB機器を接続していない専用エミュレータで、手動で開く操作と長押し、再認識の表示、USB許可の保留・拒否、フォルダ選択、取り込みとの排他を検査する。実機やUSBパススルーでは実行を拒否する。実際のUSB許可、マウント中のリーダーの取得と解放、カードの再認識と転送は別途実機で確認する。[SD再認識の契約](sd-usb-reprobe.md)

## 専用の準備が必要なモード

| モード | 必要な準備と確認先 |
| --- | --- |
| `release-upgrade-seed`、`release-upgrade-verify` | 専用AVDで、デバッグ版に合成データを作り、同じ署名かつ上位versionCodeの非デバッグ版をデータ保持で上書きする。上書き後に写真・履歴・順位・タグ・暗号化された合成設定を照合する。[非デバッグ版への移行 #199](https://github.com/ktnyt/Loupe/issues/199) |
| `diagnostic-local-export` | 専用AVDに非デバッグ版と対応する計測APKを導入する。合成ファイルで記録開始・停止、個人情報の除外、ZIP出力の失敗、システムのフォルダ選択先を検査する。[診断ZIPのローカル出力 #221](https://github.com/ktnyt/Loupe/pull/221) |
| `jpeg` | 読み取り許可のあるJPEG 3枚をMediaStoreに用意する。元データと表示用プレビューの照合を行う。 |
| `tag-lifecycle`、`related` | タグの付いた検査用写真を索引に登録する。`tag-lifecycle` は `DCIM/OMDS/`、`related` は同じフォルダにタグ付与済みの例と未判定の候補が必要。素材がない場合、`related` の合成画像によるモデル推論までは動くが、類似写真の選出やキャッシュ再読込は未検査。 |
| `camera-pilot-dual-slot-ui`、`camera-pilot-sd-swap`、`camera-pilot-sd-revision` | 合成カードでもテストコードが隔離APKのapplicationIdとエミュレータを要求する。通常の本体APKは起動時に拒否される。UIは400dp/1.0と320dp/1.3の既存手順で確認する。[二つのSDスロットの検証 #184](https://github.com/ktnyt/Loupe/issues/184)、[再挿入と保存済み判定 #166](https://github.com/ktnyt/Loupe/issues/166) |
| `burst-real`、`burst-gap-millis` の実RAW指定 | 承認済みのRAWコーパスを検査用領域に用意する。合成fixtureによる連写状態と区別する。[連写番号の検証](https://github.com/ktnyt/Loupe/blob/b7b917850eb45aa85dfe4352151071a89c61cc75/docs/design/burst-gap-millis/README.md) |
| `camera-benchmark`、`camera-pilot-wifi`、`camera-pilot-paired-wifi` | 実際のカメラへのWi-Fi経路、検査用の写真、ペア接続が必要。[ペア接続の契約](camera-paired-connection.md) |
| `camera-pilot-qr-status`、`camera-pilot-ble-advertisement`、`camera-pilot-ble-saved` | 実カメラの登録状態、BLE通知、無線接続条件を指定。合成QRやペアセッションの検査とは区別する。[BLE起動の契約](camera-ble-wake.md) |
| `camera-pilot-sd`、`camera-pilot-sd-source-diagnostic`、`camera-pilot-cleanup` | 参照元のSD、必要な読み取り許可、承認済みfixture、対応する既存試行の記録が必要。[カメラ取り込みの契約](camera-import-contract.md) |
| `gallery-screen-talkback`、`dashboard-talkback`、`ranking-result-talkback`、`talkback-status` | TalkBackを使用する実機手順。通常のラベル・状態の計測とは区別し、フォーカス・音声・ジェスチャーを確認する。[TalkBackの検査手順](development-workflow.md#talkbackを一時的に使う検証) |
| `amazon-retirement-seed`、`amazon-retirement-verify`、`amazon-retirement-viewer`、`amazon-retirement-controls` | ネットワークを無効にした、使い捨ての専用Debug AVDで `-e disposable-avd true` を指定する。seedは旧APK上で `-e consent absent`、`active`、`contradictory` のいずれかを明示し、各条件で別の空のAVDを使う。同じ署名の候補APKへデータを保持して更新し、保存済み履歴・暗号化された合成設定・容量整理への同意・旧タスクを照合する。viewerとcontrolsは合成素材による表示と操作を確認する。外部サービスへ接続せず、AVDは検査後もオフラインのまま停止する。[旧操作の廃止 #254](https://github.com/ktnyt/Loupe/issues/254) |
| `e2-archive`、`e2-background`、`e2-large-original`、`e2-mixed-{backup,consent,restore}`、`e2-catalog-{publish,republish,restore,previous}` | 認証情報と隔離されたバージョニング有効の検査用バケットが必要。復元専用モードは発行したカタログまたは元の検査IDにも依存する。[e2接続試行](e2-android-pilot.md)、[実運用契約](e2-production-contract.md) |
| `e2-large-cache`、`e2-cleanup-boundary`、`e2-cleanup-product-{seed,sync,offline,restore,stop}`、`e2-cleanup-shared-{seed,denied,allowed,live,preview}` | `e2verify` や `e2fixture` の別APK、空のライブラリ、権限とユーザーの切り替え、各試行固有のIDが必要。サービスとの照合を含むモードには隔離バケットも用意する。[e2の実運用移行 #117](https://github.com/ktnyt/Loupe/issues/117) |
| `photo-storage`、`photo-storage-{archive,archive-snapshot,archive-ui,inventory,inventory-ui,performance,ui,ui-run,ui-run-skipped}`、`photo-storage-{detach,abandon-detach}-{seed,absent,restored}`、`photo-storage-saf-{seed,verify}` | 書き込み可能な副ストレージを持つ専用エミュレータまたは検査用カードを使う。付け外し・SAF権限・別アプリ所有・移動性能はトリガーと順序をそろえる。[保存先と移動の検証](photo-storage-contract.md#検証)、[移動時間の計測](diagnostics.md#取り込み済み写真の保存先移動issue-162) |
| `RawEditInstrumentation` の `real`、`viewportUri`、個別の編集UIと開発用キャプチャ | 実素材を使う `real` または `viewportUri` には承認済みのRAWが必要。画面・入力状態まで確認する専用検査は、対象の実験APKと計測APKを用い、目的に応じた引数と画面条件を指定する。[RAW実験アプリの検証](cnt-look-poc.md#開発と確認)、[原寸表示と保存状態](raw-full-resolution-contract.md) |

本体の専用モードは、対象の入力を準備したあと、該当手順で `adb -s SERIAL shell am instrument -w -e mode MODE jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation` に必要な引数を渡す。端末の選定、実施記録、利用者データの取り扱いは[開発手順](development-workflow.md)に従う。
