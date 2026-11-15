# ルック保存領域の保全

写真ビューアーと手動現像には、ルックの選択・適用・保存・LUT取込機能はない。写真ビューアーは原本由来のプレビューをそのまま表示する。手動現像は現像側の調整、カーブ、元画像との比較、JPEGの別ファイル保存を行う。

既存の私有 `look-library/` と `look-sessions/`、そこにあるプリセット・写真別選択・編集sessionは、読み取り・書込み・移行・削除を行わず保持する。既に保存された写真とJPEGも変更しない。旧sessionのBundleにあるルックのtokenやpreset状態は適用せず、互換な現像側のvalues/saved/curveBankなどだけを復元する。現像側snapshotのFORMAT6302と値・カーブの構成は維持する。

過去のCNT校正・ルック研究の資料と検証画像は履歴として保持する。アプリに同じ機能があることや、現在のAndroid動作を確認した証拠には用いない。

目的・削除範囲・保全契約は [issue #119](https://github.com/ktnyt/Loupe/issues/119)、検証時の画像と限界は [検証記録](design/issue119/README.md) を参照する。
