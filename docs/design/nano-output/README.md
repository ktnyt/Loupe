# AI出力設定のAndroid合成検証

現行の視覚契約は [Material 3 UI契約](../../material3-ui.md)、動作は [Nano Banana](../../nano-banana.md) と [issue99](https://github.com/ktnyt/Loupe/issues/99) を参照。

専用Android 35エミュレータで、320/400dp・fontScale 1.0/1.3の4条件を検証した。キーやプロンプトは隔離fixtureの合成値のみで、有料APIやe2への通信は行っていない。

設定とdialogにはFLAG_SECUREを保ち、合成ViewをCanvasへdrawして取得した。末尾の選択マークはsoftware CanvasがRenderThreadのdrawable animationを進めないため、テスト内で静的終点へ進めて取得する。runtime animationの検証としては扱わない。これらはOS screenshotや実スマホのcaptureではなく、実タッチ・IME・TalkBack音声・実サービスの証拠にはしない。

- [変更前・320dp/1.3](before-settings-320-1.3.png)
- [出力設定・320dp/1.3](output-settings-320-1.3.png)
- [出力設定・400dp/1.0](output-settings-400-1.0.png)
- [解像度の選択・320dp/1.3](resolution-320-1.3.png)
- [末尾比率へのスクロール・320dp/1.3](aspect-tail-320-1.3.png)

Instrumentationでは設定の保存/キャンセル/再表示、不正な型の設定からの復帰、ListViewを末尾へスクロールして21:9の行を選択・保存・再表示する操作、48dp以上の行を検査する。dialog起動はreflection、行と保存の操作はprogrammatic clickで、物理タッチとは区別する。

fake networkで開始時の比率/解像度snapshot、設定変更後の再開とPOST再送抑止、4800×3584の合成JPEGを縮小せず保存して読み戻すこと、20,000,000px超過拒否、表示previewのサンプリングを検査する。

独立UI・Cold・Securityレビューと修正結果はissue99に記録する。実機での保存・設定保持とpeak memoryは未確認。
