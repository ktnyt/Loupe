# 連写判定ミリ秒入力のAndroid画面記録

Issue: https://github.com/ktnyt/Loupe/issues/188
現行の動作契約: [burst-gap-millis.md](../../burst-gap-millis.md)

## 変更前

Androidエミュレータemulator-5580に既存導入されていたLoupe 0.31.54（versionCode 218）で取得。実装基点3640011の0.31.59（223）より古い版のため、変更前の実機検証や同一版比較の証拠ではない。ソースで既存の0/1/2/3秒の選択肢を照合した。

- [通常幅/font1.0](before-normal.png): 1080×2400px、411dp、density420、縦画面。
- [320dp/font1.3](before-narrow.png): 840×2400px、density420、縦画面。

撮影後にエミュレータの画面サイズ・文字倍率を元の値へ戻した。OSバーを含むcapture。写真原本を検証用データとして使用していない。

## 確認の範囲

エミュレータの画面記録は、物理端末での表示・キーボード・操作や常用版APK導入の確認と区別する。変更後の操作結果と未確認項目はissueへ記録する。
