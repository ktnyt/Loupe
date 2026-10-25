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
