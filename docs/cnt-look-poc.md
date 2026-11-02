# CNT-likeの使い方と検証

## 起動と操作

既存のLoupeでRAW/JPEGを開き、現像画面の「現像 ▾」からルック調整を開く。通常版0.24.0/code146、実験版0.7.0/code10が今回の対象。最新の導入・検査結果はissue #67を参照する。

- CNT-like（仮）: Gallery CNTの同一入力参照から測ったカラーLUTと周辺光量を適用する。曲線はidentity、粒子は既定オフ。既存現像の露出・WB・曲線は変更しない。
- Neutral: 確認後、ロールオフ0を含め現像とルックを中立に戻す。読み込んだLUTとCustomは保持する。
- Custom: 手動の曲線・LUT・周辺光量・粒子を保持し、他のプリセットから戻せる。リセットは基準プリセットのルックだけを戻す。
- トーン、LUT、周辺光量、粒子は独立してON/OFFできる。LUT・周辺光量・粒子には個別の強度がある。粒子は暗部寄りとseedも調整できる。
- 「カーブを編集」からトーンタブの「現像／ルック」を切り替え、既存の多点PCHIPカーブを調整する。
- 比較ボタンで全補正を一時的に外す。保存は原本と別の原寸JPEG。左右分割比較は未実装。

外部`.cube`は符号化sRGB用の3D LUT、サイズ2〜65、32MiB以下を選ぶ。読み込み失敗時は前の有効LUTを保持する。外部LUTの読み込みで周辺光量の設定は変えないため、LUT単独の評価では周辺光量をオフにする。

## 画像処理と精度

RAW/JPEGデコード → linear sRGBの既存現像 → 符号化sRGBのルック曲線 → 3D LUT → 周辺光量 → 固定seedの輝度粒子 → 既存ディザ・最終clamp → 8bit JPEG。

内部RGBA32F、曲線テーブル8193点、LUTはfloatの8頂点から手動三線形補間。中間の8bit化は追加しない。周辺光量と粒子は向き適用後の全画像座標で評価し、タイルごとに中心やseedを取り直さない。CPU/GPUの処理と保存snapshotを共通にする。[処理契約](cnt-look-contract.md)、[校正方法・実写真の評価・残る誤差](cnt-quality.md)を参照。

RAWは既存LibRaw、camera WB、auto bright無効。プリセットはカメラJPEGの明るさへ自動調整しない。必要に応じて既存現像で露出・階調を合わせる。RAW現像とカメラ内ISPの違いを、一般JPEG用のカラーLUTへ混ぜない。

## 開発と確認

```sh
sh tests/raw-edit-look.sh
sh tests/raw-edit-look-store.sh
sh tests/raw-edit-state.sh
sh tests/raw-structure.sh
python3 -m venv /tmp/loupe-cnt-calibration-venv
/tmp/loupe-cnt-calibration-venv/bin/pip install -r scripts/requirements-cnt-calibration.txt
/tmp/loupe-cnt-calibration-venv/bin/python tests/test-calibrate-cnt-look.py
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools JAVA_HOME=$(/usr/libexec/java_home) ./gradlew :app:assembleDebug :raw-experiment:assembleDebug :raw-experiment:assembleDebugAndroidTest :app:lintDebug :raw-experiment:lintDebug
```

校正ツールの`generate`、`fit --spatial`、`evaluate`、`apply`の使い方は[校正文書](cnt-quality.md)に記載する。画像はローカルで扱い、GitHub・Claudeへ原本を添付しない。

実験版のGPU/保存検査は端末ロックと待機動画の停止を確認し、issue-progressで開始・終了を記録して実行する。単なるinstrumentationの終了コードで成功とせず、`PASS`本文も確認する。

```sh
adb -s SERIAL shell am instrument -w -e look true -e tone true -e full true jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
adb -s SERIAL shell am instrument -w -e lookui true -e font 1.0 -e width 400 jp.nano.rawgallery.rawexperiment.test/jp.nano.rawgallery.RawEditInstrumentation
```

`lookreference=true`は任意のパスやCamera写真を直接読まない追加検査。実験アプリのprivate filesへ明示的に配置した`cnt-reference-input-0.jpg`〜`2.jpg`だけを読み、原本とは別のcacheコピーからJPEG現像・GPUプレビュー・原寸タイル・NativeJpeg95を確認する。結果PNG/JPEGは同じprivate filesへ残し、入力のハッシュ不変を検査する。MediaStore公開やUI操作の検証は別経路で行う。写真をAPKへ同梱しない。

## 検証済みの範囲

- Host: Identity17/33/65、DOMAIN、三線形補間、異常入力、正確なNeutral、単調PCHIP、固定seed、輝度粒子、6501/6502の画素互換、6503の独立した周辺光量の保存、私有store、128曲線/128万サンプル、構造144項目を確認。
- 校正: チャートCの未学習中間色で平均ΔE76 1.6012/p95 3.7605。同一入力のGallery CNT参照3場面で旧プリセットより色差が減少し、候補名を伏せた独立画像評価でも全3場面で新候補が選ばれた。Camera CNT全体や肌色の品質証明ではない。
- Build/lint: 通常・実験・実験テストAPKをビルド。lintはError0、Warning通常101/実験42（既存を含む）。
- 実験0.7/code10のGPU/現像/保存: CPUとの差最大1 code、縦横の原寸タイル差最大1 code。NativeJpeg95の合成写真比較MAE1.98187/RMS3.97749 code。粒子1024→512のMAE0.795715 code。3個の65³私有store save208ms/load759ms。操作入力から描画までのレイテンシ値ではない。
- 実写真3場面のAndroid JPEG出力は参照との中央値ΔE76が2.0251/1.6686/1.6689。プレビューと原寸GPUとの差は最大1 code、原本hashは不変。建物・植物の実機出力比較について、利用者から十分近いとの評価を得た。UI照合と通常アプリの結合検証はissue #67の工程記録を参照。

## 制約

出力はSDR sRGB相当の8bit JPEG。HDR/P3出力、16bit PNG/TIFF出力はない。初期RAWプレビューと原寸デモザイクには既存の差がある。既存入力のノイズ・シャープニング・局所トーンマッピングを取り除く処理は追加しない。

校正の暗い高彩度の青緑には大きな誤差が残る。肌、追加の光源、Galaxy S25 Ultra入力、撮影時CNTとの等価性、利用者の好みは別に評価する。粒子は未校正のため既定オフ。Customは現在の編集セッション内で保持する設定で、写真をまたぐユーザープリセット管理ではない。

最終WBタブの通常400dp/font1.0と、テスト用に400x640dpへ制限したfont1.3のUI操作を実機で再検査しPASS。参照との独立画面評価でも操作を妨げる差は見られなかった。短画面のDialog位置はテスト専用に補正し、補正前後をcapture JSONに記録しているため、短い実端末そのものの配置確認とは区別する。指操作、IME、TalkBackは未検証。

通常版0.24.0/code146でも、一覧からの編集起動、変更後Backの取消・再生成、MediaStoreへのJPEG保存と寸法・色、保存中の取消後の出力残存なし、原本bytes不変を結合検査しPASS。外部エディターは起動していない。Cold ReviewとSecurity Reviewは修正後の再評価で範囲内の未解決指摘なし。
