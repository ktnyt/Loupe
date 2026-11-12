# Loupe UIデザイン案

> このディレクトリは過去のHTMLプロトタイプ、設計メモ、captureの履歴です。これらは現行UIの視覚的権威ではありません。現在のUI方針は[Material 3 UI契約](../material3-ui.md)、動作は各issueおよび現行契約を参照してください。過去の検証記録は当時の結果として保持します。

## Material 3移行の実機記録（Issue #92）

[実機キャプチャ・検証範囲](material3/README.md)。現行契約に沿った表示と操作の証拠であり、過去HTMLとのpixel一致を要求する資料ではありません。

## 共有容量の事前確認（Issue #89）

[Claudeの参照HTML](loupe-share-capacity.html)は採用済みの選択・共有画面と変更後を並べる。SHA-256 `e275505e2aeab8c287f5dc0218d5af4de8c82337545b64fbb91fae8c45cbd80c`。容量の札、超過時の共有無効、形式別の判定、測定中・失敗とクラウドRAWの後で確認する状態を扱う。通常・超過の比較PNGを369dp/文字1.0と320dp/文字1.3で収録する。合成写真による設計であり、実機検証は別に行う。[契約](../share-capacity-contract.md)を正とし、いずれかの形式が使えるときは入口を維持し、無効は各形式で判定する。

[2026-10-25版・Claude Design版29の画面一覧と操作プロトタイプ](loupe-ui-2026-10-25.html)

2026-10-25にClaude Designから取得した51ボード（操作プロトタイプ・仕様を含む）です。旧版はGit履歴に残しています。現在、この書き出しは必要なランタイムがなくローカルでは白い画面になります。表示可能なHTMLを準備する間はClaude内の元画面で確認します。

操作は下側、写真・アルバム・タグの一覧は上から表示します。アルバムの最近の写真、写真一覧の他アルバムのカルーセル、新しい日本語を適用対象とします。画面のダミー写真や日付、基準日の仮表記は実装値ではありません。

[Issue #2](https://github.com/ktnyt/Loupe/issues/2) の初期本文より、この版と最新の実装契約を優先します。アプリに追加済みの自動タグ付けは維持します。デザインの部分共有など、既存の処理契約と異なる点はHTMLだけを根拠に変更しません。プロトタイプの検証はAndroid実機の動作保証ではありません。

[ローカルで表示できる元画面のHTML](loupe-original-restored.html)を取得しました。元の描画結果を保存した51画面で、画面内の操作は動きません。タグ一覧と同期画面の表示を確認し、元の画面との比較を進めています。実機への適用済みを示すものではありません。

## 比較基準の扱い

元のClaude Designアーティファクトを全体配置の比較基準とする。個別issue用HTMLが上書きするのは、そのissueの明示された変更対象だけ。既存Androidへの正規化や部分的な検証用モックは、元デザインとの一致を保証しない。各画面は元アートボード、要求された変更、実機を直接比較する。未確認の画面を個別issueの完了だけで適用済みにしない。

## 版29での追加と確認範囲

連写の読み込み表示4ボード（I1〜I4）と、処理状況ダッシュボード8ボード（J1〜J8）を含みます。2026-10-25にClaude DesignのHTMLエクスポートから取得し、全51ボードおよび追加ボードの収録を確認しました。旧HTMLとの置換であり、Android実装の変更ではありません。

**[Issue #17](https://github.com/ktnyt/Loupe/issues/17) の「文字によるステータス表示の廃止」は未反映です。** この版には完了・一時停止・失敗等の文字表示が残っています。今後の実装では最新のユーザー要件を優先してください。[Issue #16](https://github.com/ktnyt/Loupe/issues/16) のダッシュボードの件数は架空例であり、実データではありません。

Claude上で一覧画面の表示を確認済みです。書き出したHTMLの全操作導線、Android実機での表示は未検証です。

## その他メニューの初期表示（Issue #13）

[Claude作成の比較基準HTML v2](loupe-issue13-menu.html)。初回・再表示の位置安定性、現行の幅 `min(表示幅−24dp,560dp)`・角丸20dp・可視高さ48%上限を扱います。左の不具合は模擬、右が基準です。項目・背景の写真・見出し・装飾はダミーで、これらの置換を指示するものではありません。既存のメニュー項目、読み上げ、ADJUST_RESIZEを維持します。版29全体を置換するものではありません。

## ソートの操作名（Issue #7）

[Claude作成の比較HTML](loupe-issue7-naming.html)。未開始・途中・完了の入口、比較/結果見出し、共有前誘導、再ソート/方式変更確認の名称を適用します。主操作は棒グラフの割当24dp（既存ActionIconの描画基準26dp）、文字13sp Android sans-serif-medium、色#111111/背景#EDEDEA、角丸26dp、最低高64dp。HTML取得後、アイコンの縦配置・棒の形・6dp paddingを現行Androidに合わせ、比較枠の線を寸法外にして実機400dpの条件を追加しました。比較対象はこの部品と名称で、背景やダミーの共有方式・シート装飾・全画面配置の再設計ではありません。状態文言は#17、モード説明は#6に分けます。

主CTAのHTMLはAndroidのfont paddingを含む行箱を約24dpで表現します。ブラウザの代替フォントとの字形・太さの差は残るため、ピクセル完全一致の保証ではありません。名称、部品寸法、既存Androidの描画条件を基準とします。

## 全写真とアルバムの並列タブ（Issue #12）

[Claude作成のHTML](loupe-issue12-tabs.html)。共通の固定2タブと状態保持の基準です。背景#0F0F10、上線1dp/#19191B、左右8dp、均等幅、最低高48dp、16sp sans-serif-medium/24sp行高、上下6dp、文字下4dpに2dpの下線。非選択文字#A9A9A4、選択文字と線#EDEDEA、写真選択中は両タブを無効にしてalpha .38。標準ナビゲーションのinsetsはOS値を一度だけ適用します。

表記は既存の「すべての写真」を採用します。HTMLの#11枠は将来の候補で今回追加しません。カルーセル/写真/フィルター/状態表示はダミーで、タブ以外の配置や新しい表示の追加を指示しません。子画面のBackは直前の階層へ戻します（写真→連写→一覧、個別アルバム→呼出元ルート）。全写真ルートとアルバムルートのBackは互いのタブへ遷移しません。選択中Backは既存どおり選択終了です。フィルターごとの位置を保持し、初めての条件は先頭から表示します。状態保持の対象は同一Activityの切替、既存の保存済み画面復元は回帰確認します。ブラウザとAndroidの字形差は完全一致を保証しません。

## アルバムからタグへの入口（Issue #11）

[Claude作成のHTML](loupe-issue11-tag-entry.html)。アルバムrootの既存dockを「タグ」「その他」の均等2操作にします。PANEL背景#19191B、全周8dp、各ボタン最低64dpで内容に応じて伸長、13sp sans-serif-medium、既存24dp割当アイコンを文字の上に置き間隔4dp、ボタン全周6dp。HTML取得後、既存ActionIconの26dp描画基準とタグ/その他の線形、Androidのfont paddingを含む比較用24dp行箱に正規化しています（実測した文字1〜1.3倍では既存dock高80dpを維持）。固定2タブの幅・位置は#12のままで、将来候補の右端枠は使用しません。ブラウザとの字形・太さの違いは残ります。

album rootからのタグはfolder=null・camera=すべて・photo指定なし。標準Backで元の一覧位置へ戻ります。通常のタグ詳細からのBackも既存どおりタグActivityを終了します（一覧を経由しません）。全写真/個別album/単一写真の既存タグ入口の対象範囲は維持します。HTMLの写真・タグ一覧・状態表示はダミーで、既存タグ機能や#1推論を変更する指示ではありません。

## 比較写真のA/B表示（Issue #10）

[Claude作成のHTML](loupe-issue10-comparison-labels.html)。A=ranking.upper、B=ranking.lowerを実画像の左上に重ね、比較中の位置説明を除きます。画像端から8dp（短辺96dp未満は4dp）、最低24dp四方、内側左右7dp/上下2dp、角丸6dp。背景#B80F0F10、外周1dp/#73EDEDEA、文字13sp sans-serif-medium/#EDEDEA/行箱16sp。文字拡大時は内容に応じ伸長、極小画像は余白を減らしても入らなければ装飾を隠し、画像の読み上げ名にA/Bを残します。

FIT_CENTERの実画像矩形から毎描画時に配置し、読込前/失敗時はスロット内へ配置します。画像のタップ・長押しを装飾が妨げず、既存の選択/拡大対象と対応します。比較配置の面積判定・全体表示・順位保存を変更しません。HTML取得後、比較スロット間隔を既存の0dpへ、選択可能条件を両画像完了へ揃えました。読み込み/失敗の模様や選択枠・操作ボタンは模擬で、新しいUI追加を指示しません。フォントの字形・幅差はありpixel完全一致は保証しません。

#10の明るい/暗い背景は実機照合用の単色合成画像（#FFFFFF/#0F0F10）と共通です。
比較画像のタップ・長押しは両画像完了まで無効、既存の拡大ボタンは読込状態によらず拡大画面を開きます。HTMLはその遷移だけを模擬し、拡大先での読込成功を保証しません。

## 操作ラベルの欠け・折り返し（Issue #9）

[Claude作成のHTML](loupe-issue9-action-labels.html)。比較/結果の末尾操作で表示名を「取り消し」、読み上げ名・tooltipを「直前の操作を取り消す」とします。既存undoアイコン、13sp、最低64dp、全周6dp、dock全周8dpと色を維持します。3/4操作の必要セル幅（文字の実測幅+左右padding、最低48dp）から収まる最大列数を選び、順番を保って次行へ送ります。十分な幅では従来の1行です。全幅のソート/再開/結果CTAは検査のみで変更しません。HTMLの周辺操作は模擬、字体差と行箱の差はAndroid実測で確認します。

#9の参照は取得後、既存ActionIconの26dp描画基準（viewBox24）、主CTAの縦配置、実機の1〜1.3倍で64dpを維持する24dp行箱へ合わせています。比較終了では方式によらず3操作です。

最終版は同じ列調整を連写詳細の前後移動4操作にも適用し、「この写真を1位に」の表示を「1位にする」へ短縮します（完全なAX名・tooltipと確認操作は維持）。

## Android標準Backへの統一（Issue #5）

[Claude作成のHTML](loupe-issue5-system-back.html)。画面の専用Back操作を除き残る操作で幅を分配します。比較は取り消し/その他（全順位比較中は同じ順位を追加）、連写は前/次/拡大。既存の64dp・13sp・必要幅の列調整・完全AX名を維持。取得後に既存アイコンを正規化し、Cloudの残る3操作とタグ選択が写真一覧に属する点を現行実装へ合わせました。タグ/設定の内容と写真は簡略模擬で、実アプリへの追加UI指示ではありません。

標準Backは写真→呼出元の比較/一覧、比較→連写/共有選択、連写→一覧、個別album→元root、root→OS。Tagは写真→同フィルタ/位置の写真一覧→詳細→タグ一覧→呼出元。選択中はまず選択終了。busy保全、順位/原本/タグ保存と確認キャンセルは保持します。

## Issue #6 ソート方式

参照: [loupe-issue6-ranking-modes.html](loupe-issue6-ranking-modes.html)。Claudeの完成案を採用し、写真を人に限定しない名称「すべてに順位」へ短いラベルを調整した。1位方式は1/2/2/2、全順位方式は1/2/2/4の例を表示し、同順位をまとめる。2択の枠とチェックで現在方式を示す。写真や他のdockは簡略模擬であり新規配置の仕様ではない。320/400dp・文字1/1.3倍、選択/確認/キャンセル/保存失敗を収録。

## Issue #14 操作の手がかり

参照: [loupe-issue14-direct-affordances.html](loupe-issue14-direct-affordances.html)。結果一覧の説明文を除き、左右8dp・行間4dp・角丸12dpのカード、88×72dp/角丸8dpの写真、14sp/20sp行高の順位と省略しないファイル名、右24dpの拡大印を表示する。行全体が1操作で、画像や印は別のフォーカスを持たない。失敗時は画像の代わりの印を表示する。

Cloudの入口はログイン・同期の設定・接続と確認。初期説明と空statusを隠し、実際の認証・通信・失敗・結果は既存メッセージを保持して表示する。HTMLの状態別色/アイコン/短い文言は#17に属する提案として採用せず、今回の変更は可視性とpolite通知に限定する。Cloudのヘッダ・メニュー・周辺余白は模擬で既存実装を維持。接続メニューの4操作、ログアウトの確認、FLAG_SECURE、自動送信しない契約を保持する。

#14は14sp/20sp行高・文字1.3倍を同じ条件にする。OSとブラウザの書体の字幅・太さ、Unicode改行位置は異なり、カード高は内容に応じて伸びる。固定改行や文字縮小は行わず、実機で省略/欠けがないことを検査する。pixel完全一致とは区別する。

## Issue #17 状態表示

[Claude完成版HTML](loupe-issue17-visual-status.html)。8状態、原因の印、400msの表示遅延、最低600ms表示、8秒から数値札、900msの結果通知と持続する失敗を収録。320/400dp・文字1/1.3倍で8画面を操作できる。空状態の文も#17の全画面要件に従い印と既存操作に置換する。詳細な適用範囲・契約は[状態表示](../visual-status.md)を参照。周辺配置と写真は模擬で、Android検証の代わりではない。

## Issue #16 処理状況ダッシュボードの設計

[Claude作成の単体HTML](loupe-issue16-dashboard.html)と[観測契約](../background-dashboard.md)。版29のJ1〜J8をこの範囲で置き換える。#17の状態表現を使い、共通準備ジョブと仮画像/プレビュー/タグ解析/自動付与を分離。現在取得可能モードを実装基準とし、将来の計測済み例は未実装の参考に限る。件数不明と既知0、旧データと実行主体消失、処理失敗と取得失敗を区別する。取得後にAXの時刻帰属と失敗タイミングの推測を修正し、Claudeの最終版にも維持。前回値には履歴印を加えて色以外でも識別できるようにした。

Android実装は[#39](https://github.com/ktnyt/Loupe/issues/39)で導入済み。現行の適用範囲と実機検証の限界は[観測契約](../background-dashboard.md)を参照する。HTMLの将来モードは引き続き未実装の参考であり、実装済みの現在モードと区別する。

## Issue #8で使わない派生案

[loupe-issue8-consistency.html](loupe-issue8-consistency.html)はタグの写真・件数と同期の直接操作を省いたため、比較基準には使わない。T1/T11は下記の元画面に変更要求を反映したHTMLを参照する。画面横断の残る確認範囲は[比較契約](../ui-consistency.md)にまとめる。

## タグ一覧とクラウド同期

[元のデザインに依頼済み変更を反映したHTML](loupe-t1-t11-applied.html) はT1/T11の実装参照。左側に元の画面、右側にBack削除・状態表示変更などを反映した画面を並べる。写真と数値は合成例であり、Androidでは実際の記録を読む。

## アルバム・写真一覧、ソート結果、タグ詳細の比較

[元画面と依頼済みの変更を並べたHTML](loupe-result-lists-tagdetail-applied.html)。アルバムの最近の写真、2列一覧、写真一覧の他アルバム横送りと4列写真、結果の大きな写真、タグ詳細の格子を収録。各画面の補足設計は下記の専用HTMLを参照する。画面を収録していることとAndroidの適用完了は区別する。

[タグ詳細の既存操作を保持したHTML](loupe-tag-detail-existing-ops.html)をV05の実装参照とする。合算件数、写真追加、一括訂正、解除確認を含む。[ソート結果の既存操作を保持したHTML](loupe-sort-result-existing-ops.html)は結果画面の実装参照。全写真の拡大、同率、読込失敗、長い名前を含む。

[写真一覧の4フィルターを保持したHTML](loupe-photo-list-filters.html)は全写真・アルバム内の写真一覧の実装参照。元S02の配置に既存4フィルター、長い名前の折返し、ルートの固定タブを反映する。

[写真選択と共有のHTML](loupe-select-share.html)は元S03/T01/T08/S09と必要な変更を並べる。写真選択と共有シートを適用対象とし、選択中も元の右下の雲印（24×20dp、未選択5dp・選択14dp）を保持する。共有準備は中止操作だけで取り消し、Backでは閉じない現行契約を反映している。

## 個別写真表示

[元のS08を保持した写真表示HTML](loupe-photo-viewer.html)をMainの通常/比較・結果からの閲覧とTagActivityの写真閲覧に適用する。黒背景、日時、写真のfit、64dp一列dockを保ち、専用Back削除と既存の拡大・共有・タグ・情報の条件を反映する。HTML確認とAndroid実機の適用完了は区別する。
- 2026-10-26の追加訂正：既存の倍率2.5に合わせて仕様と `scale(2.5)` を更新。元S08と既存操作の変更はなく、ローカル表示と拡大切替を確認。

## 連写詳細・比較の全体配置（Issue #8）

[loupe-burst-compare.html](loupe-burst-compare.html)は2026-10-27に取得したClaudeの補足設計。左は元S04/W01/W02とS05/T06、右は#5/6/7/9/10/17の採用済み変更を適用する。元の大きな写真、サムネイル列、主/副操作を保ち、#6の順位例と枠/チェックを置換しない。常時1位保存確認、読込中の連写操作、履歴が残る間の連続取消、前/次の循環、連写全写真の共有を保持する。

ローカルで元画面との配置を確認し、読込中でも1位操作が確認を開くこと、比較3回目から2回続けて取り消して1回目へ戻ること、完全な方式のAX説明を確認。AndroidのSPは非線形のためHTMLの線形倍率との実寸差を区別する。Androidへの適用と実機比較はこのHTML取得だけでは完了しない。

## その他メニュー（Issue #8）

[loupe-more-menu.html](loupe-more-menu.html)は元の18その他と、photoMenu/galleryMenuの既存項目・遷移を保持した縦画面の補足設計。見出し・矢印・全幅の閉じるを元に揃え、window幅・角20dp・高さ48%上限は#13を維持する。元アーティファクトを直接表示して確認し、単体HTMLの表示と2段の遷移を確認。写真、キーボード高さと下端距離は合成例。APK122で縦320/400dp・文字1/1.3の実機比較と独立UI評価を確認。取消・2段遷移・末尾到達、初回/再表示の寸法安定と低い領域での操作到達も確認。実キーボード・指スワイプ・音声は未検査。

## Issue #46 タグConfidence

[loupe-tag-confidence.html](loupe-tag-confidence.html) はタグ詳細と基準を教える画面の現行参照。ClaudeのLoupe UIブラッシュアップから出力し、ブラウザ表示と閾値変更・基準を教える画面への遷移を確認した。上部の説明文1か所を、判断後のキャッシュによる再学習と明示的な写真解析を区別する文へ補正した。写真とスコアは合成例。Android実装の検証状況はissue #46へ記録する。

## Issue #48 タグ解析の進捗

[Claudeの補足HTML](loupe-tag-analysis-progress.html)を解析中の印と解析済み/対象枚数の視認性の参照とする。320dp・文字1.3倍の表示と20354/32541の桁数をブラウザで確認した。周辺配置・状態遷移は模擬で、採用範囲は[契約](../tag-analysis-progress-contract.md)に限定する。Androidの配置・中止・完了・失敗の仕様は既存どおり。

## Issue #51 個別写真の編集入口

[Claudeの編集ボタン設計](loupe-photo-edit-toolbar.html)と[契約](../photo-edit-contract.md)。元S08と既存写真表示に編集を追加した比較HTML。通常は共有の直前、Tagはタグ操作の直前。24dpの鉛筆・12sp/16sp・最低64dp高、必要幅48dp以上。幅が足りなければceil(n/2)列の二段、二段目を中央へ配置する。400ms後の同セル中止・失敗印と読み上げを含む。320dpでMainの4+3、文字拡大時のTagと編集開始/中止/失敗の合成操作をローカル表示で確認。元S08からの既存変更以外の配置を保持している。実機のフォント幅・編集アプリ・保存動作は別途検証する。

## 実験的なRAW現像（Issue #53）

[初期の比較HTML](loupe-raw-editor.html)は元S08を含む比較資料として保持する。基本配置は下記の重ね合わせ案、ガイドと初期化は最新のガイド案を参照する。[契約](../raw-editor-contract.md)を参照。合成プレビューは実RAWの画質・速度を示さない。

[Claudeの重ね合わせ案](loupe-raw-editor-overlay.html)は7タブと写真上の2D操作を収録する。400dpでは操作面328dp、右・下の片軸帯48dp、間隔8dp。この旧案ではカーソル以外の2D要素は操作中も非表示だが、その条件はIssue #56の下記ガイド案で置き換える。写真外の補助行にMAX・かすみ・コントラスト・モノクロを配置し、タブは各幅約57dpで横スクロールなし。Monoは色相260・彩度11・輝度+2で保存対象、MAXと補正前比較は表示専用。合成写真と状態の模擬であり、実際のRAW処理やAndroid実機の確認を示さない。Claude内のプレビューで400×844dp・文字1倍のWB待機とカラーのモノクロ切替を表示確認し、元S08も直接表示した。描画済みClaude原本とAndroidのWB実機キャプチャを独立比較済み。通常windowと高さ640dp window/文字1.3倍で7タブ固定配置・ラベル欠けなし・透明面・Mono・ピンチを合成実機検査済み。実TalkBack音声、全状態の見た目、ローカルHTMLの描画は未確認。

[ガイドと初期化の案](loupe-raw-editor-guides.html)は原寸処理の参照を引き継ぎ、操作中の面と右下の片軸レールを半透明で示し、写真上に数値や軸名は表示しない。面の枠は1dp・白35%と黒25%、格子は白12%、基準線は白32%、レールは2dp・白35%。表示開始120ms、指を離して3秒後に400msで消え、カーソルは残る。面のダブルタップで両軸、各帯と補助行のスライダーは自分の値だけ初期化する。Claude内プレビューで元S08、WBのdrag・hidden・右軸resetの模擬、原寸状態の選択肢を確認した。HTMLは合成表示であり、実機操作・TalkBack・実写真上の視認性は未確認。

[原寸処理の状態案](loupe-raw-editor-full-resolution.html)は、既存の配置を保ち、原寸準備・拡大・保存待ち・失敗と再試行を合成状態で示す。Claude内プレビューで元S08の存在と現像画面の失敗状態を確認した。HTMLの精細表示と保存は模擬で、実RAW処理やAndroid実機での動作は未確認。

[WBの参照比較](loupe-raw-editor-overlay-comparison.png)と[7タブの実機キャプチャ](loupe-raw-editor-overlay-matrix.png)は合成画像のみ。Androidは通常window・文字1.0倍と高さ640dp window・文字1.3倍。PixelCopyで選択中のタブの画素を確認して撮影し、独立レビューで14画面と選択線の修正を確認した。参照と実機のviewport・OS領域は異なり、全画素の完全一致やdrag中の外観・音声確認を示さない。


Issue #56のAndroid実機確認では、通常window/文字1.0と高さ640dp/文字1.3のガイド表示・離した直後・fade途中・消去を撮影した。[参照と実機の表示中](loupe56-comparison-drag.png)、[消去後](loupe56-comparison-hidden.png)、[4状態](loupe56-states-matrix.png)、[写真とカーソルの画素を確認した7タブ](loupe56-tabs-confirmed.png)を独立評価し、静止画の必須修正なし。参照400×844と実機1200×2608/density3はOS領域とviewportが異なり、合成素材・代替フォント差もあるため、全画素の厳密一致を示さない。実指/TalkBack音声/実写真での視認性は未確認。確認済みと未完了は[契約](../raw-editor-guides-contract.md)を参照。

Issue #55の[4状態の参照・実機比較](loupe55-fullstates-comparison.png)は、完成したガイドHTMLを変えずにClaudeが400×844で描画した準備中・保存待ち・失敗・完了と、Androidの合成実Windowを並べたもの。独立レビューでは静止画範囲に必須修正なし。保存待ちの暗い表現は中止可能と分かりにくい懸念があり、警告印の位置に軽微差がある。実操作の中止・失敗再試行・原寸保存は別の実機検査で確認した。原寸状態の確認済み/未確認は[契約](../raw-full-resolution-contract.md)を参照。

## カーソルをつかむ現像操作（Issue #59）

[現在の参照HTML](loupe-raw-editor-cursor-navigation.html)は面28dp・右/下24dpの半透明カーソル3点を常時表示し、48dpの操作域をつかんだ相対移動だけで値を変える。空白からのドラッグはパン、ダブルタップは原寸100%→150%→fit。カーソル上のダブルタップ初期化、7タブ、原寸準備/待ち/失敗の状態を保持する。[操作中](loupe59-reference-drag.png)と[消去後](loupe59-reference-hidden.png)はClaudeが完成HTMLから書き出した400×844・文字1.0・WB+60/+60の合成画面。代替フォント、1dp=1px。元ガイドHTMLは不変。Androidへ反映し、[操作中](loupe59-comparison-drag.png)と[消去後](loupe59-comparison-hidden.png)を独立比較済み。比較の根拠と残る差は[契約](../raw-editor-cursor-contract.md)を参照。

同contentの追加参照は[操作中](loupe59-reference-content-drag.png)と[消去後](loupe59-reference-content-hidden.png)。HTMLの実行時高さ805⅓dp/DPR3/文字1.0で描き、ブラウザ丸めで1200×2415px、実機アプリ領域は1200×2416px。OS領域を除き同じ物理幅で比較する。

## 現像の下部タブ（Issue #61）

[loupe-raw-editor-footer-v2.html](loupe-raw-editor-footer-v2.html)が現行の下部配置参照。旧footer.htmlは基準版。上56dp・写真は残り・下80dp。補助行と拡大ボタンを削除し、必要4タブだけ高さ48dp/下8dpの補助操作を写真へ重ねる。7タブでviewportと2DUI座標を固定。参照PNG: [WB](loupe61-reference-wb.png)、[トーン](loupe61-reference-tone.png)。400×(2416/3)dp・密度3・文字1.0・初期値・fit、実出力1200×2415px（丸め1px差）。短いcontentの面サイズはmin(幅−72,写真高さ−176)で契約と統一。[短い参照](loupe61-v2-reference-short.png)は400×576dp/密度3/文字1.3、実寸1200×1728px。通常PNGはv1/v2で同一ハッシュを確認。Android実装・通常/短Windowの実機検査と独立比較を完了。[実測短Windowの参照](loupe61-v2-reference-short640.png)はcontent400×640dp/font1.3、実寸1200×1920px。[通常WB比較](loupe61-comparison-wb.png)・[トーン比較](loupe61-comparison-tone.png)・[短いWB比較](loupe61-comparison-short.png)（左参照/右実機）。576dpの参照は設計上の参考寸法。字体・合成注記/斜線・既存ヘッダー・chip幅・線端には差があり、全画素一致とは扱わない。

## 多点トーンカーブ（Issue #63）

[Claude完成参照](loupe-tone-curve.html)。トーンだけ写真領域の下端8dp、左右8dpに高さ264dpの半透明パネルを重ねる。内側8dp、行間4dp。上段48dpは明度/RGB/R/G/Bと選択カーブのリセット、中段144dpはグラフ（パネル内幅−176dp）と右152dpの入力/出力・Undo/Redo/削除、下段48dpはロールオフ。グラフの内側8dp、線2dp、点は5dp/選択7dp、操作域48dp。既存7タブ/写真viewport/他6タブ/比較/保存/準備状態は維持する。

[既定](loupe63-reference-default.png)、[5点S字](loupe63-reference-scurve.png)は400×805⅓dp・密度3・文字1.0、[短い画面](loupe63-reference-short640.png)は400×640dp・文字1.3。合成写真のみ。Claudeの画面で元footer-v2と修正版を確認し、ダウンロードと保存先のSHA一致を確認。プレビュー写真の色はSVG近似なのでAndroidの画質比較の基準にはしない。点・線・数値・パネル配置を比較する。数値契約は[tone-curve-contract](../tone-curve-contract.md)。Android実装後、400dp通常/font1.0、400×640dp/font1.3、320×640dp/font1.3の端末captureと操作回帰を確認。[通常比較](loupe63-comparison-normal.png)・[短い比較](loupe63-comparison-short.png)は左参照/右実機。独立UI評価を反映し、選択端点には外側リングを追加した。再評価で対応必須の不一致・切れなし。[検証範囲](../tone-curve-verification.md)を参照。

## CNT-like研究用ルック（Issue #65）

[Claude完成参照](loupe-cnt-look-layout-fixed.html)は既存7タブ/写真viewportを維持し、ヘッダーの現像▾から高さmin(content×.46,384dp)の下付きスクロールシートを開く。地#1C1C1F、上角丸28dp、背景dimなし、操作48dp以上。プリセット、独立ON/OFF、cube読込/状態/強度、粒子/暗部寄り/seed、基準プリセットへのリセットを持つ。トーンパネルの3行目は現像/ルック切替+現像rolloffまたはlook toneスイッチとし、高さ264dpを維持する。

[通常参照](loupe65-final-reference-normal.png)はcontent400×805⅓dp/density3/font1.0、[短画面](loupe65-final-reference-short.png)は400×640dp/font1.3。完成HTMLの数値契約を実装のCNT-like値（明度6点、組込み33³ LUT、粒子18/80/seed65、Neutral rolloff0、cube32MiB/行4096）へ整合した。色はHTML近似で、写真・粒子の画質基準には使わない。Android実装と操作/独立比較は別途確認する。[画像処理契約](../cnt-look-contract.md)を参照。

### #65の比較条件

現行の比較基準はClaude作成の[レイアウト修正版](loupe-cnt-look-layout-fixed.html)と[通常PNG](loupe65-layout-fixed-reference-normal.png)・[短画面PNG](loupe65-layout-fixed-reference-short.png)。シートの直下の子を`flex-shrink:0`とし、ハンドル4dpと区切り線1dp、状態の行を縮めず、シート内でスクロールする。プリセット・動作・シート高の契約は維持する。元の`loupe-cnt-look-final.html`とPNGも保管するが、その版のハンドル等はflex縮小で高さ0になるため現行の寸法比較には使わない。

実機UIの通常400dp/font1.0と短画面400×640dp/font1.3の操作検査は通過した。[通常比較](loupe65-comparison-normal.png)と[短画面比較](loupe65-comparison-short.png)は左が参照、右が実機。独立評価では追加修正が必要な重大UI/UX差なし。[通常座標](loupe65-capture-normal.json)・[短画面座標](loupe65-capture-short.json)に物理Window座標とcropを残す。短画面は制約付きActivityと独立Dialogの条件を合わせるためtest-onlyでWindow.yを296px変更し、シート全883pxをcontent内へ配置した結果であり、実際の短い端末そのものではない。全画面は補正しない。合成写真の入力・現像条件、HTMLのSVGノイズとGPU粒子、代替フォントの違いを含むため、UI参照から画質一致を判定しない。字形・改行・行間等の小差、実指/実IME/TalkBackの未確認を区別する。

## CNT-likeの校正と周辺光量（Issue #67）

[現在のClaude参照](loupe-cnt-quality-final.html)はLUTと粒子の間へ独立した周辺光量のスイッチと強度0〜100を追加する。既存のシート高、48dp操作域、スクロール、Custom・外部LUT・曲線編集・保存の契約を維持する。CNT-likeは測定した33³カラーLUT、identity曲線、周辺光量オン100、粒子オフ（値18/80/seed65を保持）。注記はGalleryのCNTを参照した調整用ルックであることを伝える。

[通常・上端](loupe67-final-reference-normal.png)、[短画面・上端](loupe67-final-reference-short.png)、[通常・周辺光量](loupe67-final-reference-normal-scrolled.png)、[短画面・周辺光量](loupe67-final-reference-short-scrolled.png)は密度3、content400×805⅓dp/font1.0または400×640dp/font1.3。HTMLと保存先のSHA一致を確認し、周辺光量の参照PNGを表示確認した。周辺光量の行はシート内スクロール406dp/439dpの位置で、スイッチと強度を両方表示する。

参照HTMLには測定LUTが入っておらず、色は無変換、周辺光量は放射状の近似。HTMLだけに必要な未同梱の説明は実装画面へ移さない。AndroidのLUT状態の行は実際の測定LUT名と33³を表示するため、説明行の長さとスクロール量はデータの条件に合わせて比較する。実写真の画質はHTMLと比較せず、同じ入力から得たGallery CNT参照と比較する。[校正と評価](../cnt-quality.md)を参照。

このHTMLの完成はUI設計の完成を示す。端末上の操作・保存・新しい周辺光量の独立評価はissue #67の結果で別に確認する。古い#65参照は旧プリセットの記録として保持する。

## Issue #69 Saved viewer Looks

[Claude completed HTML](loupe-viewer-look-presets.html), [contract](../viewer-look-presets.md), and density3 reference PNGs [viewer normal](loupe69-reference-viewer-normal.png), [viewer short](loupe69-reference-viewer-short.png), [save dialog normal](loupe69-reference-dialog-normal.png), [save dialog short](loupe69-reference-dialog-short.png). Preserve original S08 and existing dock ordering. New top-right Look chip (right8/top8,48dp,13sp) and top-left Before; narrow320dp/font1.3 date moves down to avoid overlap. Non-dimming bottom selector min(contentHeight*.46,384dp), scrollable56dp choice rows/48dp deletion, applied/prepare/error/retry and separate JPEG save/cancel/outcome. Editor Look sheet gets preset-save above reset and Look-only name dialog. Saving navigation waits for actual export/cancellation outcome. References400x805.333/font1 and400x640/font1.3, synthetic data only; HTML LUT is illustrative, not rendering evidence. Android normal/font1 and constrained400×640dp/font1.3 operation/export checks passed. Independent comparison found no blocking UI/UX mismatch in captured states; small position/text-wrap/fixture-order differences remain, not pixel-exact equality. See [normal viewer](loupe69-comparison-viewer-normal.png), [short viewer](loupe69-comparison-viewer-short.png), [normal dialog](loupe69-comparison-dialog-normal.png), [short dialog](loupe69-comparison-dialog-short.png). Capture JSON records content/window conditions. Synthetic image colors are not photographic-quality evidence; real IME/finger/TalkBack and a physical short device remain unverified.

## Issue #84 連写内の一括1位指定

[Claude完成HTML](loupe-burst-bulk-winners.html)を参照する。元S03を直接開いて依頼し、ダウンロードのSHA-256一致と320dp/文字1.0・1.3の選択・確認・失敗・未確定の表示を確認した。既存の選択画面を維持し、共有設定とソート/共有の間へ全幅48dp・角丸24・線#6B6B70の★＋操作を追加する。確認シートには40dpの対象写真、順位保持/置換の説明、決定と取消を表示する。対象は明示選択した同じ連写の写真に限る。[契約](../burst-bulk-winners.md)を正とし、HTMLの「比較の記録はそのまま」は確定順位グループの保持を意味する。保存形式は既存の互換journalへ再構成する。参照HTMLの合成検査とAndroid上の検査は別に記録する。

[選択画面の比較](loupe84-comparison-selection-320.png)と[確認画面の比較](loupe84-comparison-confirm-320.png)は左が参照、右が実機。合成12枚・対象2枚、320dp/文字1.0/density3.25。OS領域を[撮影条件](loupe84-capture-320.json)に従って上140px・下52px除き、参照の高さも2478pxに合わせた。画像は実指・音声・物理ディスプレイの応答時間を証明しない。修正後の[選択](loupe84-final-selection-320.png)・[確認](loupe84-final-confirm-320.png)・[失敗](loupe84-final-failure-320.png)・[成功](loupe84-final-success-320.png)は0.28.4(155)、320dp/文字1.3倍の独立UI評価に使用した。操作の可読性と下部非欠損は合格。通常369dpでも文字1.0/1.3倍の合成操作を確認した。

## Issue #82 連写の同率1位追加

[Claudeの単体HTML](loupe-burst-co-winners.html)を連写詳細の同率追加操作の基準とする。既存の「1位にする」から、確定済みの非1位写真を同率1位へ追加する。既存の単独指定も保持し、保存後は同じ連写・写真に留まる。順位印と読み上げは全1位を含む。原本と共有の一時選択は変更しない。対象契約は[同率1位指定](../burst-co-winners.md)。HTMLの合成動作とAndroid実機検証は別とする。

## 写真別の共有先バッジ（Issue #85）

[Claude完成版HTML](loupe-share-app-badges.html)。SHA-256 `3c9b310df185bb83d2bcb25bc108009ee78b2b3a24375f009174e1a7d51e950a`。写真一覧・選択・連写・ビューアの右下の札、情報シートの全履歴、保存失敗時の再保存を扱う。寸法と保存の契約は[共有先履歴](../share-app-history.md)を参照する。

元S03をClaude上で直接開いて依頼し、取得ファイルのハッシュとPC上の描画を確認した。左右の元ボードはS02/S03/S04/S08/S09で、S03/S04には採用済み変更より古い操作も含まれる。全差分を本issueの変更とせず、共有履歴以外は採用済みの操作を保持する。合成写真・ダミーアプリによる設計。[一覧比較](loupe85-final-grid-320.png)と[連写比較](loupe85-final-burst-320.png)は0.28.4(155)、320dp/文字1.3倍。369dp/文字1.0倍と合わせ、共有札の位置と既存の印との非干渉を独立評価した。Androidの直接共有/共有先選択はテスト専用受信アプリで確認した。実写真、外部サービスへの投稿、実指、TalkBack音声は未確認。400dpは端末の物理幅を超えるため右端の視覚評価には使わない。履歴保存と検査の詳細はissue #85の工程記録を参照する。
