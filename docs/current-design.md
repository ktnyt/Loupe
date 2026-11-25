# Loupeの目的と現行設計

現行のアプリ構成と機能の入口を記す。UIの方針は[Material 3 UI契約](material3-ui.md)、開発環境は[README](../README.md#ビルド)、設計の経緯は[設計履歴](design.md)を参照する。`docs/design/`のHTMLや画像と、この文書の2026年10月25日の検証結果は、当時の状態を記録したもの。

## アプリの構成

通常配布する `app` は `jp.nano.rawgallery`。写真・動画の閲覧と選抜、カメラ取り込み、共有、RAWとNano Bananaの編集、クラウド保管、処理状況を提供する。`raw-experiment` は別のapplicationIdでRAW編集を検証する。共用するJavaを絞って取り込み、同じネイティブ実装をビルドする。

```mermaid
flowchart LR
  User[利用者] --> Library[ライブラリ・選抜・タグ]
  User --> Import[カメラ取り込み]
  User --> Monitor[処理の状況・診断]
  Library --> Index[端末の閲覧索引と保管記録]
  Library --> Video[動画再生]
  Library --> Share[共有用コピー]
  Library --> Raw[RAW編集]
  Library --> Nano[Nano Banana]
  Library --> Backup[保管・復元・容量整理]
  Camera[USBカメラ・SDカード・対応Wi-Fiカメラ] --> Import
  Import --> Target[PhotoStorageで書き込み先を選択]
  Import --> History[HistoryDb・CameraImportLedgerに記録]
  Import --> Media[端末のオリジナルと新規出力]
  Media --> Index
  Index --> Video
  Raw --> Media
  Nano --> Media
  Backup <--> Media
  Backup <--> E2[IDrive e2 東京]
  Nano --> E2
  Nano <--> AI[NanoBananaAPI.ai]
  Backup --> Monitor
  Index --> Monitor
```

| 利用する機能 | 実装の入口と状態 | 詳細 |
| --- | --- | --- |
| ライブラリ・再生・選抜 | `MainActivity` が `PhotoRepository` と `GalleryIndex` で写真・動画の一覧を開く。`VideoActivity` が動画を再生し、順位とタグはそれぞれ `RankingStore` と `TagStore` が保存する | [閲覧の契約](#p1-写真を探して確認する)・[タグ](tag-confidence-contract.md) |
| カメラ取り込み | `CameraImportActivity` と取り込み処理がUSB・SD・対応Wi-Fiの入力を扱う。`PhotoStorage` が書き込み先のMediaStoreボリュームを選ぶ。カメラ設定と従来形式の履歴を `HistoryDb`、内容照合済みの取り込み記録と公開の復旧情報を `CameraImportLedger` が保存する | [取り込みの入口](camera-quick-import.md)・[保存先と履歴](photo-storage-contract.md) |
| 共有とRAW編集 | `SharePreparation` は共有コピーを用意する。`RawEditActivity` / `RawEditFullSession` はLibRaw・EGLなどで処理し、調整したJPEGを別ファイルとして保存する | [共有](#p4-指定形式で外部アプリへ渡す)・[原寸RAW現像](raw-full-resolution-contract.md) |
| IDrive e2保管・復元 | `E2SyncActivity` と `E2SyncJob` が写真・動画のバックアップと復元一覧を扱う。`ArchiveStore` の保管記録を索引とは別に持ち、容量整理は照合済みの条件で実行する | [IDrive e2の運用](e2-production-contract.md) |
| Nano Banana | `NanoBananaSettingsActivity` で認証情報とプロンプトを設定する。`NanoBananaService` はe2に置いた限定の送信用画像で外部の生成を依頼し、検査した結果を別JPEGへ保存する | [Nano Bananaの設定と後処理](nano-banana.md) |
| 処理の状況・診断 | `DashboardActivity` が保存済みの処理状態を表示する。診断はアプリ専用領域に記録し、利用者の保存操作で許可範囲の項目を端末内のZIPへ出す | [処理状況](background-dashboard.md)・[診断記録](diagnostics.md) |

## 文脈と最上位の目的

個人のAndroid端末に写真・動画を取り込み、見返し、連写から好みの写真を選び、編集結果や選んだ写真を使う。オリジナルを保管し、端末容量を整理した後も利用できるようにする。

ライブラリのRAW表示には埋め込みJPEGを使い、RAW編集では現像結果を別ファイルへ保存する。クラウド保管・復元の現行e2経路は[運用契約](e2-production-contract.md)に定める。以下の目的図とP1〜P6は、閲覧・選抜・共有・保管に関する2026年10月25日からの整理を引き継いでいる。

```mermaid
flowchart TD
  P[写真を見返し、選び、使い続ける] --> V[写真を探して確認する]
  P --> R[連写から好みの写真を決める]
  P --> S[意図した写真を共有する]
  P --> C[オリジナルを保ちながら端末容量を空ける]
  V --> I[完成済みの一覧をすぐ表示する]
  V --> D[写真の内容を確認する]
  R --> B[比較する連写の範囲を定める]
  R --> O[比較結果を保存して再開する]
  S --> T[今回渡す写真を確定する]
  S --> E[指定形式で外部アプリへ渡す]
  C --> A[オリジナルの保管と復元を確認する]
  C --> F[安全条件を満たす端末オリジナルだけ整理する]
  T -. 確定した順位を参照 .-> O
  E -. オリジナル取得を参照 .-> A
  D -. クラウド写真のオリジナル取得を参照 .-> A
  F -. 復元照合済みの記録を参照 .-> A
```

実線は親の条件を具体化する関係、点線は他の目的の保証への依存。SQLite、ハッシュ、キャッシュ、Androidジョブは実現手段であり、その名前をそのまま利用者の目的にしない。

## P1: 写真を探して確認する

- 機能: アルバム・機種から写真を探し、一覧、拡大、前後移動で内容を確認する。
- 要件: 全件走査を毎回待たず保存済み一覧を開ける。追加写真を裏で反映する。RAWは埋め込みJPEG、JPEGは画像そのものを表示する。オリジナル取得に失敗しても取得済みプレビューを残す。
- 根拠・状態: `design.md`の閲覧、プレビュー、v0.11索引は既存設計。300msの更新要求は作業ツリーのREADMEに記録された現行仕様であり、新たな反映時間保証ではない。

### P1.1 完成済みの一覧をすぐ表示する

- 不変条件: 索引は閲覧用であり、同期済み判定や削除許可に用いない。更新中は直前に完成した索引を読む。
- 事前条件: 写真アクセス権がある範囲を走査する。初回は索引作成が必要。
- 事後条件: 写真・アルバム・連写所属・更新状態を一つのDBトランザクションで置換する。失敗・キャンセルでは途中の索引を公開しない。
- 一覧の背景反映: 反映直前に先頭可視行の写真と行上端を保持し、更新後は同じ写真を含む束の行へ復元する。対象が消えた場合は元のセル位置を新しい範囲内へ丸める。ヘッダーの途中表示は維持し、初回の空一覧だけ保存済み位置を使う。`GalleryPosition`と`ScrollingGrid`が担当。オリジナル・順位・タグ・選択は変更しない。
- 更新契約: 画面の変更通知は最初の通知から300ms後に要求する。更新中の追加要求は次回へまとめ、強制要求を優先する。これは表示完了までの300ms保証ではない。定期更新もAndroidの実行制御を受ける。
- 実装: [GalleryIndex.java](../app/src/main/java/jp/nano/rawgallery/GalleryIndex.java) `load/rebuild/signature`、[IndexRefreshRequests.java](../app/src/main/java/jp/nano/rawgallery/IndexRefreshRequests.java) `start/finish`、[MainActivity.java](../app/src/main/java/jp/nano/rawgallery/MainActivity.java) `mediaObserver/refreshIndex`、[GalleryIndexJob.java](../app/src/main/java/jp/nano/rawgallery/GalleryIndexJob.java)。主な位置はそれぞれ85、117、154行、8行、64・653行。
- 検証: 更新要求の単体回帰テスト成功。DBのロールバック、実端末での通知、外部ストレージは今回未検査。READMEの約2.5秒という実測は小さな検証JPEGに限る過去記録。

### P1.2 写真の内容を確認する

- 不変条件: 閲覧でオリジナルを変更しない。RAWは全体探索で構造を検証したJPEG候補のうち最大画素数を選ぶ（詳細・既知の先頭補正は[RAW抽出契約](raw-extraction.md)）。向きを一度だけ適用する。抽出失敗を別の現像結果で隠さない。
- 事前条件: オリジナルまたは保存済みプレビューへアクセスできること。クラウドオリジナルの取得はP5に依存する。
- 事後条件: 先に小サイズプレビューを表示し、オリジナル取得成功後に高画質表示へ移る。取得失敗ではプレビューと失敗表示を残す。
- 実装: [RawPreview.java](../app/src/main/java/jp/nano/rawgallery/RawPreview.java) `inspect/bytes/candidate`（77・115・261行）、[PhotoRepository.java](../app/src/main/java/jp/nano/rawgallery/PhotoRepository.java) `scan/sourcePreview/fullPreview/bitmap`、[MainActivity.java](../app/src/main/java/jp/nano/rawgallery/MainActivity.java) `showPhoto`（823行）、`ZoomImageView`。
- 検証: 抽出方式とエラー経路を読解。実写真の抽出一致、回転、拡大操作は今回未検査。READMEにある8枚の一致は過去の検証結果であり全RAW形式の保証ではない。

## P2: 連写から好みの写真を決める

- 機能: 連写をまとめ、2枚ずつ比較し、全写真の順位と同順位を決める。
- 要件: 比較範囲が明確で、途中保存・再開・一手戻しができる。判断はオリジナルの削除や移動を起こさない。
- 根拠・状態: 連写と保存は`design.md`。同順位はREADME v0.14と現行コードによる後続仕様。旧「同順位は扱わない」は現行契約ではない。
- 上位との対応: P1の見やすさとは別に、写真を選ぶための比較範囲と判断結果を保証する。

### P2.1 比較する範囲を定める

- 不変条件: 機種・保存先・RAW/JPEG区分が同じ写真について、対応する同じ連写モードの番号が隣接して+1なら時間差より優先してまとめる。番号の戻り/重複/飛び/モード差と番号あり/なしの境界は分け、番号なし同士だけ隣接時刻差が閾値以内ならまとめる。不明な撮影時刻・機種は単独扱い。追加日時や更新日時で連写を推測しない。
- 仕様: 無効・1・2・3秒とカスタム1〜2147483647ミリ秒、初期値1000ミリ秒。入力・保存の契約は[連写候補の撮影間隔](burst-gap-millis.md)、抽出・番号優先・cache移行は[連写番号優先](burst-number-priority.md)を参照する。無効は番号ありも単独にする。現行のローカルJPEG/RAWメタデータ抽出は小数秒を読み取らず、番号なしの当該経路は秒精度に制限される。番号優先では閾値を超える場合もあり、時間差fallbackも隣接差の連鎖なので束全体の幅は閾値を超え得る。連写内番号は一意な連写IDの保証ではない。
- 事後条件: 元の一覧順に代表を配置し、オリジナル、同期、整理の単位は1枚のまま保つ。
- 実装: [BurstGroups.java](../app/src/main/java/jp/nano/rawgallery/BurstGroups.java) `group`、`GalleryIndex.rebuild`が機種・保存先・形式のstreamを作る。
- 検証: 番号優先の実装・単体/Android・実機・独立レビューの最新結果はissue #188へ記録する。実ORFの番号記録の調査はissue #189を参照する。

### P2.2 比較結果を保存して再開する

- 不変条件: 対象は開いた束の写真集合に限定する。集合が変われば別記録。判断結果と今回共有する選択状態は別に持つ。
- 事前条件: 写真IDが空でなく重複しない。未完了の比較で両方のプレビューが表示できた後に選択する。
- 選択回数: 比較中の各画像に、現在の判断履歴でその画像が選ばれた回数を表示する。初期0回、A/B選択で選ばれた画像だけ1加算し、同順位では加算しない。undo・再開・再ソートは現在の履歴に追従する。推移的な勝利や同順位の他メンバーへは加算しない。既存の1位指定・同率追加は履歴を自動生成するため、その結果をundoした場合の回数には生成された判断も含む。実際の過去のタップ回数を記録する監査ログではない。写真上のA/Bバッジと選択ボタン下へ表示し、細い写真ではバッジをA/Bのみへ縮退する。読み上げは画像名とボタンの状態説明に回数を含める。
- 個別指定: 確定後の非1位写真を既存1位へ追加できる。残る順位グループを保持し、同率人数に応じて順位を再計算する。単独1位への置換も可能。確認・保存・失敗時の契約は[連写の同率1位指定](burst-co-winners.md)を参照。
- 一括指定: 連写内の選択画面から明示選択した写真をまとめて1位へ追加する。共有設定とは独立し、確定済みの1位と下位グループ順を保持する。[契約](burst-bulk-winners.md)を参照。
- 事後条件: 選択・同順位・一手戻しで次の履歴を作り、永続保存成功後に画面の順位状態を進める。保存失敗なら次の比較に進まない。同順位は1位・1位・3位のように表示する。
- 実装: [PhotoRanking.java](../app/src/main/java/jp/nano/rawgallery/PhotoRanking.java) コンストラクタ・`choose/tie/undo/best/rankOf`、[MainActivity.java](../app/src/main/java/jp/nano/rawgallery/MainActivity.java) `rankingFor/openRanking/saveRanking/rankingCandidate`、[RankingStore.java](../app/src/main/java/jp/nano/rawgallery/RankingStore.java) `load/save`、`ComparisonLayout`。順位のキー生成・読込・commitはRankingStoreへ分離済み。
- 検証: 順位100順列、再開・undo・不正入力、比較レイアウトの単体検査成功。RankingStoreの保存失敗の戻り値・既存キーとの互換性はホストテストで検査済み。Androidのディスク書込み失敗と実画面の操作は未検査。

## P3: 今回渡す写真を確定する

- 機能: 写真や連写枠を選び、選択済み写真から共有対象を決める。
- 要件: 順位の保存を変えずに共有範囲を変えられる。未選択写真を勝手に追加しない。未確定の連写を確定済みと扱わない。
- 根拠・状態: README v0.14の後続仕様を整理。既存設計文書には不足していた契約。
- 不変条件: `対象 ⊆ 明示選択した写真`。ベスト限定なら対象はその中の確定1位すべてと単写真。同率1位を1枚へ勝手に絞らない。対象IDの重複を除く。
- 事前条件: 選択する枠と、その枠が含む写真が特定できること。選択開始時にベスト限定をオンにする。
- 事後条件: ベスト限定オフなら選択した全写真。オンで未確定の束があれば、その束を未確定として返し、比較へ進む・選択へ戻る・確定分だけ共有する選択肢を示す。枠数と実対象枚数を区別する。
- 参照: P2の確定順位を読む。P4へ渡す直前に対象リストを固定する。
- 実装: [BestShotSelection.java](../app/src/main/java/jp/nano/rawgallery/BestShotSelection.java) `resolve`（11行）、[MainActivity.java](../app/src/main/java/jp/nano/rawgallery/MainActivity.java) `resolveSelection/shareSelection/shareMenu`（245・338・1170行）。
- 検証: 同率、単写真、未確定、部分選択、限定オフ、空選択の単体検査成功。比較から選択へ戻る画面遷移は今回未検査。

## P4: 指定形式で外部アプリへ渡す

- 機能: 共有先と順序・形式を保存し、1枚または複数枚を共有する。
- 要件: 明示操作時だけ渡し、元の品質と向きを保つ。準備失敗で一部だけを渡さない。受信アプリへオリジナルの書き込み権限を渡さない。
- 根拠・状態: `design.md`の共有契約に、README v0.14の複数共有を統合した既存仕様。
- 不変条件: オリジナル形式ではバイト列を変更しない。JPEGオリジナルはそのまま、RAWのJPEG共有はオリジナル内JPEGの画素を再圧縮せず向き情報を付ける。小サイズWebPで代用しない。
- 事前条件: P3で固定した対象の全オリジナルを準備でき、共有先が形式・複数共有を受け付けること。
- 事後条件: 全ファイルの準備成功後にのみ共有Intentを起動する。準備失敗では生成途中の共有コピーを取り除き、共有しない。権限は専用領域の対象URIに対する読み取りのみ。
- 成功境界: Loupeが確認できるのは共有先の起動まで。外部アプリでの保存・投稿完了は別であり、成功扱いに含めない。
- 実装: [MainActivity.java](../app/src/main/java/jp/nano/rawgallery/MainActivity.java) `export`、[SharePreparation.java](../app/src/main/java/jp/nano/rawgallery/SharePreparation.java) `prepare/writeCopy`、[ShareProvider.java](../app/src/main/java/jp/nano/rawgallery/ShareProvider.java) `file/openFile`（16・27行）、`ShareTargets`、[AndroidManifest.xml](../app/src/main/AndroidManifest.xml)。オリジナル取得はP5。
- 検証: 全件準備後のIntent起動と読み取り制約を読解。共有準備の順序・形式・容量・途中失敗・中断をホストの一時ファイルで検査済み。オリジナル取得・EXIF処理は既存実装を移動し、外部アプリへの受け渡し、失敗時の実画面、複数形式の互換性は未検査。

## P5: オリジナルの保管と復元を確認する

- 機能: Amazon Photosに同じオリジナルがあるか検索し、必要なら保存し、内容を検証して復元する。
- 要件: HTTP成功だけで保管完了としない。結果不明時の重複送信を避ける。認証情報を意図しない転送先・成果物へ出さない。
- 根拠・状態: `design.md`の追加検証・自動同期・オリジナル取得の既存契約。初回試作の「接続診断のみ」は現在の機能範囲を表さない。
- 不変条件: 検証済みは再取得したオリジナルのサイズ・SHA-256一致を意味する。MD5は候補検索に用い、内容保証を置き換えない。クラウドオリジナルを削除しない。
- 事前条件: 利用者のログインとオリジナルの読み取りが必要。自動同期は対象確認後に有効化し、初期値は無効。現行の保存・取得上限は1枚512 MiB。
- 事後条件: 送信前に`pending`を永続記録する。結果不明は検索と復元照合だけを再試行する。復元と永続プレビュー保存後に`verified`を記録する。オリジナル取得時は内容確認済みの一時ファイルを完成ファイルへ置換する。
- 失敗・再試行: 認証失敗は自動同期を停止。個別保留には再試行待ちを置く。定期バッチは最大20枚・時間制限付き。これは全写真の保管完了時刻を保証しない。
- 参照: P1・P4へ取得済みオリジナルを供給し、P6へ照合済み記録を供給する。DB記録、プレビューファイル、クラウド保存は単一トランザクションではない。
- 実装: [CloudVerifier.java](../app/src/main/java/jp/nano/rawgallery/CloudVerifier.java) `verify/uploadAndVerify/download`（126付近・208・314行）、[ArchiveStore.java](../app/src/main/java/jp/nano/rawgallery/ArchiveStore.java) `pending/verified`（144・148行）、[OriginalCache.java](../app/src/main/java/jp/nano/rawgallery/OriginalCache.java) `obtain`（16行）、`SyncEngine.runBatch`、`CloudActivity`、`SyncJob`。
- 検証: 送信前記録・復元照合・キャッシュのハッシュ再検査を読解。今回ネットワーク接続、認証切れ、プロセス終了からの回復は未検査。READMEのアップロード等の成功は過去の特定ファイルの実測に限る。

## P6: 安全条件を満たす端末オリジナルだけ整理する

- 機能: 保管済み写真の端末オリジナルを解放し、プレビューとクラウドオリジナルで引き続き利用できるようにする。
- 要件: 未同期・照合失敗・プレビュー未保存・権限不足のオリジナルは削除しない。同期の有効化と整理の有効化を分ける。
- 根拠・状態: `design.md`の自動同期・JPEG追加仕様。初回試作の「削除なし」「保持期間未決定」は後続仕様で置き換わっている。現行初期値は7日。
- 不変条件: 対象は対応機種のRAWとアクセス可能なJPEGのうち、管理対象MediaStoreオリジナル。オリジナルを読み取れないものは残す。クラウド削除はしない。
- 事前条件: 同期・整理がともに有効、Androidの整理権限がある、期間条件を満たす、直前の復元照合・永続プレビュー保存に成功し、端末オリジナルのメタデータとSHA-256が一致すること。
- 期間仕様: RAWは撮影日不明なら残す。JPEGは撮影日→追加日→取得可能な作成日。すべて不明なら照合・保存後に対象となる。未来の上位日付を下位日付へ置き換えない。変更日時を作成日の代わりにしない。
- 事後条件: `releasing`を保存してから端末の1オリジナルを削除し、削除件数1を確認後に`released`を保存する。削除後も一覧・閲覧・共有に必要な永続プレビューと記録を残す。
- 失敗境界: DBとMediaStore削除は同時確定しない。削除後に記録更新が失敗すると`releasing`が残り得る。現行走査はSHAを持つ保存記録をクラウド写真として復元するが、この途中失敗の端末試験は未実施。ファイルの並行変更を完全に排除する原子的な削除契約も確認できていない。
- 実装: [SyncPolicy.java](../app/src/main/java/jp/nano/rawgallery/SyncPolicy.java) `retentionDate/eligible`（22・28行）、[SyncEngine.java](../app/src/main/java/jp/nano/rawgallery/SyncEngine.java) `runBatch/unchanged`（89・205付近）、`ArchiveStore.verified/preview`、`PhotoRepository.scan`（164行付近）、`SyncActivity`。
- 検証: 保持期間・機種・撮影日44検査成功。削除経路は読解のみ。オリジナル削除、長時間バックグラウンド動作、途中停止からの復帰は今回実行していない。

## 状態と保存の責務

| 状態 | 責務・保証 | 現行の担当 | 他の状態との境界 |
| --- | --- | --- | --- |
| 比較結果 | 判断履歴から順位を再構成する | `PhotoRanking`、`RankingStore` | 共有選択やオリジナル削除を変更しない |
| 今回の選択 | 明示選択から今回の共有対象を決める | `selectedTiles`、`BestShotSelection` | 永続順位を読むだけ |
| 閲覧索引 | 完成した写真・アルバム・束を表示する | `GalleryIndex` | 削除の根拠にしない |
| 保管記録 | オリジナルと照合結果・復元先を保持する | `ArchiveStore` | 索引と別DB、削除と非原子的 |
| 永続プレビュー | 端末オリジナルを解放した後も閲覧可能にする | files/archive、`CompactPreview` | 消去可能キャッシュとは別 |
| オリジナルキャッシュ | 必要時の取得を再利用する | `OriginalCache` | 512 MiB、次回取得時に期限・容量整理 |
| 閲覧キャッシュ | 閲覧コストを減らす | `PhotoRepository` | 256 MiB、永続プレビューの代用ではない |
| 共有コピー | 外部アプリへ限定した読取権限で渡す | `SharePreparation`、`export`、`ShareProvider` | オリジナル・永続プレビューと別領域 |

`ArchiveStore.key`はフォルダ・名前・サイズ・更新日時から生成される識別子であり、内容ハッシュではない。共有選択の整数index、順位保存のID、クラウドオリジナルIDも同じ識別子ではない。名前変更や保存先変更を越えて順位を維持する保証は記録されておらず、変更するなら移行契約が必要。

## 旧記述と現行仕様の対応

| 履歴にある記述 | 現行での扱い | 根拠 |
| --- | --- | --- |
| 初回は接続診断のみ、削除しない | 初回試作限定。現在は保存・復元・任意の自動整理を持つ | design.md追加仕様、`SyncEngine` |
| 保持期間は未決定 | 現行初期値7日、設定範囲1〜365日 | README、`SyncPolicy` |
| 同順位は扱わない | v0.14で同順位を扱う | README v0.14、`PhotoRanking.tie` |
| オリジナル整理後のJPEG共有 | 小サイズWebPを共有せずオリジナルから用意する | design.mdオリジナル取得の追加仕様、`export` |
| 主要操作は上部メニュー | v0.12以降、下部操作とアイコンへ変更 | README v0.12〜v0.14、`GalleryUi` |
| 写真の変更通知をまとめる | 最初の通知から300ms、更新中の要求も引き継ぐ | 作業ツリーREADME、`IndexRefreshRequests` |

## 対応の判定と残る作業

「閉」は記載した範囲の対応を確認できたこと、「開」は不一致があること、「未検査」は必要な資料や検証が不足していること。アプリ全体の安全性や完成度を表す採点ではない。

| 対象 | 要件↔機能 | 契約↔要件 | 実装↔契約 |
| --- | --- | --- | --- |
| P1 閲覧 | 閉: 閲覧・起動待ち短縮に対応 | 閉: 索引とオリジナル取得の条件を分離 | 要求集約は閉（単体検査）、DB・表示・RAW実ファイルは未検査 |
| P2 比較 | 閉: 比較範囲と判断の保存に対応 | 閉: 同順位を後続仕様へ整合 | 計算・保存キー互換性・commit成否の伝達は閉（ホスト検査）、Androidの永続化・画面は未検査 |
| P3 対象決定 | 閉: 意図した共有範囲に対応 | 閉: 選抜と選択を分離 | 対象計算は閉（単体検査）、画面遷移は未検査 |
| P4 受け渡し | 閉: 品質と共有先指定に対応 | 閉: 全件準備と読取権限を定義 | 準備の全件成功・後片付けは閉（ホスト検査）、Android画像処理・外部アプリとの結合は未検査 |
| P5 オリジナル保管 | 閉: オリジナルを使い続ける条件に対応 | 通常成功・結果不明は閉、競合・途中停止の網羅性は未検査 | 保存・復元経路は読解済み、実サービスは未検査 |
| P6 容量整理 | 閉: オリジナル保全と容量解放に対応 | 通常の削除条件は閉、オリジナルの並行変更時は未検査 | 日付条件は閉（単体検査）、削除・途中失敗は未検査 |

今回見つかった明確な「開」は、旧設計文書の「同順位なし」と現行仕様の不一致、および複数選択・共有対象決定の契約が旧文書に欠けていた点。本書で後続仕様と対応を整理した。実装の不具合修正を行ったという意味ではない。

コード整理の採否:

1. 実施済み: `MainActivity`から共有準備を`SharePreparation`へ独立させた。`export`には画面起動以外に、形式決定、オリジナル取得、コピー、容量管理、失敗時の後片付けがある。P4の全件成功契約を一つの責務に集めるのが分割の理由。行数を減らすためだけに分割しない。
2. 実施済み: 比較履歴の保存を`RankingStore`へ分けた。純粋な`PhotoRanking`は既に存在するので、別の順位モデルを増やさず、保存成功後だけ進める境界を明文化する。
3. 未実施（今回の範囲外）: 削除を含む変更の前に、`verified → releasing → released`の途中停止と、端末オリジナルの並行変更を検証する。既存の照合・削除間の隙間をどこまで保証するかを決めてから契約・実装を変える。

既存のUI・クラウド方式・識別子・保存形式は今回変更しない。新たな抽象層の一律導入も要件とはしない。

## 2026年10月25日の検証記録

初回整理は `ab903e5` と当時の作業ツリーが対象だった。README、`GalleryIndex.java`、`MainActivity.java`の未コミット変更、および当時未追跡の `IndexRefreshRequests.java` とテストを照合した。その後、共有準備と順位保存を分離した。

### 共有準備・順位保存の分離契約（2026年10月25日、実装前）

今回の依頼でP4の共有準備とP2.2の保存をMainActivityから分離する。挙動・保存形式を変えない範囲の実装を委譲されたものとして扱う。

- `SharePreparation`: 固定済みの写真リストと共有形式を受け、今回の出力合計が512 MiB以下で全件完成したときだけ、順序付きファイル一覧とMIME型を返す。過去の共有コピーは24時間保持し、今回の合計に含めない。オリジナル取得・RAW内JPEG抽出・向き付与を行い、例外・中断・容量超過では今回分だけを削除して部分結果を返さない。オリジナルの一時取得は別途1枚512 MiB以下を必要とし、選択画面でもこの条件を確認する。既存キャッシュの期限整理と、オリジナル取得側のキャッシュ更新はロールバック対象ではない。詳細は[共有容量の契約](share-capacity-contract.md)を参照する。
- 共有先選択、URIへの読み取り権限、Intent構成、共有先の対応確認・起動、画面メッセージはMainActivityに残す。共有準備には外部アプリ起動や画面更新を持たせない。ファイル生成の依存だけを差し替えて、途中失敗を検査できる境界を設ける。
- `RankingStore`: 写真IDをソートし、従来どおり`v1-`とID列のSHA-256で保存キーを生成する。SharedPreferences名`rankings`、履歴文字列、同期`commit()`を維持する。読み込みは同じ集合なら同じ履歴を再現し、不正な履歴は黙って初期化しない。保存はcommitの成否を返し、失敗時に画面の比較状態を進めない。
- 順位読み込みは比較開始とベストショット判定の双方を同じ保存責務へ寄せる。写真からIDへの対応付けと画面更新はMainActivityに残す。SharedPreferencesのcommit失敗時のメモリ上の挙動を含め、従来のAndroid保存方式を変えない。
- 検証予定: 共有の全件成功・途中失敗・容量・中断、既存キーと履歴の互換性・保存失敗のテスト。既存回帰テスト、アプリのビルドとlint。実機・外部アプリの検証結果とは分ける。

### 文書整理時の検証

2026年10月25日に現行作業ツリーで実行:

- `sh tests/ranking.sh`: 順位100順列・再開/undo・不正入力、比較配置、ベストショット対象決定が成功。
- `sh tests/index-refresh.sh`: 更新要求の集約・強制更新優先・失敗後の保留引継ぎが成功。
- `SyncPolicyTest`: 機種・保持期間・撮影日44検査成功。
- `BurstGroupsTest`: 8ケース成功。

後二者は`SyncPolicy.java`、`RawPreview.java`、`BurstGroups.java`とテストを一時ディレクトリへ`javac`でコンパイルして実行した。アプリ全体のビルド・lint・実機操作・外部サービス接続・削除は今回の文書整理では再実施していない。過去の実機記録はREADMEに残し、今回のテスト結果と混同しない。


### 分離後の検証（2026年10月25日）

- `sh tests/preparation-storage.sh`: 既存のv1キー・履歴読込み、同順位、集合順の変更、集合の分離、undo、commit失敗の伝達、不正履歴を上書きしないことを確認。共有準備は順序・拡張子・MIME型、途中失敗時の全コピー削除と既存コピー保全、容量超過、期限整理、中断時の後片付けを確認。
- `sh tests/ranking.sh`、`sh tests/index-refresh.sh`、`sh tests/cloud-policy.sh`: 成功（順位100順列など、索引要求、クラウドURL制限22検査）。
- `./gradlew assembleDebug lintDebug`: 成功。lintエラー0件・警告27件。移動した従来の`android.media.ExifInterface`にもAndroidX版を推奨する警告がある。今回は画像処理方式と依存関係を変えていない。
- `git diff --check`: 成功。

SDKを使う検証では`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`を指定。新規ホストテストのSharedPreferencesは代替実装、共有元のコピー処理は注入した代替処理であり、Androidでのディスク永続化、実際のオリジナルコピー・RAW抽出・EXIF付与、Intent起動を実測した結果ではない。画面のcommit失敗分岐と既存コピー処理の移動はコードの差分で確認した。実機インストール、クラウド通信、外部アプリへの送信、オリジナル削除は行っていない。

### RAW抽出拡張（2026-10-25）

一覧・オリジナルキャッシュ・共有に51拡張子を接続。30拡張子34実ファイルを検査し21ファイルのJPEG抽出と参照照合に成功。詳細、JPEG未検出の13ファイル、未検証の形式・実機範囲は[RAW抽出の検証](raw-extraction.md)を参照。前述のRAW実ファイル未検査という記録を、この追加検証の範囲で更新する。

## その他メニューの初期表示

- 目的: 操作先が途中で移動せず、その他メニューを安定して操作できる。
- 要件・契約: メニューはMaterial 3のBottomSheetDialogを使い、実際のシート本体を利用可能幅（最大640dp）へ収める。項目一覧の可視高さ48%上限とスクロール、既存の項目・ラベル・戻る・外側タップ・読み上げを保つ。入力はMaterialAlertDialogとTextInputLayoutで表示し、IMEで操作を隠さない。オリジナル・順位・タグ・選択を変更しない。
- 実装: MaterialUi.sheetでnative surfaceの最大幅と展開状態を指定する。背景・角丸・scrim・入退場アニメーションはMaterial 3に従う。共通SheetDiagnosticsがscrimを除くsurfaceの初回描画/フレーム提出を記録し、呼出元のonShowを置き換えない。
- 参照: [Material 3 UI契約](material3-ui.md)。Issue #13の旧24dp外余白・20dp角丸・固定背景・無アニメーションとdecorの完全静止検査は、Material 3移行で置き換える。過去の確認結果はissue #13とGit履歴に残す。
- 検証: 現在の版の実機記録はissue #92を参照。旧0.17.9での固定Dialog検証をMaterial 3版の動作保証として扱わない。

### 操作の手がかり（Issue #14）

結果一覧は説明文に代えてカードと拡大印を使う。順位・ファイル名・順番を保持し、長い名前は折り返す。各行は単一の操作対象で、読み上げ名は順位と名前、クリック操作名は「拡大」。写真の読込失敗でも行の操作を失わず、拡大から標準Backで結果へ戻る。

Cloud初期画面は説明や空statusを表示しない。ログイン・同期の設定・接続と確認から明示的に操作する。実処理の状態メッセージはすべて可視にしpoliteで通知する。通信・オリジナル・認証・削除条件を変更しない。同期の対象条件、共有の形式と失敗復帰、タグの空状態と入力エラー、安全確認、読み上げ用説明は残す。#1の推論と#17の状態表現は対象外。

### 背景処理の観測状態（Issue #36）

SyncEngineは既存の文章/時刻と同じSharedPreferences editでsyncStateCode/syncStateAtを保存する。1バッチ終了はWAITING、保留があるとPARTIALとし、全写真COMPLETEを推測しない。全体総数は未計測。

PreviewPreparationJobは既存のready/total/failed/stateと同じeditでpreparationStateCode/preparationStateAt/preparationCountsKnownを保存する。総数の走査完了後だけ件数を既知とする。readyは準備済みpreview数、failedは今回の処理失敗数で、ThumbHashの失敗はreadyと重なり得るため合計で残数を算出しない。ThumbHash単独件数は未計測。

JobState.snapshot相当の読取では旧データ・不明コード・時刻欠落はUNKNOWN。保存されたRUNNINGでも実行主体がない場合はUNKNOWNとし、過去の計測件数と更新時刻は保持する。状態保存は既存同様applyによるためプロセス停止時のdisk永続化を保証しない。UI、認証、送信、削除、タグ推論、jobの実行条件は変更しない。

## 状態表示（Issue #17）

可視の状態文を形と数値へ統一し、操作名・機能見出し・写真メタデータ・安全確認を保持する。実装前契約と全対象は[状態表示](visual-status.md)を正とする。明示した状態と原因を描画し、JobStateの未知・未計測を成功や0件と扱わない。読み上げと明示的な復帰操作を保持する。実装/実機検証は進行に応じて別記する。

## 処理状況ダッシュボード

「処理の状況」を開くと、`DashboardActivity` が保存済みの観測値を表示する。未計測値は「—」とし、工程ごとの結果、共通の準備ジョブ、前面での実行抑制を区別する。診断の記録・ZIP保存もここから操作できる。 [観測契約](background-dashboard.md) [診断記録](diagnostics.md) [初期実装 #39](https://github.com/ktnyt/Loupe/issues/39)
