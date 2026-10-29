# 診断ログ（Issue #15）

## 目的と契約

Issue #3の「5〜6枚でも数秒待つ」現象を原因から調査できるようにする。
高速化は行わず、5990ae7をrevertした従来のFIFO・2ワーカー・サムネイル先行順を維持する。
実装前の契約はIssue #15本文。ログ導入の完了をIssue #3の解決と扱わない。

デバッグ可能なAPKのみ自動で記録する。releaseでは記録しない。
時刻はelapsedRealtimeNanos（単調増加）。session行のwall_msはJST等への換算用で、経過時間の計算には使わない。
session、seq、request、parent、threadで関連を追う。イベント書込み順は複数スレッドで前後するので解析時にnsで並べる。
写真はセッションごとの匿名ID。写真名・URI・パス・入力文字・例外メッセージ・認証情報は記録しない。

## 記録する境界

| 範囲 | イベント |
| --- | --- |
| 全4 Activity | create/resume/pause/destroy、touchの全actionとdispatch終了、key（文字キーの値は除外）、戻る |
| 共通ボタンとSheet | action.button、dialog.show/input（ボタン名や入力内容は記録しない） |
| 主画面 | 一覧・アルバム・連写・拡大・順位・共有等の画面構築、grid選択、burst.open/select |
| 非同期処理 | Mainのscans/images/actions、タグworker/analysis、Cloud/Sync、GalleryIndex、SyncJobのenqueue/start/end/fail/cancel |
| 連写hero/strip | image.request（burst ID・匿名写真ID・形式・要求サイズ）、memory hit/miss、post、bind、fail、failure_applied、discard |
| 全repository画像処理 | preview/sourcePreviewの呼出・同期ロック待ち/獲得・終了/失敗、compact/sourceキャッシュ有無、RAW inspect/extract、JPEG source、縮小、trim、decode |
| 連写hero描画 | image.draw、image.frame_commit（描画コマンドがGPUに提出されたフレーム）、取得不能時frame_unavailable |

`burst.open → 最初のheroのframe_commit`を初回表示の指標とする。
**frame_commitはディスプレイ走査完了ではない**。視覚的な初回表示との照合が必要なら画面録画等を併用する。bind、drawだけで実際の画面表示完了と断定しない。
後続の前後移動はimage.requestから測り、元のburst.openからの経過を初回表示として数えない。
repositoryのlock待ちは、呼出を記録してから従来と同じsynchronizedブロックへ入るまで。
cacheのhitはファイル対の存在を表す。破損して再生成された場合は後続のsource/compactイベントで判別する。

## 保持と回収

アプリ内部の`files/diagnostics/events.jsonl`と`previous.jsonl`の2ファイル、各最大2MiB。
書込み待ちは最大2048行。I/Oは専用スレッドで行い、満杯時は操作を止めず破棄し、後続行のdroppedに累計を記録する。
各行をflushし、アイドル時も250ms以内に処理を継続する。強制終了直前のキューは失われ得る。
保存失敗はlogcatのLoupeDiagnosticにwriter_failedを出し、保存を停止する。

```sh
# 操作直後、プロセスを止める前に回収。画像やDBは取得しない。
python3 scripts/collect-diagnostics.py --output /tmp/loupe-diagnostics
python3 scripts/analyze-diagnostics.py /tmp/loupe-diagnostics/*.jsonl
```

複数端末がある場合はcollectに`--serial`。ファイルが流れた場合は再現し直す。
回収先は実行ごとに新しいディレクトリにする。JSONLとwriter-status.txtを確認し、droppedや破損行がある区間を完全な計測として使わない。
ローテーションで消えた開始イベント、強制終了直前、別プロセス、取得不能な描画は推測で補わない。

## 画面取得による計測への影響を避ける

画面は端末の共有領域へ保存せず、Macへ直接取得する。

```sh
/opt/homebrew/share/android-commandlinetools/platform-tools/adb exec-out screencap -p > /tmp/loupe-screen.png
```

`adb shell screencap -p /sdcard/...` は画像追加の通知を発生させ、一覧更新・処理負荷・操作位置を変える場合がある。スクロールが静止してから操作対象を確認し、取得後の画面更新がないことと、診断ログの枚数・対象が予定と一致することを確認する。録画も計測負荷を含むため、撮影の有無を条件に記す。写真入りキャプチャはローカルに留める。

## 範囲と限界

「全て」はアプリの入力と処理境界を追えることを目的とし、Android/OS/ネットワーク/WebView内部の全イベントや、毎行の実行トレースではない。
指の移動も記録するが座標・入力文字は保持しない。IME内部や他アプリの共有先の操作は対象外。
非同期タスク内部で独自に例外を握り潰す場合、task.endだけでは成功を意味しない。個別のimage/repositoryの結果を読む。
shutdownNowで未実行のキューが除去された場合やクラッシュ時は終了が欠け得る。終了なしを成功扱いしない。
連写hero以外の画像はrepositoryまでのログであり、個別のframe_commitは今回の範囲外。
診断I/O・JSON生成による負荷はゼロではない。性能比較は同じログ設定で行う。絶対時間の無負荷保証はしていない。

## 再検証

```sh
mkdir -p /tmp/loupe-diagnostic-tests
javac -d /tmp/loupe-diagnostic-tests app/src/main/java/jp/nano/rawgallery/DiagnosticWriter.java tests/DiagnosticWriterTest.java
java -cp /tmp/loupe-diagnostic-tests jp.nano.rawgallery.DiagnosticWriterTest
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools gradlew -p . assembleDebug assembleDebugAndroidTest lintDebug
# APKとandroidTest APKをデータ保持で更新した後
/opt/homebrew/share/android-commandlinetools/platform-tools/adb shell am instrument -w -e mode diagnostics jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation
```

合成JPEGだけを使うテストでcache miss/hit、bind/draw/frame_commit、失敗、旧要求破棄、名前漏洩を確認する。写真原本や順位・タグの書換えはしない。

## 開発手順の見直し

2026-10-25、ホーム全体探索とキャッシュ後のms比較による不適切な改善報告を受け、共通AGENTS.mdとこのリポジトリの規約に限定探索・同条件検証を追加した。
追加負担は短い規約と必要時のログ回収のみ。次のIssue #3再調査で、不必要な探索や条件違いの報告が再発しないか確認する。現時点で将来の遵守効果は未確認。負担が効果を上回れば該当規約を短縮する。戻す場合は追加した節だけを外す。

## 2026-10-25 実測した検証結果

- 25128PNA1G / Android 16に0.17.4(29)をデータ保持で更新。Issue #3の優先キュー修正は撤回済み。
- Writerのローテーション・上限・再起動追記・過大行失敗、解析の時系列整列・欠損フレーム・再選択区別のテスト成功。
- clean assembleDebug / assembleDebugAndroidTest / lintDebug成功。
- 合成画像でキャッシュmiss/hit、成功・失敗・旧要求破棄、bind/draw/frame commit、写真名非記録を確認。frame commitは追加のUI更新を起こさずに到着することも検査。
- 初期のonDraw内callback登録は次フレームへ遅れることが実測で分かったためonPreDrawへ移動。誤った成功記録と旧APKによる試験はIssueに訂正し、最終APKで再試験済み。
- 通常のJPEG6枚連写でinput→burst.open→hero request→task→repository→bind→draw→frame_commitを回収・解析。破損行0、dropped=0、writerエラーなし。タップから最初のframe_commitは61.8msだったが、生成済みキャッシュを使った**計測基盤の動作確認**であり、数秒待つ問題の解消・原因の証拠ではない。
- 通常画面の確認中、既存のloadAlbumsが再更新時にAdapterView.removeViewを呼び停止した。元のa62aa2aにも同じ処理がある。検証阻害を除くため既存のScrollingGridを再利用し、2回の再読込と通常アルバム操作を実機確認した。この修正はログ基盤とは別コミットに分ける。
- 実機の長時間連続収集・故意の容量枯渇・プロセスクラッシュ直前の保存保証・release APKでの非記録動作は未実施。releaseの無効化条件はコードで確認した。

frame commitの意味: [Android公式API](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))。

## 共通Sheetの初回描画

`dialog.show`（window attach）、`dialog.first_draw`（高さ上限を満たした最初のpreDraw）、`dialog.frame_commit`を同じrequestで記録する。再表示は新しいrequest。first_drawには画面上のx/y/width/heightのみ記録し、項目名・入力内容は含めない。frame_commitはGPUへの提出であり画面録画と併用する。非hardware描画はframe_unavailable。入力からの区間は直前の対応するinput/actionと照合する。

`am instrument -w -e mode sheets jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation`はダミーの短い/長い/入力Sheetの初回・同一Dialog再表示を検査する。onShowを置換した経路でも最初のdrawから300msの各描画フレームの位置・寸法が同じこと、幅・水平中央・下部位置・高さ上限・項目選択を確認する。長いリストはfontScale 1.3倍の条件でも検査し、端末の文字サイズ設定は変更しない。実際のTalkBack、IME、OSアニメーションは録画・操作で別途確認する。

## 新規取り込みから一覧反映まで

`media.changed`はMediaStoreの変更通知。`index.start/end`を同じrequestで結び、`index.scan_start/end`、`index.metadata_start/end`、`index.publish_start/published`で走査・メタデータ解析・連写判定後の索引公開を区別する。`index.end`は終了境界であり、成功・変更ありの証拠には`index.published`を使う。変更なし・中断・例外ではpublishedを記録しない。

`screen.index_applied`は一覧データの適用であり、写真描画完了ではない。新写真の匿名photo IDから`image.request`を結び、`image.frame_commit`を別の終点として扱う。解析時はsession内でns順に並べ、別セッション・端末再起動後の時刻やrequestを混ぜない。

`am instrument -w -e mode import-latency jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation`は、一意の試験フォルダへ合成RAW6枚（各24MiBのパディングと640×480の合成JPEG）を新規に作り、MediaStore公開開始→一覧への全件反映と初回実画像frame_commitを測る。毎回新しいURIを使い、既存写真やキャッシュを削除しない。終了時はこの試験でinsertが返したURIだけを削除し、再構築可能な索引を更新する。プロセス強制終了時にはfinallyの実行は保証できない。写真形式・枚数・ファイルサイズ・端末内の総枚数・ログ設定・電池温度・サンプル数を比較条件に残す。

試験中は対象画面の操作を控える。画面遷移や対象Activityのpauseは`test.import_interrupted`で失敗にし、一時退避後に復帰した回も比較に採用しない。`import-interruption`モードは戻る操作、`import-pause-interruption`は端末ホームへの退避と復帰を意図的に実行し、この検出と後片付けを検査する。合成コンテナでの結果を、利用者が取り込んだ実RAWや外部転送アプリの速度の検証済み結果として扱わない。

索引の入力署名はMediaStoreの世代とArchiveStoreの写真レコード変更世代を使う。派生metadataの保存とDB/WALのcheckpointは再走査の理由にしない。走査中の入力変更は次の確認で検出する。前面の確認が変更なしでも、先行する背景更新で完成した索引を表示し、連写・選択・拡大・ソート中の反映は通常の一覧へ戻るまで保留する。

`index-signature`モードは一時DBだけを使い、ArchiveStoreのschema1/2から3への移行と新規作成、既存記録の保全、metadata/checkpointで署名が変わらないこと、写真レコードの変更とrollbackを検査する。`import-deferred`は合成画像の拡大中に新規取り込みを行い、更新結果が保留され、戻る操作で一覧へ反映されることを検査する。

更新の保留は非同期読込の依頼時には解除せず、同じ更新世代のデータを一覧へ適用できた時点で解除する。`index-apply-race`は読込を止めて拡大へ移り、破棄された結果の保留が残ることと、古い読込が新しい更新の保留を解除しないことを合成データで検査する。

## 内蔵現像の調整応答（Issue #53）

`LoupeRawEdit` のdebugログは `adjust.input ticket/ns` と `adjust.frame ticket/input_ns/commit_ns` を対応づける。要求IDは現像画面内だけの匿名IDで、写真名・URI・座標・例外本文は記録しない。状態・入力時刻・IDは1つの不変スナップショットへまとめる。`adjust.render` はGPU描画とreadbackの時間であり、入力からの応答時間ではない。`source.copy` / `source.raw` / `load.failed.*` は選択入力のコピーとRAW展開と固定の失敗分類を区別する。

```sh
python3 scripts/raw-editor-profile.py --serial SERIAL --output /tmp/loupe-edit-new-run
# 同じ実写真を明示的に再利用する場合だけ、許可済みcontent URIをローカルファイルで指定する。
python3 scripts/raw-editor-profile.py --serial SERIAL --output /tmp/loupe-edit-real-run --uri-file /tmp/selected-raw-uri.txt
```

独立実験アプリを終了・起動し、通常は1200x800の合成写真、URI指定時はその入力を使う。質感の同じ面を1.8秒往復×3で操作し、入力からframe commitまでの中央値/p95、各ドラッグ中の反映回数、最後の入力の反映時間を記録する。URIファイルはリポジトリへ追加しない。前景の動画とPiPを止め、ロック状態を確認する。ロック設定や認証を変更しない。CLIは画面を保持する設定を変更せず、検証中は連続操作を行う。終了後の長い待機前に別途端末の状態を確認する。

表示された要求の中央値だけでは、描画破棄による飢餓を検知できない。入力要求数・反映回数・操作中の反映回数を合わせて判断し、合成写真の改善を実写真の性能の証明にしない。frame commitはディスプレイ走査完了を表さない。


## 原寸準備と競合中の調整応答（Issue #55）

`full.prepare.start/complete` は原寸キャッシュの準備区間、`full.detail.complete` は現在領域の精細画像組立完了を示す。固定イベントには `ns` を付け、同じ端末の `adjust.input/frame` と対応づける。`full.detail.complete` 自体は画面への反映完了を表さない。準備時間、連続操作の応答、保存時間を混ぜない。

```sh
# 起動から記録し、原寸準備と重なったドラッグ入力が100件以上・反映が1件以上あることを要求する。
python3 scripts/raw-editor-full-profile.py --serial SERIAL --output /tmp/loupe-full-overlap-new --uri-file /tmp/selected-raw-uri.txt --require-preparation-overlap
# 同じ入力・同じ6ドラッグを、原寸準備完了後に測る。
python3 scripts/raw-editor-full-profile.py --serial SERIAL --output /tmp/loupe-full-ready-new --uri-file /tmp/selected-raw-uri.txt --wait-prepared
python3 scripts/test-raw-editor-full-profile.py
```

出力先は毎回新規ディレクトリ。URI指定を省略すると合成画像を使う。開始前に実験アプリだけを終了し、動画・PiPを止め、ロックと前景を確認する。ログは対象タグだけを収集し、端末全体のログを消去しない。各1.8秒のドラッグ6回について入力数・反映数・最終入力の反映時間を出し、準備中と完了後の中央値/p95を別々に示す。起動やタブ切替の入力はドラッグ計測から分ける。準備との重複が足りなければ失敗とし、準備後の結果で代用しない。

電池温度を前後で記録し、RSS/HWM/PSSは入力計測終了後に取得する。取得できない値や反映のない区間はnullを残す。終了後のPSSは準備中のピークではなく、HWMもOSから取得できた場合のプロセス全体の値である。LibRaw内部のピークをタイル用メモリの上限と同一視しない。画質、原寸の保存寸法、原本hash、ズーム時の見え方は別の実機検査で確認する。
