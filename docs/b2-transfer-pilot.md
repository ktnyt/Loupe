# B2への少量転送試行

[B2と端末SQLiteの設計](b2-archive-design.md)の接続試行。Macなどのホストから、専用の非公開バケットに指定したRAWとJPEGを保存し、保存時に返った版を読み戻す。対象は各形式1枚以上、計2〜4枚、合計256 MiB以下。

```sh
python3 scripts/b2-transfer-pilot.py \
  --endpoint s3.us-west-004.backblazeb2.com \
  --bucket TEST_BUCKET \
  /path/to/sample.dng /path/to/sample.jpg
```

Python 3.8以上と、SigV4および転送中のサイズ制限を扱えるcurl 8.4以上を使う。B2の標準アプリキーは試験バケットに絞り、読み書きを許可し、完全削除権限は付けない。キーは端末に表示されない入力欄で入力する。すでにシェルに `B2_APPLICATION_KEY_ID` と `B2_APPLICATION_KEY` を設定している場合は、その値を利用する。コマンド引数や出力にキーは含まれない。

送信先はB2公式のS3 HTTPSホストに限る。試行ごとに `loupe-transfer-pilot/<試行ID>/` を割り当てる。元ファイル名をリモートキーに使わない。版、サイズ、SHA-256が一致したときだけ合格とし、失敗時は後続の送信を止める。B2に試行用オブジェクトが残るため、不要になった場合は管理画面で表示された試行の接頭辞を確認する。

出力するPUT・GETの秒数はcurlが計測した接続開始から転送完了までの時間で、ローカルの事前コピーとSHA-256検査の時間は含めない。速度の単位はMiB/s。ホストと回線の条件を併記し、Androidアプリでの表示時間や実機の転送性能とは区別する。

この試行が確認する範囲は原本の往復転送と内容の一致。Androidのキー保護・端末SQLite・プレビュー・カタログ復元は次の実装で扱う。
