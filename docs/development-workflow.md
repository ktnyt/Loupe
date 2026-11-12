# Loupeの開発手順

理念と必須の順序は[AGENTS.md](../AGENTS.md)、工程記録は[issue-progress.md](issue-progress.md)を正とする。本書は実行時の確認方法と委譲の手順を定める。

## issueから実装へ

`gh issue list --repo ktnyt/Loupe --state all --search '対象の語句'` で重複を調べ、対応するissueがなければ既存の開発・設計テンプレートで起票する。`gh issue view 番号 --comments` で最新の要求、契約、進捗を読む。既存のissueが同じ目的を扱っていれば引き継ぐ。

issue確認時に `git status --short`、`git branch --show-current`、`git worktree list` で状態を確認し、設計・新規branch作成より先にmainを最新化する。作業中の変更を破棄したり、無関係な変更を自動でstash・コミットしたりしない。混在する場合は専用worktreeを使う。

mainを安全に操作できるworktreeで、各コマンドの成功を確認して順に実行する。

```sh
git switch main
git fetch origin main
git merge --ff-only origin/main
git rev-parse HEAD origin/main
```

最後の2つのコミットIDが一致することを確認し、issueの設計記録に基点を残す。fetch失敗、fast-forward不可、ローカルmainだけのコミットによる不一致は中断として記録し、強制リセットせず原因を確認する。

mainが別worktreeで使用中なら、そのworktreeの状態と利用状況を確認し、安全に更新できる場合だけそこで上記手順を実施する。他の作業を妨げる場合は中断して調整し、強制checkoutはしない。専用worktreeの新規作業branchは確認済みのmainのコミットから作る。既存branchで作業を再開する場合は、そのbranchと変更を保持し、更新したmainとの差分を確認して必要な取り込みを別途行う。

目的・機能・要件・不変条件・事前条件・事後条件を、issueまたは現行設計書に実装前に記す。成功条件と失敗からの復帰、UX、計測条件を含め、最もシンプルな実装方針を選ぶ。リファクタリングが必要なら、その必要性と完了条件を別issueに書き、元issueに依存先をリンクして先行実施する。単に周辺コードが気になるだけで依頼を広げない。

branchは `18/development-workflow` のように作成する。先行issueにもその番号のbranchを使う。先行リファクタリングのマージ後はmainの最新化を再実行してから本題のbranchを作成する。変更前の実機確認で再現操作・期待・実測・対象端末・アプリ版・写真枚数/形式・キャッシュ状態を残す。アプリに影響しない文書や開発ツールの変更など、省略できる場合は理由を記録する。接続できないことは「不要」の根拠にならない。

## APKとmainの照合

常用端末の本体はmainからビルドしたAPKへ統一する。PR branchの動作検証と、普段使う端末への常用導入を分ける。通常はエミュレータや予備端末で実装・独立レビューを先に確かめ、main反映後に常用APKを用意する。

```sh
python3 scripts/deploy-apk.py prepare --output outputs/apk/main-<作業名>
python3 scripts/deploy-apk.py inspect --apk outputs/apk/main-<作業名>/loupe.apk
python3 scripts/issue-progress.py record --issue ISSUE --run WORK_ID --phase install --status start --note 'mainコミットと対象端末' --then python3 scripts/deploy-apk.py install --bundle outputs/apk/main-<作業名>/bundle.json --serial SERIAL
python3 scripts/deploy-apk.py status
```

prepareはcallerの未保存変更を拒否し、origin/mainをfetchして、そのコミットだけを新しい一時worktreeでビルドする。APKとsource commit/tree、SHA-256、applicationId、versionCode、署名証明書、検出した必須機能を同じbundleへ記録する。source commit/tree・mode・issueはビルド時にassetとしてAPKへ埋め込み、APKの署名対象に含める。installはbundleとの一致を確認し、JSONだけをmain扱いに書き換えたAPKを拒否する。既存APKへmain証明を後付けするコマンドは設けない。bundleと端末記録はgit管理外。

installはAPKを非公開の一時ディレクトリへコピーし、同じスナップショットを検査・導入する。サイズ上限付きで署名対象provenanceを読み、重複を拒否する。これらを再計算し、ソースがmainに含まれること、端末と同じpackage/署名でversionCodeが下がらないこと、library/video/nano-banana/camera-importを備えることを確認する。現在の端末がcameraなしでも必須検査は残す。カメラのUSB attach filter/metadata/connectedDevice serviceも確認する。ただしmanifestだけではmenu欠落が分からないため、camera-tabsの常設カメラ/Nano設定遷移などのUI検査も行う。

`adb install -r`の前にpending記録を保存し、導入後の端末APKを取得してハッシュ・版・署名を読み戻す。切断や不一致ならpendingを残して停止する。端末ごとの記録は正確なserialのSHA-256をファイル名に使い、serial自体も記録と照合する。git common directoryを基点に全worktreeで共有する。共有記録ディレクトリを、以前の記録の確認から導入後の読み戻しまで排他ロックし、並行する導入を拒否する。recoveryやreadback待ちの間は同じADB serialで完結させる。別serialのpendingが残る場合は新しい導入を拒否するため、USBと無線ADBを途中で切り替えない。`--records`は隔離テスト等で別記録先が必要な場合に限り、常用導入のpendingを隠すために変更しない。検査用の別applicationIdのAPKを入れる操作は、本体の常用導入とは区別する。

main前に常用端末で確認が必要な場合は、issueと目的を明記して復旧/検証用として作る。

```sh
python3 scripts/deploy-apk.py prepare --mode recovery --ref <保存済みcommit> --issue ISSUE --output outputs/apk/recovery-<作業名>
python3 scripts/issue-progress.py record --issue ISSUE --run WORK_ID --phase install --status start --note '復元用commitと対象端末' --then python3 scripts/deploy-apk.py install --bundle outputs/apk/recovery-<作業名>/bundle.json --serial SERIAL --ack-recovery
```

recovery導入後はpending-main-integrationとなり、statusは成功にしない。次のAPKもrecoveryであっても、未統合の復元コミットを含まなければ上書きを拒否する。端末から取得したAPK内の署名provenanceもmain/recoveryの両方で祖先検査するため、Mac側の記録がない場合やADB serialが変わった場合も復元コミットを引き継ぐ。ソースcommitはmerge commitまたはfast-forwardで祖先関係を保ってmainへ反映する。導入済recoveryをsquash/rebaseして元commitを祖先から外さない。その後、mainから作り直したAPKを導入・読み戻して閉じる。機能を欠くmainを戻し先にしない。実機で確認できないまま常用検証APKを残してdeliveryを完了にしない。実機導入・確認が残るPRはRefsとして、完了前のissue自動クローズを避ける。

機能の意図的な廃止は要件変更として別途判断し、issue・レビュー・必須契約の更新を伴わせる。検査の回避flagやuninstall、データ消去、downgradeで合わせない。今回のcameraなしAPK相当を拒否するhost fixtureと、main/hash/署名/版/導入後切断の検査は `python3 scripts/test-deploy-apk.py` で行う。

## Material 3によるUI設計

現行のUI契約は[Material 3 UI契約](material3-ui.md)と対象issueの契約に置く。`docs/design/`のHTML・画像は当時の設計や検証を示す履歴であり、新規UIの現行基準ではない。既存の動作契約とアクセシビリティは維持する。

実装前に、対象画面・状態・操作、Material 3部品の適用範囲、カスタム描画を残す理由、レイアウト制約、文字倍率・insets、読み上げと操作方法を記録する。Material Components for Android 1.13.0 と既存の dark Material 3 theme を使い、標準操作には適切な標準部品を使う。写真・曲線など既存の直接操作描画は維持し、Material 3の状態表現とアクセシビリティを補う。issueにない全体再設計へ範囲を広げない。

実装後は同一画面状態のAndroid captureを変更前後で取得し、端末幅・文字倍率・OS領域などの条件を記録する。静止画では分からない操作は操作記録または録画で確かめる。独立レビューでは配置、読みやすさ、状態変化、タッチ領域、遷移、読み上げをissueの目的と契約に照らして評価し、指摘を修正して再確認する。HTMLとのpixel一致は要求しない。画面が用意できない場合は理由と未確認項目を記録する。

## 実機と独立したUI・UX評価

端末の縦画面を前提に実装・確認する。検査ではActivityの向きと実際の幅・高さ・文字倍率を確認し、検査後は元の要求方向へ戻す。横画面が必要になったら、issueの対象と契約に含め、実機captureと独立したUI/UXレビューで確認する。写真の縦横比による配置調整を、端末の横画面の設計と混同しない。

端末を使う検証・画面操作・待機用の再生は直列に実行する。バックグラウンド実行の検証はsessionの終了と結果ログを確認してから次の端末操作へ進む。待機用動画の起動も検証中には行わない。通常の合成検証は `scripts/device-check.py --standby-youtube` で開始前に動画を一時停止してYouTubeを終了し、PiPが検査画面に残らないようにする。成功・失敗時とも終了処理でYouTubeへ戻して画面点灯の保持を確認する。動画を使わない場合は `--no-standby-video 理由` を必須とする。前面と画面点灯を保持するウィンドウが両方YouTubeであることを最大5秒確認する。動画の読み込み待ちを再生失敗と混同せず、過去の保持ウィンドウだけでは成功にしない。保持状態は将来のロック防止の保証ではなく、次の実機操作前の確認は省かない。TalkBack検証は設定復元後に別途動画を再開する。終了処理が失敗した場合は、導入や検査の成否と分けて記録し、次の待機前に実際の画面保持を確認する。

実装後は変更に合ったテストとビルドを行い、上記のmain照合gateでAPK版と端末を特定して導入する。実機で期待結果・実測結果を確認し、対象画面をキャプチャする。UI変更では同じ画面状態のAndroid captureを条件付きで用意する。動きや操作は録画または操作記録を添える。機密情報を含まないテストデータで撮影する。

新しいサブエージェントを履歴なしで起動し（利用環境が対応する場合は `fork_turns: none`）、次の指示と成果物だけを渡す。実装者の見立て、弁明、予想する合否は渡さない。

> 対象の操作目的は〈利用者の目的〉です。issueの現行契約とAndroidのcapture・操作記録を根拠に、状態の見分けやすさ、階層、余白、文字、タッチ領域、反応、待ち時間、中断・復帰、読み上げを評価してください。Material 3標準部品の使い方とカスタム描画の状態・アクセシビリティも確認してください。確認できない項目は未検査とし、修正項目を位置・状態・根拠付きで列挙してください。ファイルは変更せず、合否と所見を返してください。

見えていない操作性を静止画だけで合格にしない。写真内容・OS領域・描画方式などcapture条件の差も具体的に記録する。要件に関係する指摘は修正し、再インストール・再撮影・再評価する。明らかにissueの本質から外れた指摘だけ、その根拠をissueへ記録して追跡を分ける。未確認事項を明示する。

## Cold ReviewとSecurity Review

cold-reviewの手順を用い、開発履歴を継承しないレビュアーへ対象worktree、比較元コミット、issueの要求と契約、読取専用の範囲を渡す。差分に加え、文書と実装の一致、不要な仕組みも確認する。

その後security-reviewを使い、変更した範囲の入力・権限・保存/共有・外部送信・ログ・秘密情報・依存関係を確認する。対象外の観点は理由を残す。変更していないアプリ全体まで検査済みとしない。

それぞれ指摘の発生条件と影響を確かめ、対象issueに必要な修正を行い、同じ範囲を再レビューする。レビュー結果・採否・根拠・未検査・再試行番号をissueへ残す。範囲外でも重要なら別issueに起票してリンクする。未解決の範囲内指摘、実施不能の必須検証があればマージせず中断を記録する。Security Review後のコード修正も必要なテストとCold Reviewへ戻す。

## 計測と完了

アプリの計測は[診断ログ](diagnostics.md)を使い、各issueで操作開始から利用者に結果が届くまでの時間、成功/失敗、待ち・中断・再試行、対象機能の負荷や件数を定義する。必要なイベントがなければ先に計測を整える。測定値には単位、端末、アプリ版、入力条件、サンプル数を付ける。アプリのあらゆる事象を計測対象として検討し、未計測の範囲もissueに明記する。写真原本・位置情報・個人情報を計測のために収集しない。

開発は進捗CLIで設計、変更前確認、UI設計、実装、インストール、実機確認、各レビュー、追加修正、PR/マージの経過時間と試行回数を残す。取得できるビルド時間・テスト時間・利用量も単位とともにissueへ記録し、取得できなければ未計測とする。経過時間は稼働時間とは異なり、並行工程を加算しない。次回見積もりでは似た条件の実測とばらつきを使う。

進捗reportを実施内容と照合し、PRテンプレートへ根拠のリンクを記入する。完了条件を全て満たすissueだけ `Closes #番号` を記す。PRの差分・チェック・レビュー状態を確認し、必要なチェックが通ってから通常のマージを行う。保護ルールや失敗チェックを迂回しない。マージ後にPRの状態とissueの自動クローズを再取得して確認する。

この仕組みは規約・既存CLI・テンプレートによる運用であり、全操作を自動実行・強制するものではない。次のUI開発でMaterial 3 UI設計から実機確認まで通し、記録漏れ・手戻り・工程時間・記録負担をissueで評価する。負担が効果に見合わなければ重複手順を減らす。戻すときは本変更のコミットをrevertし、過去のissue記録は保持する。

### TalkBackを一時的に使う検証

TalkBackの有効化を許可されたこと自体は、検証の必須要件を意味しない。変更がフォーカス・読み上げ属性・AX操作へ影響する場合に、その影響に合う範囲で使う。通常の配置・色・余白の変更で全画面・全寸法のTalkBack検査を一律に追加せず、Material 3 UI契約に沿った実機captureと通常操作を先に確認する。有効にした場合は、検証範囲の見直し後も設定を元へ戻して再読する。

変更への影響から検査が必要と判断し、利用者が検証中の有効化を許可している場合に、`python3 scripts/talkback-check.py --serial <端末ID>` を使う。元の設定を取得し、終了時は各設定を独立して復元して再読する。PASSだけでなく `Restored and verified` を確認する。既存のサービスが有効な端末は変更せず止まる。通常タップの検査と、実TalkBack下のフォーカス検査は分ける。

端末の切断やプロセスの強制終了では復元を保証できない。復元エラー時は端末の設定を確認して戻し、検証完了として扱わない。この手順を変更したら `python3 scripts/test-talkback-check.py` と同じ実機検証で有効化から復元まで確かめる。

通常の合成実機検査は `python3 scripts/device-check.py --serial <端末> --mode <mode> --width 400 --font 1.0 --standby-youtube` で実行する。instrumentationが終了コード0でも本文がPASSでなければ失敗として終了する。結果の確認と次の検査は分け、失敗時は対策と再確認を先に行う。

写真一覧・選択のTalkBack検査は同じ復元付きCLIで `--mode gallery-screen-talkback` を指定する。実TalkBackのタッチガイド有効状態、写真のフォーカスとAX操作、共有設定スイッチの読み上げ属性と切替を確認する。通常の `gallery-screen` は画面上の座標へタップを送る。音声の聞き取りやTalkBackのジェスチャー操作まで検査済みとは扱わない。

## OTA配布

[OTA更新手順](ota-updates.md)に従い、mainへのpush後にGitHub Actionsで検査・署名・stable Release公開を行う。版はfirst-parent履歴から自動算出するため、app/build.gradleの固定番号を手動で増やさない。公開jobの成功とReleaseのsource/hashを確認する。手動配布でもmain prepareのbundleだけをpublishする。Obtainiumで通常更新した後は `scripts/deploy-apk.py confirm --bundle <bundle.json> --serial <SERIAL>` をissue進捗の `--then` から実行し、USB installをやり直さず実端末のAPKを照合する。配布したAPKと同一であることを確認するまでdeliveryを完了にしない。
