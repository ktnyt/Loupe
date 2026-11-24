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

## 変更後

検査用APKはLoupe 0.31.59（versionCode 223）、専用エミュレータemulator-5580で取得した。通常幅411dp/font1.0と320dp/font1.3、density420、縦画面のOSバーを含むcapture。画面データは空の検査UIで、写真原本を表示していない。

| 状態 | 通常幅 | 320dp/font1.3 |
| --- | --- | --- |
| 数字キーボードと入力 | [画像](after-normal-keyboard.png) | [画像](after-narrow-keyboard.png) |
| 不正値とエラー | [画像](after-normal-invalid.png) | [画像](after-narrow-invalid.png) |
| 保存した350msの選択表示 | [画像](after-normal-selected.png) | [画像](after-narrow-selected.png) |

狭幅の選択一覧は選択されたカスタム値までスクロールした状態であり、最上部の「まとめない」はこの位置では一部だけが見える。スクロール操作の検証結果はissueのAndroid検査記録に分けて残す。

配置の独立UI評価、コードのCold Review、変更範囲のSecurity Reviewと修正結果はissueを参照する。静止画だけで読み上げ・操作・保存・失敗復帰を検証済みとはしない。物理端末とTalkBack音声は未確認。

## 最終確認

最終APKのSHA-256は`694a0d824dc919147821f5dd0e601f78fc9c8d360491267536f13d44017fa099`。通常/狭幅の`burst-gap-millis`と既存`index-signature`のAndroid instrumentationはPASS。設定保存・再表示・入力エラー・Cancel/Back/外側タップ・スクロール後の「まとめない」選択、索引更新中の旧連写/選択抑止、合成JPEG2枚の単独表示への反映、失敗再試行、元設定の復元と合成写真削除を確認した。閾値を有効化する逆方向の旧singleton操作抑止は共通ガードのコードレビューによる確認であり、独立した実操作ケースではない。

UIレビューは最終6枚について合格。通常幅のエラー文末1文字だけの改行を解消した。IME/ダイアログ遷移とスクロール位置の検査側の失敗は修正後に同じ操作で再確認した。端末の表示設定は1080×2400px、density420、font1.0へ復元した。

専用エミュレータでの再実行は、検査APK導入後に以下のmodeを指定する。検査は作成した合成写真のみ削除し、設定を復元する。常用端末への検証APK導入は開発手順のgateに従う。

```sh
adb -s emulator-5580 shell am instrument -w -r -e mode burst-gap-millis jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation
adb -s emulator-5580 shell am instrument -w -r -e mode index-signature jp.nano.rawgallery.test/jp.nano.rawgallery.ArchiveInstrumentation
```
