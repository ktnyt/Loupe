# E-P7 Wi-Fi PoCのAndroid画面検査

専用emulator-5580（Android API 35）の合成検査。実カメラとは通信していない。

- `before-settings-411-1.0.png`: 変更前のカメラ設定、実画面幅411dp・文字倍率1.0。
- `ep7-wifi-*-411-1.0.png`: 変更後の通常幅。
- `ep7-wifi-*-320-1.3.png`: 検査プロセスのWindowを320dp、文字倍率を1.3にした狭幅。システムの文字設定は変更していない。
- `initial`: 初期表示。自動通信なし。
- `busy` / `failure`: ラッチで制御した合成処理中と失敗。
- `dropdown`: 共通の名前を持つJPG/ORFを選ぶ合成一覧。形式と容量を先頭行へ表示し、フォルダ/写真名は折り返す。選択中の表示は `success-fixture` で確認する。
- `success-fixture`: 合成の成功表示。数値とSHAは表示検査用であり、実カメラの転送計測値ではない。
- `settings`: 既存カメラ設定から試験画面を開くボタン。

captureは描画commit後のcomposed displayで、OS領域も含む。狭幅captureの左右余白は検査Window外。スワイプや音声を静止画だけで確認済みとは扱わない。

検査は `Ep7WifiChecks` の `ep7-wifi` mode。待機、初期無効操作、処理中、失敗・再実行可能状態、onStop中止要求、設定導線、48dp最小領域、toolbar/scrollの安全領域、限定cleartext policy、形式の選択中表示とdropdown内の読み上げ文字を確認する。実TalkBack音声、E-P7とのWi-Fi接続・原本転送・実速度は未検査。

独立UI再レビューで形式の判別と初期OSアイコンを確認し、要修正指摘なし。dropdownを閉じる途中の `list` captureは、安定した状態の証拠に使わず保存対象から除外した。
