# ポイントカーブの検証

対象はIssue #63、Android検証端末A、実験アプリ0.5.0/code8と通常版0.22.0/code144。旧実験パラメータの破棄は利用者の指定であり、旧数値で旧仕上がりを再現する互換モードは設けない。原本と既存JPEGを変更しない。契約は[tone-curve-contract.md](tone-curve-contract.md)。

## 数値・画質

| 対象 | 方法と結果 |
| --- | --- |
| 補間 | ホストで128曲線/128万サンプル。端点・節点・非減少・平坦区間・有限値・最小間隔・不正入力を検証しPASS |
| LUT | 8193 float32サンプル、解析PCHIPとの最大絶対誤差0.000120163（8bitの0.031 code相当、許容0.0003） |
| ロールオフ | r=0〜1、明度0〜32で有限・非減少、接続の連続性を確認 |
| 色域 | 10000色で出力範囲/有限値を確認。Oklabの色相誤差最大0.000276 rad（chroma>0.0001）。ほぼ無彩色では角度を評価せずRGB範囲と有限性を確認 |
| 6条件 | 暗部の微細差、白近傍の低コントラスト、鮮色、滑らかな勾配、露出過不足、新recipeの往復を合成データで検証しPASS |
| CPU/GPU | 端末で6種類×1024サンプル、全bank・カラー/モノクロ・比較を検証。最大誤差1/255。固定ディザ単独の段差も1 code以下 |
| タイル/保存 | 曲線5本を含む原寸のタイルと非タイル結果を2 code以内で検証。JPEG保存/cancel/原寸準備・待ち・再試行の回帰PASS。実Activityから6000×4000のJPEGを保存し、最新レシピ・寸法と自己生成物cleanupを確認 |
| 公開RAW | 既存manifestのDNG（Blackmagic Micro Cinema Camera、CC0）/ORF（Olympus C5050Z、CC-BY-NC-SA 4.0）をSHA照合し、LibRaw→2048px新tone→JPEG生成PASS。private test fixtureは終了後削除 |

`tests/raw-edit-state.sh`、`tests/raw-full-source.sh`、`scripts/test-raw-editor-full-profile.py`を実行しPASS。GPUは`RawEditInstrumentation`のtone/full、公開RAWはrealを実行した。数値検査は花弁などの実写真の印象を保証するものではない。撮像・LibRaw段階で失われた情報は復元しない。

## 操作と画面

端末instrumentationのcurve/cursor/uiで点追加・相対ドラッグ・端点・選択・削除・bank保持・数値入力・Undo/Redo・リセット・ピンチ/CANCEL・スライダーの操作単位・比較・Bundle再生成を検証しPASS。描画待ちのMOVEを残したstop/startコールバックでも、操作の取消・再読込・最新frameの到着を確認した。未確定数値を残した選択移動・点削除・リセット後のDoneでも別の点を変更しない。値の小数表示を未編集の座標へ戻さない。

400dp通常/font1.0、400×640dp/font1.3、320×640dp/font1.3で描画完了後にPixelCopyした。Claude完成HTMLとの[通常比較](design/loupe63-comparison-normal.png)・[短い画面比較](design/loupe63-comparison-short.png)は左が参照、右が端末。通常は高さの丸め1px差がある。写真は参照側がSVG近似、端末側が合成画像なので色の一致を判定しない。既存ヘッダーやタブの字体差は残る。短画面のタブは既存の横スクロールを維持する。

独立UI評価後に[選択端点のリング](design/loupe63-endpoint.png)を追加し、再評価で対応必須の不一致・切れなし。Cold Reviewの範囲内指摘を修正して再評価を受けた。security-reviewに沿う独立静的確認で対応必須指摘なし。数値境界、固定LUTとGL解放、不変snapshot、別JPEG/失敗cleanup、debug gate、診断の匿名性を確認した。ネイティブライブラリの脆弱性監査ではない。

通常版0.22.0/code144のphoto-edit検査もPASS。内部現像Activity、Mono/変更後の戻る操作・再生成、別JPEGの寸法・色・原本不変、保存中の戻る操作によるcancelと自己生成物cleanup、失敗/retryを確認した。外部editorは起動しておらず、その保存互換性は別の検証範囲。

## 性能

同じ端末でYouTubeを停止しPiP無し、6000×4000合成画像の原寸準備後にトーンを6回連続ドラッグする。入力からframe commitまでを計測し、display scanoutは含まない。各1試行の比較であり、実RAWの初回デコード待ちを短縮したという結果ではない。

| 指標 | 旧b705bf9 | 新6847990 |
| --- | ---: | ---: |
| 入力数 / commit数 | 656 / 641 | 652 / 647 |
| commit間隔の中央値 | 16.58ms | 16.60ms |
| 従来のrequest→commit 中央値 / p95 | 21.08 / 25.46ms | 38.42 / 42.10ms |
| 最後の入力→commit（各ドラッグ） | 43.63〜49.79ms | 33.87〜48.54ms |
| graphics PSS | 59260KB | 83044KB |
| 原寸準備（この試行） | 15.208s | 28.680s |
| バッテリー温度 | 32.2→32.2℃ | 32.0→32.0℃ |

描画頻度は旧版と同程度まで戻った。一方、従来指標の反映遅延は約1フレーム増える。増加に寄与した要因は分離できておらず、遅延も旧版と同じとは扱わない。旧版はXY面、新版はポイントグラフを操作する条件差がある。新しく計測した編集callback開始→commitは40.44 / 44.04ms（中央値 / p95）。この指標は同期UI処理を含み、従来のrequest指標と直接比較しない。ハードウェアの入力採取やイベント配信遅延は含まない。

継続要求のenqueue→worker開始は0.202 / 0.347ms、worker重複ticketは0、最後の要求とcommitはticket656で一致し、最後の入力から100ms後以降のworker開始は0だった。LUT CPU / upload呼出 / draw-readbackの中央値は3.21 / 0.152 / 5.60ms。ドライバーの遅延実行はreadbackへ計上され得る。値は[匿名の測定結果](diagnostics/issue63-performance.json)に保存した。

実Activityの6000×4000 JPEG保存は1試行24.715s（保存要求からpublish完了まで）。旧版の同じ保存経路の時間は未計測なので速度比較には使わない。

ソースRGBA32FのためGPU使用量は増える。総PSSはGCの影響が大きく、当該試行は旧218871KB/新273930KB。原寸準備時間は新旧とも1試行で変動要因を分離できておらず、準備や実RAWの初回デコードが速くなったとは主張しない。

## 未確認の範囲

- 実写真の花弁・暗部・鮮色・空に対する主観評価、ディスプレイの個体差、実指で密集点を操作した感触。
- 実IME・TalkBackの通し操作。数値入力・仮想アクセシビリティノード・物理キーの経路はinstrumentationで確認した。
- プレビューと出力は共通shaderだが、表示縮小と原寸の画素対応・JPEG量子化の差は残る。全画素一致を保証しない。
- named presetや画像ごとの永続recipeストアは既存アーキテクチャに存在せず、今回追加しない。新recipeはBundleと保存jobで保持し、Undo履歴はActivity再生成をまたがない。
