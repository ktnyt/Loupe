# CNT-like PoCの検証と使い方

## 現在の状態

issue #65 の画像処理エンジンを実装したチェックポイント。研究用UIは未実装で、通常の現像画面からCNT-likeや外部LUTを選ぶことはまだできない。既存画面の起動だけでは新しいルックを試せない。ClaudeへのUI設計依頼、Activityでのlook永続化、実機操作・保存・画質確認が残る。PoC全体の完了ではない。

## 実装した経路

既存LibRaw/JPEG decode → linear sRGB/float GPU → 既存現像 → 符号化sRGB → lookのPCHIP曲線 → 3D LUT → 固定seedの輝度粒子 → 既存出力ディザ → RGBA8 → 既存原寸JPEG保存。

[処理契約と調査結果](cnt-look-contract.md)を参照。既存のロールオフは変更せず、追加look独自のトーンマッピングは行わない。ES3で32bit floatテクスチャの8頂点から手動三線形補間する。

## 開発者向けの実行

専用worktree `/private/tmp/loupe-issue65`、branch `65/cnt-look-poc`。通常アプリと実験版は既存のビルド方法を使う。

```sh
sh tests/raw-edit-look.sh
sh tests/raw-edit-state.sh
sh tests/raw-structure.sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=$(/usr/libexec/java_home) ./gradlew :raw-experiment:assembleDebug :raw-experiment:assembleDebugAndroidTest :app:assembleDebug :raw-experiment:lintDebug
```

GPU確認用instrumentationを追加し、下記のlook検査を実機で実施した。再実行する際は現在の端末・ビルド・ロック状態を確認する。端末ロック解除と待機動画停止・負荷確認後に使う。installとdevice-testはissue-progressへ別々に開始/結果を記録する。通常アプリをテスト目的で上書きする必要はない。

```sh
adb -s SERIAL install -r raw-experiment/build/outputs/apk/debug/raw-experiment-debug.apk
adb -s SERIAL install -r raw-experiment/build/outputs/apk/androidTest/debug/raw-experiment-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e look true jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
```

`PASS`本文を確認し、`LoupeRawEdit`の`look.check`、`look.tiles`、`look.resize`を取得する。coreのPASSだけをlook品質の証明にしない。`-e tone true`、`-e full true`は既存回帰検査。look検査は合成画像のみで、実写真の主観品質を検査しない。

APIからの設定例（UI実装後も同じ状態を使用）:

```java
RawEditState neutral = RawEditState.neutral();
RawEditState cnt = neutral.withLook(RawEditLook.cntLike());
// InputStreamは呼び出し側が閉じる。符号化sRGB用の3D .cubeを使用する。
RawEditCube external = RawEditCube.read(inputStream);
RawEditLook custom = cnt.look().withCube(external).withLutEnabled(true)
    .withLutStrength(.5f).withGrainStrength(.2f).withSeed(65);
RawEditState adjusted = cnt.withLook(custom);
```

`RawEditLook.neutral()`は追加lookだけを無効にする。`RawEditState.neutral()`は既存の肩も含めた無加工用。既存float snapshotにはlookを含めないので、Activity復元時には`RawEditLook.write/read`によるprivate file保存も必要。最大65³表をBundleへ直接入れない。

## 比較用の合成素材

```sh
python3 scripts/generate-look-fixtures.py /tmp/loupe-look-fixtures --size 17
# 現行画像入口はRAW/JPEG。PNG参照をJPEGへ変換してアプリで使う場合:
sips -s format jpeg /tmp/loupe-look-fixtures/look-ramp-patches.png --out /tmp/loupe-look-fixtures/look-ramp-patches.jpg
```

既存ファイルがある出力先には上書きしない。`--size 33`や`--size 65`には別の空ディレクトリを使う。PNGはsRGBタグ付きの8bit合成参照で、生成時点の段差と処理起因の段差を区別する。内部精度は4097点以上のfloatランプで別に検査する。JPEG再圧縮はNeutralでも値を変えるので、同一サイズのshader出力を主に比較する。

## 検証の区別

- 純Java: Identity LUT 17/33/65、非自明な三線形補間、DOMAIN、端点、異常入力、Neutral、seed、共通チャンネルノイズ、floatランプ、値の保存/復元を確認。
- 既存純Java: 128曲線/128万サンプル、階調・色域・Undo/Redo、およびRAW構造144項目を確認。
- ビルド: 通常APK・実験APK・実験テストAPKをビルド。Android lintを実施。
- GPU: b1454f51（25128PNA1G）/実験0.5.0 code8でPASS。CPU/GPU最大差1 code、25タイル最大差1 code、1024→512の粒子比較MAE .795715 code / RMS 1.073757 code。テスト全体18986msで、操作レイテンシの計測ではない。
- UI/画像選択/実保存: 新UI未実装のため未確認。端末は利用者が解除し、既存tone回帰と新lookの合成GPU検査を実施した。
- 経過時間はissue-progressコメントで計測。入力→表示の実測、実写真の主観評価、完了までの利用量は未計測。

最新の結果と工程状態はissue #65を正とする。

## 制約と次の比較

- 仮のlookは暖色保護、寒色寄りの暗部、明部の色かぶり抑制を意図したもの。Leica参照に対する一致度は測っていない。
- 現行RAW初期プレビューはhalf-sizeデモザイク、原寸cacheはfull-size。画像入力が違うので、同じshaderでも細部の完全一致は保証しない。
- 出力はSDR sRGB相当の8bit JPEG。HDR/Log/Display P3 LUTの自動判別、HDRの保存、16bit PNG/TIFF書き出しは未実装。既存JPEGデコードの8bit制約を引き継ぐ。
- グレインは長辺2048の写真座標を基準にし、縮小時は4点サンプルでaliasを抑える。実機とJPEG後の印象は未確認。粒子の物理モデルや任意縮小率での厳密な面積積分ではない。
- 入力画像にすでにあるノイズ、スマホの局所トーンマッピング、シャープニング、WBはlookだけでは取り除けない。

次は同一シーン・同一露出/WB・同じ画角の元画像/CNT適用画像のペアが必要。まず肌色、ニュートラルグレー、空、緑、赤、暗部のある逆光、明部飽和のある場面を揃える。比較のために顔や位置情報を外部へ送る必要はない。

位置合わせ後にグレー段階のトーン伝達、明度別の色相/彩度誤差、肌色とハイライトの色差を分けて推定する。粒子は平坦領域を明度帯ごとに分け、分散・空間周波数・JPEG圧縮との差を測る。曲線→LUT→粒子の順に一つずつ合わせ、未使用シーンで過学習を確認する。CNT特有と思われる局所コントラストが残る場合だけ別工程として検討する。
