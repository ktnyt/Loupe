# プレビュー優先の原寸現像

対象: Issue #55。基点7c5bab8。#53の長辺2048px制限を、原寸の観察と別保存に必要な範囲で置き換える。実装とhost/ビルド検証済み。Cold Reviewは保存の公開/取消競合を修正して再確認し、Security Reviewとともに修正必須指摘なし。原寸機能のAndroid実機検証は未実施。

## 目的と機能

主題を拡大して元の画素のディテールを確認しつつ、軽量プレビューで現像を続けられること。原寸RAWを準備し、現在見ている範囲を同じ編集値で描画し、原寸JPEGを別保存する。

利用者指定は、プレビューへの計算の優先、バックグラウンドの原寸準備、元解像度による拡大表示。原寸JPEG別保存は直前の解像度に関する相談と合わせた具体化。UIは透明2DUI・7タブ・Monoを保持する。

## 原本と原寸データの契約

- 原本はcontent URIで読み取り専用。セッションごとの256MiB以下のprivate入力コピーを保持する。個人写真・URI・パス・例外本文は外部やログへ出さない。
- 初回プレビューは現在のhalf-size/長辺2048px以下を利用。原寸準備は別の低優先度workerで、一つのLibRaw処理だけ実行する。新しい編集値では再デモザイクしない。
- 原寸はLibRawのhalf_size=0、撮影時WB、auto-brightなし、linear sRGB16、user_qual=0で展開する。有効画像寸法と向きはprocess後のget_mem_image_formatと既存writerに従い、センサー外縁のraw寸法で代替しない。
- 未変更のLibRaw PPM/PGM16 writerでprivate一時キャッシュを出力する。Javaのfull-size FloatRGBAやAndroid Bitmap、full-size GL textureは作らない。LibRaw内部の全画像bufferは残るため、原寸準備のメモリがタイルサイズに制限されるとは扱わない。
- P5/P6・16bit・寸法（各16384以下、80MP以下）・payloadのbyte長・close完了・セッション取消を検証してからキャッシュを公開する。未完成キャッシュを表示/保存に使わない。ネイティブ処理の失敗は原寸失敗とし、縮小結果を原寸と呼ばない。
- 原寸準備完了後、小プレビューも原寸キャッシュから作る。half-size初期像との画質差を許容し、その置換でズーム/パン/編集値を変更しない。
- background/終了/写真変更で取消し、所有workerの終了後にそのセッションのinput/cacheだけを削除する。新セッションのdecodeは前のdecodeが資源を解放してから開始する。native取消はprogress callbackによる協調的取消で、即時停止保証ではない。

## 描画と優先制御の契約

- GL資源は単一GL workerが所有する。要求は軽量preview、現在viewportのdetail、export tileの順に扱う。処理済みpreviewの提示を最新入力との一致で飢餓にしない。
- 連続操作中はdetailを開始せず、入力後の短い静止期間にviewportのdetailを進める。少なくとも各tile境界で最新previewと取消を再確認する。GLの単一draw/readback中の中断は保証しない。
- 原寸detailは小さいtileから画面相当の領域へ組み立てる。軽量previewの上に描き、view内の全画像のfit/zoom/pan座標を変えない。1:1へ到達できる最大倍率を有効寸法とviewサイズから計算する。
- preview・detail・exportは同じRawEditState/shaderを使う。空間フィルターは長辺min(2048,原寸長辺)の基準格子で定義し、原寸でも同じ画像内の範囲を参照する。各tileは最大radiusのceil+1pxのhaloを持ち、内部境界でclampせず実画像の端だけclampする。
- detailのcommitにはセッション、編集revision、viewport revision、before/MAXの全一致を必要とする。編集・比較・MAX・zoom/panが変わればdetailを直ちに無効化し、古い精細像を後から重ねない。
- previewと原寸はdownsample/非線形処理の順序が異なるため完全な画素一致を保証しない。同じ解像度でのtiled/untiledはJPEG前に許容差で比較する。

## 原寸保存の契約

- 保存開始時の編集値をsnapshotし、確認画面は原寸キャッシュの有効寸法を使う。原寸準備中の保存要求は中止可能に待ち、2048pxへ黙ってfallbackしない。
- pinned libjpeg-turbo 3.2.1（公式source/digestを確認）による一つのscanline encoderへ、短いRGB8 stripを順に渡す。tileごとのJPEGを結合しない。全画像Bitmapは作らない。
- 新規MediaStore pending URIだけへ書き、全行のJPEG終了・flush/sync後に公開する。中止/失敗は今回の未完成出力だけ削除する。EXIF個人情報は継承せず、画素の向きは適用済み。
- 原寸準備待ちのBackは待ちを取り消し、編集中の画面へ戻る。保存中Backは公開確定前なら取消し、cleanup後に編集へ戻る。公開確定と取消受付はatomicな排他状態遷移で決め、公開が先なら完了/失敗を一度だけ通知してUIを復帰させる。background/終了も同じ境界で取消する。終了後にダイアログlistenerから保存を開始しない。process kill時のfinally実行は保証しない。

## 検証と受入

- 原寸checker/線/impulse/色/非整倍寸法で原寸由来ディテール、tiled/untiled境界とedge、向き、same-stateを確認する。
- 遅延タスクでsession/edit/viewport/modeの古いdetailを排除し、decode/IO/render/export各段の中止とbackground復帰を検査する。
- GR DNG/OM ORFを端末で原寸準備・拡大・編集・元寸法JPEG別保存し、元SHA不変と当該テスト生成物だけのcleanupを確認する。
- 初回preview、準備と競合中、準備後、拡大、保存を分離し、input→frame commit、更新数、detail表示、load/save、PSS/native/GL pressureを実測する。frame commitを走査完了と呼ばない。
- 変更前の同じ入力/gestureと比較し、原寸準備が連続操作を止める場合は未達として原因と対策を検証する。hard realtimeや全形式/全端末対応は保証しない。
- 独立Cold/Security Review、UI変更部分はClaude参照と独立UI評価を行う。

## 方式の根拠と工程

26MPの全画像FloatRGBAは約397MiB、GL RGBA16Fは約198MiBで、全画像readback/Bitmapを重ねる方式は採らない。linear RGB16 cacheは約149MiBのdisk。GL tileとRGB stripはboundedに保つ。LibRaw内部peakと最長取消gapは実機で未測定。

LibRawのPPM writerとlinear設定を利用して向き/色変換の独自実装を避ける。JPEGはAndroid private libraryへリンクせず、公式libjpeg-turbo 3.2.1 sourceとlicensesをpinして同梱する。新しい責務を追加する機能実装であり、必須の先行リファクタリングは現時点では不要。既存UI配置は保持し、原寸準備・倍率・保存確認の仕様だけClaude参照を更新する。


## 現在の検証状況

hostでは実GR DNGの有効寸法6224×4160、実OM ORFの6132×8172について、初回metadataと原寸準備の寸法一致、キャッシュ整合・取消・cleanup、JPEGの分割行入力・寸法・境界・FD所有の検査を通過。合成原寸Sourceと状態モデル、性能ログparser7件のhost検査も通過した。APK4種とlint2種をビルド済み。

Android用のSource/Tile/Session検査、公開/取消を固定するラッチ検査、準備待ちの編集と保存・中止を実Activityで確認する合成検査を用意している。端末への新APK導入・これらの実行・実写真の拡大表示と原寸保存・準備中/後の性能とメモリ・変更後UIの独立比較は未実施。#53の2048px実機検証を原寸機能の検証として扱わない。
