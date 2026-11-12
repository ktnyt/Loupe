# 動画とNano Bananaの検証画面

2026-11-12 JST、Loupe 0.31.0 (164)、実機24129PN74G、1200×2670px/density520。通常幅369dpと320dp、font scale 1.0/1.3でinstrumentationを実行した。画面は合成MP4、合成JPEG、ダミー設定のみ。画像の内容やプロンプトに個人データを含めない。

| 画面 | 条件 | ファイル |
| --- | --- | --- |
| 動画の再生制御 | 369dp/1.0 | [video-controls.png](video-controls.png) |
| 動画の一時停止 | 320dp/1.3 | [video-paused-large-text.png](video-paused-large-text.png) |
| プロンプトメニュー | 320dp/1.3 | [nano-menu-large-text.png](nano-menu-large-text.png) |
| 設定 | 320dp/1.3 | [nano-settings-large-text.png](nano-settings-large-text.png) |
| タスクの確認再開 | 320dp/1.3 | [nano-resume-large-text.png](nano-resume-large-text.png) |
| 別画像として保存した結果 | 369dp/1.0 | [nano-result.png](nano-result.png) |

動画はSurfaceViewを含むUiAutomationのcompositor screenshot。Window PixelCopyだけの画像は動画面を含められないため根拠に使わない。Activity遷移終了を待って取得した。設定画面はFLAG_SECUREのため、同じ端末の隔離fixtureでレイアウトしたViewをdrawしている。ほかのNano画面はhardware/compositor capture。

Nanoの結果はfake networkで合成JPEGを返す検査であり、実サービスの生成画像ではない。中断・再試行・保存と後処理の状態遷移を検証した。実APIキーは使っていない。動画のplay/pause/seek/背景停止/復帰/失敗再試行、写真と動画が混在する場合の前後移動はinstrumentationで検査した。物理タップ、実IME、TalkBack音声、別codec、背景復帰後の途中フレームの厳密な保持は、この静止画からは確認できない。

独立UIレビューでは、映像フレームと再生制御、Nanoの設定・メニュー・復帰・保存結果に表示上の修正必須指摘はなかった。操作の根拠と詳細は[動画issue #94](https://github.com/ktnyt/Loupe/issues/94)、[Nano Banana issue #95](https://github.com/ktnyt/Loupe/issues/95)の各工程記録を参照する。本フォルダは検証記録であり、現行UIの権威は[Material 3契約](../../material3-ui.md)と各issueの契約。
