# AI編集のバックグラウンド実行

現行契約は[Material 3 UI契約](../../material3-ui.md)、[Nano Banana](../../nano-banana.md)、[issue107](https://github.com/ktnyt/Loupe/issues/107)を参照する。

API35専用エミュレータで実際のAndroid Serviceを起動し、隔離した設定・合成画像・fake通信で検証する。画面はUiAutomationのOS screenshotで取得した。キー、個人写真、実サービスへの通信は含めない。物理端末のタッチ・IME・TalkBack音声や性能の確認とは区別する。実機確認は利用者の指定で後日とする。

通常はAI編集のプロンプトを選ぶだけで同じ写真画面に戻る。結果画面への遷移はなく、生成中も写真の移動、タグ操作、Homeが使える。結果は自動保存し、短い標準Toastで知らせる。Android必須の実行中通知は低重要度・無音とする。

キャプチャは400dp/fontScale1.0と320dp/1.3の各状態を収録した。readyはプロンプト、viewerは生成中の通常閲覧、busyは重複開始抑止、cleanupは保存後の削除再試行、missing-versionは手動復旧の案内、submittingは応答不明の案内。通常操作はprogrammatic click、背景継続は実際のHome IntentとService実行で確認する。missing-versionの場所表示は選択可能なテキストであり、コピーできることをアクセシビリティactionで確認する。

Instrumentationは320/400dp×fontScale1.0/1.3で、同時受付の抑止、開始時のプロンプト・出力snapshot、JPEG自動保存と読み戻し、背景での完了時にActivityを起動しないこと、保存済み画像を保持した後処理再試行、停止時の実行権保持、永続化した終了処理、古いIntent・古い終了確認の拒否を検証する。既存nano-banana検証で4K寸法・20MP上限・応答不明時の再POST抑止を維持する。

初回の停止確認はstopService後のonDestroyを待たず、providerを解放する競合があった。割込を受けたことを確認してから解放するfixtureへ修正した。アプリの動作を待ち時間だけで判定しない。

Toastは最新1件へ置換する。captureは最後の標準Toastが終了してから取得する。operations-400-1.0.txtとoperations-320-1.3.txtに、実行したassertionの範囲と通知を表示した順序を残す。通知hookはshowの呼出を記録し、OSの表示をすべて撮影した証拠とは区別する。ready/busyの表示行は48dp以上であることも検証する。

独立UI・Cold・Securityレビューと検証結果はissue107に記録する。
