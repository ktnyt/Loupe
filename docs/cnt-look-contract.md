# CNT-like look PoC

## 目的と範囲

Leica Contemporaryに近づけるため、写真を比較しながら曲線・LUT・周辺光量・粒子を独立に差し替えられる基盤を既存の現像へ追加する。CNT-likeは同一入力にGalleryのCNTを適用した参照から校正する。撮影時CNTとの等価性や、すべての写真で同等の画質となることは保証しない。合意済み要件はissue #65・#67。以下の契約は依頼の範囲で具体化したもの。

## 既存経路の調査

基点 `66bbfd87c58317b8ccf94a8e92294003dde7b237`。

- `RawEditSource` はcontent URIをprivate cacheへ上限256MiBで複写。JPEGはAndroid BitmapFactory/BitmapRegionDecoder、RAWは既存LibRaw 0.22.3。寸法上限16384、80MP。現行の静止画入力はRAW/JPEG。新しいRAWデコーダーは追加しない。
- RAWはcamera WB、auto bright無効、gamma=(1,1)、sRGB primaries、RGB16。初期プレビューのhalf-size現像と原寸現像ではデモザイク条件が異なるため、入力自体の差がある。
- JPEGはARGB_8888としてデコードしてsRGB逆伝達関数を適用。JPEGの元の8bit以上の精度は復元しない。EXIF orientationを適用する。原寸cacheはbig-endian linear RGB16 PPM。
- GPU入力はRGBA32F。ES3/EGL pbuffer、float32曲線テーブル8193点、最終FBOだけRGBA8。最終ディザ後のRGBA8 readbackをBitmap/原寸タイル保存に使う。保存は既存libjpeg-turbo、8bit JPEGの別ファイル。HDR、広色域出力、16bit画像書き出しは対象外。
- 現行トーンは線形WB/露出・質感→Oklab L基本階調/肩/明度カーブ→符号化sRGBのRGB/R/G/Bカーブ→彩度→Oklab色域圧縮→sRGB符号化。2〜32点の単調PCHIP、入力最小間隔1/128、出力非減少、領域外端点保持。局所マスクはない。
- `RawEditRenderer` を初期プレビューと `RawEditFullSession` の原寸詳細/タイル保存の双方から使用する。既存curve LUTは1D曲線の表であり3D色LUTとは別。

## 機能・要件・契約

### 調整と原本保全

- 不変条件: 通常起動では追加look無効。既存の現像既定値を変更しない。既存の原本・保存済みJPEGを書き換えない。
- Neutralは `RawEditState.neutral()`（既存rolloff=0を含む全現像中立+look無効）。任意現像設定のまま追加lookだけ無効にする操作とは区別する。Neutralの画素は同解像度のBeforeと最終量子化/丸め程度の誤差に収める。JPEG再圧縮のバイト一致は要求しない。
- CNT-likeは追加lookだけを置換する。Customは手動設定。パラメータはimmutable、render request/export開始時の状態を共有し、調整中に保存中の状態を変えない。
- 履歴とequalsはlookを含む。従来のfloat snapshotは従来現像だけを保持するため、Activity再生成時はlookの別保存も必要。LUTをBundleへ直接格納しない。

### 処理順序と色管理

1. 既存RAW現像/JPEG decode→linear sRGB。
2. 既存の現像（露出/質感/基本階調/既存曲線/彩度/色域圧縮）。
3. 表示参照の符号化sRGBに、追加lookのOklab L曲線→RGB合成→R/G/B曲線。
4. `.cube` 3D LUT（符号化sRGB入出力）。
5. 全画像の正規化座標による周辺光量（ハイライト保護付き）。
6. seed付き共通チャンネル粒子。
7. 既存の出力ディザ、clamp、RGBA8/JPEG。

追加lookはすでに表示参照へ変換された画像のカラーグレーディングなので、独自のHDR shoulderやRAWトーンマッピングを追加しない。Oklab曲線に入るときだけsRGBをlinearへ戻し、Oklab→色域圧縮→sRGBへ戻す。LUTの前後で誤ってガンマを二重適用しない。中間画像の8bit化は追加しない。

### 曲線

既存 `RawEditCurve` のPCHIP契約を再利用する。5曲線を追加lookの値として独立保持し、toneスイッチは5曲線全体をbypassする。identity曲線は数学的な往復変換も省く。CNT-likeの曲線は5本ともidentity。測定した色・階調変換を組込みLUTに保持し、曲線による二重補正を避ける。手動の多点調整は独立して使用できる。

### LUT

- 2〜65の3Dサイズ（17³/33³/65³を含む）、赤最速、TITLE、DOMAIN_MIN/MAX。省略DOMAINは[0,1]。領域外入力は端の座標へclamp。
- 明示的に符号化sRGB用のLUTだけを受け入れる。cube形式に色空間の保証はないのでLog/HDR/P3用のLUTを自動判別しない。
- 正確なN³要素、有限値、正の有限DOMAIN幅、directiveの重複/未知/1D shaperを検証。テキスト32MiB上限、1行4096字上限、出力絶対値65504上限。範囲外の有限LUT出力は粒子後の最終出力でclampする。
- CPUとGPUの双方が三線形補間。GPUはRGBA32F・NEARESTから8頂点をfetchして手動補間し、float linear filter拡張への依存を避ける。
- strength=0はbypass、1は完全適用。identity表は同じ入力の変化をfloat誤差内に収める。読み込み失敗時に有効LUTを失わない。

### 周辺光量

- ON/OFFと強度0〜1をLUT・粒子と別に保持する。外部LUTを読み込んでも変更しない。
- LUT後の符号化RGBへ共通倍率を掛ける。明るさの判定はclampした符号化RGBの加重値であり、線形輝度ではない。測定モデルは[校正データ](calibration/gallery-cnt-v1.json)を正とする。
- 座標は向きを適用した画像全体のu/v。crop・拡大・原寸タイルで中心を取り直さない。CPU参照は`applyAt`で粒子座標とu/vを明示する。旧`apply`は周辺光量が有効なら誤用を防ぐため失敗する。
- ハイライト保護の計算用のclampと、画素の最終clampを分ける。LUTの範囲外出力を周辺光量の前に切り捨てない。

### グレイン

- seed、強度、暗部寄りの重みを保持し、RGBに同一の加算値を使う。ランダムRGBノイズは加えない。色チャンネルがclampされる境界では色差が生じうる。
- uint hashの連続場（3一様値の和+滑らかな空間補間）を共通2048長辺基準で評価。固定seed/同座標/同入力は決定的。明部の量を減らせる。最暗/最明部で減衰してclipping biasを抑える。
- 同じ正規化元画像位置に同じノイズ場を使い、原寸タイル境界でseedや座標をリセットしない。粒径を写真全体のサイズに結び付け、プレビューから原寸保存へ粒径が突然変わるのを抑える。
- 縮小は非線形処理との順序が異なり、JPEG圧縮も粒子を変える。任意倍率で画素一致とはしない。平坦画像と実写真の統計・目視を分けて確認する。

## UI

Claude完成参照 `docs/design/loupe-cnt-quality-final.html` をUIの基準とする。現像▾の48dp入口、下付きスクロールシート（高さmin(content×.46,384dp)、地#1C1C1F、上角丸28dp、dimなし）、プリセット/独立ON/OFF/外部cube/強度/周辺光量/粒子/暗部/seed/リセットを持つ。既存ワンタップBefore/After、原寸保存を利用。カーブパネルは下行を現像/ルック切替とし264dpを保つ。左右分割は今回省略。

Customの調整と最後に読み込んだ外部LUTを別々に保持する。既存現像の既定補正が残る初期状態は「追加なし」とし、Neutralを選択済みと表示しない。Neutral選択は現像も中立にする操作、リセットは追加lookだけを戻す操作として分ける。Customの基準プリセットはimmutable lookのbasePresetとしてUndo/Redo・binaryに含め、プリセット往復で書き換えない。look binaryは6503。6501はbase0、6501/6502は周辺光量を無効にして既存値を読み込み、既存の画素を保つ。CNT-likeは既存現像を変えず組込みLUTを使い、Neutralは確認後に既存現像を含め中立へ戻す。Neutral後もCustomと外部LUTは保持する。外部入力の失敗/中断は有効なrecipeを変えず、再読込を提示する。古い非同期結果はrequest世代で破棄する。Activity停止時は読み込みを中断し、復元ではURIを再読せずprivate recipeを読む。Store v2はcurrent/saved/custom/外部cubeを最大13,214,784 bytesで原子的に保存し、Bundleにはtokenだけを入れる。同期保存時間とimport完了時間をmsでログへ残す。

## 検証

CPU自動テスト、GPU実機テスト、操作検査、実写真の主観検査を区別する。未確認の必須検証が残る間はissueを閉じずマージしない。実測結果はissueと検証文書へ記録する。

参考: [Khronos ES3 registry](https://registry.khronos.org/OpenGL/specs/es/3.0/) の形式/filterabilityを踏まえて手動補間を選ぶ。
