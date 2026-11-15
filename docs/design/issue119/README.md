# #119 検証記録

現行の契約は [ルック保存領域の保全](../../look-removal.md)、目的・実装前の条件は [issue #119](https://github.com/ktnyt/Loupe/issues/119) を参照する。このディレクトリの画像は専用エミュレーターによる検証記録。

通常の写真閲覧・タグ内閲覧・現像を確認する。写真は合成JPEG。保存ルックのfixtureは削除前のd9b83edから取り出したクラスの実APIで生成し、同じAPIで読戻し済み。写真・認証情報は含まない。fixtureの由来は app/src/androidTest/assets/legacy-look/README.md に記載する。

テストは既存ファイルを上書きせずfixtureを追加し、私有look-library/look-sessions全体のファイル集合・バイトhash・mtimeを前後比較する。実際の写真キーに対応する旧選択snapshotを配置し、表示bitmapが元の合成JPEGと一致することを確認する。旧lookToken/preset/failed状態を持つBundleを渡して、現像側だけの復元と比較・JPEG保存・終了後の保存領域不変を検査する。

実機、実写真、物理カメラ、実APIは検証範囲に含めない。写真原本や既存の実データを消して条件を合わせない。

## 結果

- 400dp/font1.0と320dp/font1.3のlook-removal検査はPASS。通常/タグviewerの操作を実OSバー・cutout安全領域で検査した。タグscreenはcontent root再構築後にinsetsを再要求する。
- 有効な旧preset・写真別選択・sessionのファイル集合、バイトhash、mtimeが閲覧・旧Bundle復元・比較・原寸JPEG保存・Activity終了の前後で一致した。合成JPEG原本も一致。1200×800 JPEG出力を読み戻した。
- 現像検証アプリのGPU tone/curve UI/full source/tile/session/publish検査はPASS。カーブの検査helperがonSaveInstanceStateで編集中操作を確定させていたため、状態を変えない観測へ修正してpinch取消も再確認した。
- #118の共通生成インジケーターも400dpで回帰検査PASS。模擬APIだけを使用した。
- 通常appとraw-experimentのbuild/lint、現像state/toneホストテストは成功。独立UI/UX、Cold Review、差分Security Reviewは最終評価で必須指摘なし。

検査は専用エミュレーターで直列実行する。JPEG出力の検査・片付けはMediaStoreのbefore/after差分を使うため、同じappの別sessionが並行保存する環境へそのまま流用しない。より広い用途では保存URIの個別追跡が必要。

実写真・実端末・物理カメラ・実API・TalkBack発話は未確認。保存済みの常用端末データには触れていない。main反映・OTAは今回の範囲に含めない。

画像: [通常400dp](look-removed-viewer-400.png)、[タグ400dp](look-removed-tag-400.png)、[現像400dp](look-removed-editor-400.png)、[通常320dp](look-removed-viewer-320.png)、[タグ320dp](look-removed-tag-320.png)、[現像320dp](look-removed-editor-320.png)。
