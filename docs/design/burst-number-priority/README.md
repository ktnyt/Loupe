# 連写番号優先のAndroid画面記録

[Issue #188](https://github.com/ktnyt/Loupe/issues/188)と[動作契約](../../burst-number-priority.md)を参照する。

## エミュレータ

Android emulator-5580、縦画面、通常411dp/font1.0と320dp/font1.3、density420。OSバーと数字IMEを含む。画像は合成placeholderだけを使い、私的なRAWのプレビューを公開していない。検査後は1080×2400px/font1.0へ復元した。

| 状態 | 通常幅 | 狭幅 |
| --- | --- | --- |
| 変更前の入力 | [画像](before-normal-keyboard.png) | [画像](before-narrow-keyboard.png) |
| 変更前の選択 | [画像](before-normal-selected.png) | [画像](before-narrow-selected.png) |
| 番号優先の入力 | [画像](after-normal-keyboard.png) | [画像](after-narrow-keyboard.png) |
| 選択値350ms | [画像](after-normal-selected.png) | [画像](after-narrow-selected.png) |
| 入力エラー | [画像](after-normal-invalid.png) | [画像](after-narrow-invalid.png) |
| 合成番号つきgrid | [画像](after-normal-grid.png) | [画像](after-narrow-grid.png) |

両幅で保存/再表示/Cancel/Back/外側/入力エラー/scroll後の全体無効・再試行と合成gridの番号優先・時間fallbackを操作した。独立UIレビューは画像と操作記録の範囲で合格。静止画だけでhit領域・読み上げ順序・音声を検証済みとしない。

## データ経路

隔離Android DBでv1/v2/v3→v4の既存列/記録保持、NULL/番号なし/番号あり、競合winner保持、JPEG marker/APP1の形式・長さ・署名途中EOF・short read/skip0・pixel直前停止、元stream読取失敗と1MiB予算超過で未検査を維持すること、internal/SDと同一volumeのprovider表現、e2-only衝突fallbackを確認した。

実ORFは機種別の承認済み少量サンプルを専用emulatorへコピーし、元のhashと一致する3枚のOM-1 Mark II連写について抽出→metadata cache→索引で1msでも1組、無効時は3単独を確認した。実写真はActivityへ描画せず、画面検証は同じ意味の合成metadataとplaceholderで分けた。

番号補完だけの計測は、実ORF3件/番号3件/失敗0で26msだった。直前の撮影日時metadata抽出、全件走査、索引公開や描画は含まない。実ライブラリ全体の性能を示す数値ではない。JPEGは上限付きAPP1抽出へ変更したため、途中方式の計測値は現方式の性能として扱わない。

## 未確認

物理端末の新実装・常用main APK導入は別途追跡する。実写JPEG、他の連写モード、未知の独自MakerNote、TalkBack音声、実ライブラリ全体の再構築時間は未確認。実機のDB全体をMacへ移す案は自動承認審査で拒否され実行せず、DB移行は隔離した合成DBで検証した。

最終の番号用JPEG抽出は汎用ExifInterfaceを使わず、EXIF APP1のTIFF（最大65527byte）だけを既存readerへ渡す。実ORF index検査ではMainActivity破棄とworker drainを確認してから実写真コピーを公開し、失敗時も実行ごとのUUID folderとowned URIを満たすcache keyを再収集して削除する。以前の途中検査で個別keyを記録していなかった残cacheについては、推測で削除していない。
