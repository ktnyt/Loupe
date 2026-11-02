# CNT-like PoCの検証と使い方

## 現在の状態

issue #65 の画像処理エンジンと研究用UIを既存の現像へ組み込んだ。調整・比較・外部LUT・原寸保存を同じ現像画面から使う。実装済みの範囲と実機検証済みの範囲は下記とissueの結果で区別する。

## 実装した経路

既存LibRaw/JPEG decode → linear sRGB/float GPU → 既存現像 → 符号化sRGB → lookのPCHIP曲線 → 3D LUT → 固定seedの輝度粒子 → 既存出力ディザ → RGBA8 → 既存原寸JPEG保存。

[処理契約と調査結果](cnt-look-contract.md)を参照。既存のロールオフは変更せず、追加look独自のトーンマッピングは行わない。ES3で32bit floatテクスチャの8頂点から手動三線形補間する。

## 開発者向けの実行

専用worktree `/private/tmp/loupe-issue65`、branch `65/cnt-look-poc`。通常アプリと実験版は既存のビルド方法を使う。

```sh
sh tests/raw-edit-look.sh
sh tests/raw-edit-look-store.sh
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

画面ではRAW/JPEGを開き、ヘッダーの「現像 ▾」からルックの調整シートを開く。CNT-like（仮）、各処理のON/OFF、LUT読込/強度、粒子/暗部寄り/seedを調整する。「カーブを編集」はトーンタブを開き、パネル下段の「現像／ルック」で編集対象を切り替える。Neutralは確認後に既存補正とrolloffも中立に戻す。Customは手動設定を保持し、プリセット間を移って戻せる。通常の比較は全補正を一時的に外す。保存は元画像と別の原寸JPEG。

APIからの設定例（画面と同じ状態を使用）:

```java
RawEditState neutral = RawEditState.neutral();
RawEditState cnt = neutral.withLook(RawEditLook.cntLike());
// InputStreamは呼び出し側が閉じる。符号化sRGB用の3D .cubeを使用する。
RawEditCube external = RawEditCube.read(inputStream);
RawEditLook custom = cnt.look().withCube(external).withLutEnabled(true)
    .withLutStrength(.5f).withGrainStrength(.2f).withSeed(65);
RawEditState adjusted = cnt.withLook(custom);
```

`RawEditLook.neutral()`は追加lookだけを無効にする。`RawEditState.neutral()`は既存の肩も含めた無加工用。既存float snapshotにはlookを含めないので、Activity復元は`RawEditLookStore`によるprivate fileとBundle内のUUID tokenで行う。current/saved/custom/保持外部LUTを含め、最大4個の65³表を13,214,784 bytes以下のファイルへ保存する。BundleへLUTを直接入れない。保存は同期処理で時間を診断ログへ残す。失敗した復元を無加工と偽って再開せず、エラーを表示する。

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
- GPU: b1454f51（25128PNA1G）/実験0.6.0 code9で最終再検査PASS。CPU/GPU最大差1 code、25タイル最大差1 code、1024→512の粒子比較MAE .795715 code / RMS 1.073757 code。最終8bit灰色ランプは225階調超、最終ディザの1 code以内で単調、黒端点/ハイライト圧縮を確認。lookテスト全体26970msで、操作レイテンシの計測ではない。
- 保存: FullSession→NativeJpeg95→private provider→JPEG decodeを65×43合成写真で比較。MAE 2.109720 code/RMS 4.522125 code、RGB平均偏り .06619/−.00859/−.05045 code。原寸/固定seed/保存snapshot/pending解除を確認。
- UI: 実験0.6.0/code9、400dp/font1.0と400×640dp/font1.3で、実View操作・AX progress・seed確定・Neutral確認/取消・プリセット往復・曲線Undo/Redo・Activity再生成を確認。Customの基準プリセットと現像のWB/曲線、保持外部LUTの復元も確認。各操作域48dp以上、文字の高さと省略、スクロールによる到達を検査した。外部LUTはこのUI検査内では合成fixtureを設定しており、OSピッカーの検査とは区別する。
- UI診断: 1回の描画完了待ちtimeoutは診断追加後の通常/短画面で再現しなかった。待機条件・制限時間は緩めていない。呼出stageと描画状態の診断を検査に保持する。
- 外部ファイル選択: 実際のOS ACTION_OPEN_DOCUMENTから合成17³/65³ cubeを選び、使用中の名前・寸法とCustom適用を確認。無効cubeではエラー/再読込と前の65³保持を確認。専用fixtureを削除し、写真原本は使用していない。通常版0.23.0/code145を導入し端末からversionを読み返した。
- UI比較: 選択文字600の最新版を通常/短画面で再撮影し、Claudeのレイアウト修正版と独立評価した。追加修正が必要な重大UI/UX差なし。短画面はActivityだけを640dpへ縮める検査条件に合わせ、test-onlyで独立Dialogの実Windowを296px移動した。Dialogの実高883pxがすべてcontent内へ入り、補正前後の座標をJSONへ明記する。PixelCopy後の画素移動は行わない。実際の短い端末のWindow配置そのものや全画素一致を示すものではない。字形・改行・行間などの小差と、実指/実IME/TalkBackの未確認は残る。
- 通常版の結合: 0.23.0/code145の既存PhotoEditChecksで、viewerから合成JPEGを内部現像へ読み込み、Mono編集/再生成、MediaStore JPEGの寸法・tint・原本byte不変、保存中Back取消と専用出力のcleanupを確認。非公開Activityの権限を変えずにapp内経路を使う。外部editor/save互換は未検査。
- 端末の私有store: 3個の65³表（9,887,142 bytes）のsave 215ms/load 333ms、round tripとatomic replacementを確認。保存/復元は同期I/Oのため、大きなLUTを持つ画面再生成時にはこの待ちが発生し得る。4個の65³表はhostで検査済み、端末上の最大構成の時間は未計測。
- lint: 両モジュールでErrorなし。Warningは実験42件、通常101件（既存を含む）。今回追加のprogrammatic Viewのconstructor、SliderのperformClick、固定日本語、RTL等の警告は残っている。全件解消やアプリ全体の無警告を主張しない。
- 経過時間はissue-progressコメントで計測。入力→表示の実測、実写真の主観評価、完了までの利用量は未計測。

最新の結果と工程状態はissue #65を正とする。

## 制約と次の比較

- 仮のlookは暖色保護、寒色寄りの暗部、明部の色かぶり抑制を意図したもの。Leica参照に対する一致度は測っていない。
- 現行RAW初期プレビューはhalf-sizeデモザイク、原寸cacheはfull-size。画像入力が違うので、同じshaderでも細部の完全一致は保証しない。
- 出力はSDR sRGB相当の8bit JPEG。HDR/Log/Display P3 LUTの自動判別、HDRの保存、16bit PNG/TIFF書き出しは未実装。既存JPEGデコードの8bit制約を引き継ぐ。
- グレインは長辺2048の写真座標を基準にし、縮小時は4点サンプルでaliasを抑える。実機とJPEG後の印象は未確認。粒子の物理モデルや任意縮小率での厳密な面積積分ではない。
- Customと保持LUTは開いている編集セッション内の設定で、写真をまたぐ永続的なユーザープリセット管理ではない。
- 入力画像にすでにあるノイズ、スマホの局所トーンマッピング、シャープニング、WBはlookだけでは取り除けない。

次は同一シーン・同一露出/WB・同じ画角の元画像/CNT適用画像のペアが必要。まず肌色、ニュートラルグレー、空、緑、赤、暗部のある逆光、明部飽和のある場面を揃える。比較のために顔や位置情報を外部へ送る必要はない。

位置合わせ後にグレー段階のトーン伝達、明度別の色相/彩度誤差、肌色とハイライトの色差を分けて推定する。粒子は平坦領域を明度帯ごとに分け、分散・空間周波数・JPEG圧縮との差を測る。曲線→LUT→粒子の順に一つずつ合わせ、未使用シーンで過学習を確認する。CNT特有と思われる局所コントラストが残る場合だけ別工程として検討する。
