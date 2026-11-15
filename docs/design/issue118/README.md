# #118 検証記録

実装の契約は [issue #118](https://github.com/ktnyt/Loupe/issues/118)、現在の動作は [AI生成とカメラ待機の表示](../../generation-indicator.md) を参照する。このディレクトリの画像は検証時点の記録。

対象は専用Androidエミュレーター `emulator-5580`。実際のNanoBananaServiceを起動し、外部通信部分だけをアプリ内のfake providerへ差し替えている。写真はテストで描いた風景。実写真・認証情報・実API・物理USBカメラ・常用実機は使っていない。

`python3 scripts/device-check.py --serial emulator-5580 --mode generation-indicator --width 400 --font 1.0 --no-standby-video '専用エミュレーター'`

狭いウィンドウは `--width 320 --font 1.3`。アプリのウィンドウ幅とリソース文字倍率を変え、OS領域を含む画面全体をscreencapで保存する。画像を切り抜いていない。

テストでは、カメラwaiting→scan/count→終了、バッジalphaの時間変化、一覧→写真→キー設定→通常メニュー、Home→アプリ復帰、実Serviceの保存→Snackbarの「開く」→保存画像の描画、失敗後の再確認を検査する。キー設定はFLAG_SECUREを維持してプログラムで状態を検査し、黒くなる画像は成果物に含めない。reduced motionはエミュレーターの設定を一時変更し、元の値へ戻して読み戻す。

静止画では動きや読み上げを判定できない。バッジの動きは時間を空けたalpha検査、状態と操作はinstrumentation結果を根拠にする。TalkBackの音声・物理操作・実APIの応答時間は未検査。

## 検証結果

- 400dp/font1.0、320dp/font1.3のgeneration-indicator検査はPASS。ボタンと生成表示、完了・失敗案内とビューアー操作の画面座標の非交差、モーダルの前面windowへの接続、状態確認からの非再送、reduced motionと設定の読戻しを含む。
- 既存nano-background検査は400dp/font1.0でPASS。保存JPEG読戻し、重複受付、背景継続、cleanup再開、古いIntent/確認画面の保護を確認した。
- 独立UI/UX評価は400dp/320dpの最終画像と操作記録の範囲で合格。
- build/lint成功。lintには既存の警告と、ライフサイクルで解放する前面Activity参照へのStaticFieldLeak警告がある。実端末、実API、物理USBカメラ、TalkBack発話は未確認。

主な画像: [生成中400dp](generating-viewer-400.png)、[モーダル400dp](generating-modal-400.png)、[完了400dp](complete-400.png)、[失敗400dp](attention-400.png)、[生成中320dp](generating-viewer-320.png)、[完了320dp](complete-320.png)、[待機](camera-waiting-400.png)、[待機の別フレーム](camera-waiting-dim-400.png)、[動き無効](reduced-motion-400.png)。

## 最終レビューと追加検証

Cold Reviewの指摘修正後、400dp/font1.0と320dp/font1.3で追加検査もPASS。Ctrl+Shift+GのキーイベントをActivityとモーダルへ送り、既存の状態メニューを開いた。永続cleanup/discardingの受付経路を実Serviceで起動し、fake deleteを待たせたままHomeへ戻り、Activity復帰後の実行中表示を確認した。保存URIを持たない後処理完了は、画像保存や「開く」を誤って案内しないことを確認した。

この再開検査は同一process内で永続jobの受付を通すもので、実process death復旧の証拠ではない。Cold Review再評価と差分Security Reviewは必須修正なし。Security Reviewで挙がったテストsetup時の例外隔離は、setupを既存finally境界内へ移して対応した。setup失敗の注入検査は未実施。

今回の停止点は実装・エミュレーターcapture提示。mainへの反映、OTA、常用実機導入は行っていない。
