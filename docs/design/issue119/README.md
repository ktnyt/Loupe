# #119 検証記録

現行の契約は [ルック保存領域の保全](../../look-removal.md)、目的・実装前の条件は [issue #119](https://github.com/ktnyt/Loupe/issues/119) を参照する。このディレクトリの画像は専用エミュレーターによる検証記録。

通常の写真閲覧・タグ内閲覧・現像を確認する。写真は合成JPEG。保存ルックのfixtureは削除前のd9b83edから取り出したクラスの実APIで生成し、同じAPIで読戻し済み。写真・認証情報は含まない。fixtureの由来は app/src/androidTest/assets/legacy-look/README.md に記載する。

テストは既存ファイルを上書きせずfixtureを追加し、私有look-library/look-sessions全体のファイル集合・バイトhash・mtimeを前後比較する。実際の写真キーに対応する旧選択snapshotを配置し、表示bitmapが元の合成JPEGと一致することを確認する。旧lookToken/preset/failed状態を持つBundleを渡して、現像側だけの復元と比較・JPEG保存・終了後の保存領域不変を検査する。

実機、実写真、物理カメラ、実APIは検証範囲に含めない。写真原本や既存の実データを消して条件を合わせない。
