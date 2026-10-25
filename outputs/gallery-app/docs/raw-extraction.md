# RAW埋め込みJPEG抽出

## 目的と契約

撮影時にカメラが生成したJPEGをRAWから取り出し、閲覧・比較・JPEG共有に使用する。センサーデータの現像や原本への書込みは行わない。

- 拡張子は探索対象を決めるために使う。実際の抽出可否はファイル内のJPEG構造で判定する。同じ拡張子でも機種・設定で異なる。
- 連続したJPEGストリームをファイル全体から探索し、画素数が最大のものを選ぶ。同じ画素数なら先に見つけたものを選ぶ。
- SOI、セグメント長、8-bit SOF0/SOF1/SOF2、SOS、エントロピー領域のstuffing/restart、EOIを確認する。progressive JPEGにも対応する。SOF3などセンサー圧縮用のlossless JPEGを画像プレビューとして返さない。
- JPEG終端より後ろのパディングは返さない。通常は原本内の範囲とバイト一致する。Epson/Minoltaで既知の先頭バイト異常がある場合のみ、メーカー情報またはMRW識別子と後続DQTを確認し、返却コピーの1バイト目をFFに補正する。`Result.repairedHeader`で補正の有無を取得できる。
- TIFF/ORF/RW2と抽出JPEG内のEXIFから、取得できる向き・機種・撮影日時を読む。メーカー独自メタデータを網羅するものではない。取得できない機種名は「不明なカメラ」、日時は0、向きは1。
- 読取りは64 KiB単位。JPEG全体を確保するのは実際に返却するときのみ。既存の上限（1画像32 MiB、1億2000万画素）を維持する。中断、範囲外参照、過剰な再解析を拒否する。

## 調査と探索対象

[ExifToolの形式定義](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool.pm)、[LibRawの対応機種](https://www.libraw.org/supported-cameras)、[darktableの入力形式一覧](https://docs.darktable.org/usermanual/4.2/es/overview/supported-file-formats/)、以下の形式別実装を参照した。外部コードをアプリに取り込まず、Javaで共通JPEG探索を実装している。

| 系統 | 探索対象の拡張子 | 参照・方式 |
|---|---|---|
| Adobe・スマートフォン・GoPro | dng, gpr | TIFF/SubIFDにあるJPEG。非圧縮プレビューしか持たない場合もある |
| Canon | crw, cr2, cr3 | [CIFF](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/CanonRaw.pm)、TIFF、[BMFF](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/QuickTime.pm)内のJPEG |
| Nikon | nef, nrw | TIFF/MakerNoteのJPEG |
| Sony | arw, arq, sr2, srf | TIFF系のJPEG。ARQの全フレームを展開する機能ではない |
| Fujifilm | raf | [RAF内JPEG](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/FujiFilm.pm) |
| Olympus / OM SYSTEM | orf, ori | TIFF派生とMakerNote内JPEG。ORIも原本拡張子を保持 |
| Panasonic / Leica | rw1, rw2, raw, rwl | [JpgFromRaw](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/PanasonicRaw.pm)。古いRAWにはJPEGがない例もある |
| Pentax / Samsung | pef, ptx, srw | TIFF系のJPEG |
| Sigma / Foveon | x3f | [X3Fの画像セクション](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/SigmaRaw.pm)。SD9/SD10など非JPEGの例あり |
| Minolta / Epson | mrw, mdc, erf | JPEGと既知の先頭補正。[ExifTool ValidateImage](https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool.pm)も先頭補正を行う |
| Hasselblad / Phase One / Leaf / Mamiya / Sinar | 3fr, fff, iiq, cap, eip, mos, mef, cs1, sti | JPEGがある場合に抽出。非圧縮TIFFプレビューやZIP圧縮されたEIP内部は対象外 |
| Kodak・その他の旧形式 | dcr, kdc, k25, dc2, drf, kc2, rdc, rwz, bay, bmq, ia, pxn, qtk, obm | 共通探索。実ファイル未検証の拡張子を含む |
| TIFFを使用するRAW・ARRI | tif, tiff, ari | JPEGがある場合のみ。通常の非JPEG TIFF画像・ARRIRAW現像は対象外 |

探索対象は51拡張子（JPEG/JPEG拡張子を除く）。BRAW/R3D/CINE等のRAW動画デコード、暗号化・独自圧縮プレビューの復号、非圧縮RGB/TIFFからのJPEG生成、断片化JPEGの再構成は実装していない。JPEGの存在を確認できなければエラーとし、RAWデータを画像として誤って返さない。

一覧（MediaStore/フォルダ）、原本キャッシュ、共有準備、共有Provider、共有先候補の検索にも同じ拡張子表を使用する。未知のRAW用MIMEは`application/octet-stream`とし、DNGに偽装しない。AndroidがMediaStoreに登録しないファイルは、フォルダアクセスの許可が必要。プレビューキャッシュキーを更新し、旧抽出結果を再利用しない。

全体探索のため初回はRAWのファイルサイズに比例した読取りが必要。以後は既存のJPEGキャッシュを利用する。Android端末上の速度・メモリ使用量・外部アプリ共有は未確認。

## 検証

```sh
sh tests/raw-structure.sh
ANDROID_HOME=/path/to/android-sdk sh tests/preparation-storage.sh
sh tests/raw-corpus.sh /path/to/samples
./gradlew assembleDebug lintDebug
```

`raw-structure.sh`はJDKのJPEGエンコーダが生成した画像を使い、最大サイズ選択、64 KiB境界、遠い位置のJPEG、APP内のJPEG、progressive、両エンディアンのEXIF、先頭補正、切断・不正長・lossless除外、中断、I/Oエラー、拡張子を検証する。133チェック通過。

`raw-corpus.sh`は`sample.<拡張子>`を読取り、ExifToolのpreviewタグを別に抽出して比較する。EOI後のパディングを除いたJPEGのバイト一致とImageIOによる実デコードを検証する。TIFF等の参照はJPEGの比較対象にしない。ExifToolが未対応のファイルを「JPEGが絶対にない」と断定するものではない。

MOSだけは、ExifTool 13.55の`Leaf.pm`がバイナリ内のLFを空白に変換するため、その既知の変換を適用した参照一致を検証する。Loupeの出力は原本内のバイトを保持し、デコードにも成功している。

公開サンプルのURL、カメラ、SHA-256、ライセンス、今回の結果は[検証マニフェスト](raw-sample-manifest.json)を参照。画像自体はリポジトリに含めない。データ源は[raw.pixls.us](https://raw.pixls.us/)。配布元のSHA-256と取得ファイルを照合済み。

2026-10-25の結果: 30拡張子・34実ファイルを検査し、21拡張子の21ファイルで抽出・デコード・参照照合に成功。残る13ファイルは対象JPEGが未検出（ExifToolでも比較可能なJPEG参照なし）。追加機種を含めDNG/CRW/MRWはJPEGあり・なし両方の例を検証した。成功した拡張子はARW、CR2、CR3、CRW、DNG、ERF、KDC、MOS、MRW、NEF、NRW、ORF、ORI、PEF、RAF、RW2、RWL、SR2、SRF、SRW、X3F。上記以外の探索対象拡張子は全機種対応を確認したものではない。

共有準備のホストテスト、`assembleDebug`、`lintDebug`も成功。実機表示・実機共有・クラウド保存は未検証。
