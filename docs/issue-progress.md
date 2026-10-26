# issue進捗記録

目的は工程ごとの経過時間と手戻りを測れるようにすること。`AGENTS.md`が作業開始時の規約、CLIがJST時刻・構造化データ・投稿後の再取得を担当する。Python 3と認証済みのGitHub CLI（`gh`）、`ktnyt/Loupe`のissueコメント権限が必要。

## 使い方

例のissue番号は実際の対象に置き換える。作業IDは `20261025-issue42-feature-a` など、他の作業と重複しない値を作り、再開時にも引き継ぐ。

```sh
python3 scripts/issue-progress.py record --issue 42 --run 20261025-issue42-feature-a --phase implementation --status start --note '対象範囲と実装方針'
python3 scripts/issue-progress.py record --issue 42 --run 20261025-issue42-feature-a --phase implementation --status complete --note '変更内容、テスト結果、残る未確認事項'
python3 scripts/issue-progress.py record --issue 42 --run 20261025-issue42-feature-a --phase install --status start --note '対象端末とAPKバージョン'
python3 scripts/issue-progress.py record --issue 42 --run 20261025-issue42-feature-a --phase install --status complete --note 'adb installの結果と端末上のバージョン確認'
python3 scripts/issue-progress.py record --issue 42 --run 20261025-issue42-feature-a --phase device-test --status skip --note '文書のみでアプリの動作・UIに影響しない'
python3 scripts/issue-progress.py report --issue 42 --run 20261025-issue42-feature-a
```

| phase | 記録する境界 |
| --- | --- |
| design | issue確認後の目的・契約整理の直前／契約確定直後 |
| baseline-test | 変更前の実機操作直前／結果確認直後 |
| claude-design | ClaudeへのUI依頼直前／完了とHTML・設計書更新確認直後（待ち時間を含む） |
| ui-review | 独立したUI・UX評価の依頼直前／結果受領直後 |
| cold-review | 独立レビュー依頼直前／結果受領直後 |
| security-review | セキュリティレビュー開始直前／結果確認直後 |
| delivery | PR作成直前／マージ・issue状態確認直後 |
| implementation | 着手直前／予定した実装と必要なローカル検証の完了直後 |
| install | 端末へのインストール直前／結果確認直後 |
| device-test | 実機操作直前／結果の確認直後 |
| additional | 追加修正の着手直前／実装と必要なローカル検証の完了直後 |

各工程のstatusは `start` → `complete` / `fail` / `cancel`。未実施の工程は `skip` と理由を記す。失敗・中断後や同じ工程を繰り返す場合、`--attempt 2`、`--attempt 3` と増やす。開始と結果は同じ試行番号にする。 開始記録は変更・インストール・検証のコマンドと別のtool callで実行し、終了コード0と投稿URLを確認してから本体操作へ進む。結果記録も独立実行する。失敗した記録と後続操作を改行だけで同じシェルへ連結しない。作業を中断するときに閉じ、再開を別の試行にする。監視を継続する応答待ちは工程に含める。

`--dry-run`は投稿本文の確認専用で、GitHubへのアクセスも遷移検査もしない。通常実行は既存コメントを取得して二重開始・二重終了・開始なしの終了を拒否する。同時投稿の排他制御はないため、一つの作業IDの記録は一人が担当する。

## 集計と限界

コメントのHTMLコメント内に `loupe-progress:v1` 形式のJSONを保存する。`run`（作業ID）、`phase`、`attempt`、`status`、`at`（ISO 8601、+09:00）、`id`（イベントUUID）で機械的に集計できる。人向け本文には実施内容と根拠を書く。

`report`は工程・試行ごとの状態と経過秒数をJSON Linesで出力する。未終了または対象作業の記録なしなら終了コード1。省略は時間なしとして扱う。必要工程の欠落は自動判定できないため、担当者が実施内容と照合する。

計測値は開始から結果までの**経過時間**であり、純粋な稼働時間やストーリーポイントのベロシティではない。並行工程を単純合算しない。失敗・中断の時間、追加開発回数、省略を分離し、完了件数と工程時間の推移を比較する。端末時計に依存するので時刻同期を前提とする。

記録漏れは通常CLIで過去の開始を作らず、issueに「事後補記」と明記して追記する。証拠のある実施日時・記録日時・根拠を区別し、不明な日時は不明とする。通常の集計には混ぜない。

## 導入の確認と見直し

今回の導入はプロジェクト規約と補助CLIによるもの。エージェントの全操作をフックで監視・強制する仕組みではなく、規約を読まないエージェントの記録漏れまでは防げない。PRテンプレートでも記録確認を求める。

最初の実開発issueで、必要な開始・終了コメントが揃うこと、投稿の負担、失敗後の再開が正しく集計されることを確認する。記録漏れや負担が続く場合は、実際のビルド・インストール手順への組込みを検討する。実務での遵守率・時間短縮はまだ未検証。戻す場合はAGENTS.mdの本ルーティン、CLI、本文書、PRの確認項目を一緒に外す。

## 開発フローとの対応

[開発フロー](development-workflow.md)に従い各工程を記録する。UI変更なしならclaude-designとui-review、実機不要ならbaseline-test・install・device-testを理由付きskipにする。design、implementation、cold-review、security-review、deliveryは通常開発で必要。additionalは追加修正を行うたびに記録する。先行リファクタリングはそのissueと作業IDで記録する。

レビューのcompleteは結果受領を意味し、合格を意味しない。本文に合否・指摘・後続issueを残す。修正はadditional、再評価は同じレビュー工程の新しいattemptとする。Claudeを監視しながら作業を続けている間はclaude-designを開いたままにし、作業自体を中断するときだけcancelにする。

PR作成前にdelivery以外の工程をreportで確認し、その後deliveryを開始する。マージ直前のreportではdeliveryの未終了1件だけが予定内で、他の未終了は解消する。マージ・自動クローズを確認後、閉じたissueへdeliveryの結果を追記し、最終reportで未終了がないことを確認する。reportの成功はレビュー合格・必要工程充足・マージ可否を保証しない。
