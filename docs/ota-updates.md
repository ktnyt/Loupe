# LoupeのOTA更新

GitHub Releasesの `ktnyt/Loupe` とObtainiumを使う。スマホで更新を確認し、Androidの通常インストーラーで同じ署名のLoupeを上書き更新する。写真・設定・履歴を消す操作は行わない。

## スマホ側

Obtainiumへ `https://github.com/ktnyt/Loupe` を追加する。stable Releaseの `loupe.apk` を対象とし、pre-releaseや検査APKを選ばない。更新が見つかったらダウンロードし、Androidの確認画面で更新する。提供元のインストール許可が必要な場合はObtainiumだけを許可する。

## mainからの自動配布

公式リポジトリのmainへpush（PRのマージを含む）すると、`.github/workflows/release.yml` がhost回帰検査、Androidのビルド、lint、実APKの署名と必須機能の検査を実行する。検査用apksignerはbuild-tools 35.0.0へ固定し、runnerに同居する新しいSDKへ自動で切り替えない。検査成功後の別jobで既存署名を復元し、最新mainから配布APKを作り、既存のdraft/hash検査を経てstable Releaseを公開する。PRの検査jobには配布鍵や書き込みtokenを渡さない。

版は `10d1f68`（0.31.3 / code 167）からmainのfirst-parentを進んだ数で決まる。1回のmergeではpatchとversionCodeが1ずつ増え、feature branch内のコミット数には影響されない。同じmain sourceの再ビルドは同じ版になる。recoveryやPRのビルドは祖先に含む最新の公式mainの版を使い、branch内のコミット数で版を増やさない。recoveryをmainへマージした後の通常版はそれより新しくなる。full historyと基点が必要で、手動での版上げコミットは不要。

配布は同時実行せず、開始時にmainが進んでいた古いrunは公開を省略する。asset転送中にmainが進んだ場合も公開直前の再確認で停止し、draftと作成済みtagを残す。同じ版が公開済みならsource・署名・APK・assetを再検証し、上書きせず成功として扱う。失敗したdraftや別sourceの同名tagは自動で修復しない。Actionsの失敗ログとReleaseの状態を確認して対応する。

署名は `LOUPE_SIGNING_KEYSTORE_B64` というenvironment secretに登録した既存keystoreを使う。`loupe-release` environmentのdeployment branch policyはbranch `main`だけを許可し、repository secretには鍵を置かない。手動承認待ちは設けず、main以外のworkflowにはこの鍵を提供しない。runner内だけで復元し、証明書fingerprintを照合し、終了時に削除する。鍵がない・証明書が違う場合は公開を停止する。鍵をログやartifactへ含めない。

Obtainiumは公開Releaseを取得する。GitHubでの公開と端末での検知・更新操作・通知受信は別であり、Androidのバックグラウンド制限も影響する。通常の更新操作は引き続き端末で行う。

## 手動配布・照合

配布元は最新mainのcleanなソースから `scripts/deploy-apk.py prepare` で作ったmode=mainのbundle。mainのcommit/treeとmodeを署名対象assetへ埋め込み、APK hash・署名・版・4必須機能を照合する。現行署名を保ち、recoveryや検査用APK・個人データ・鍵を公開しない。

Releaseはvバージョンtagをsource commitへ対応させ、draftでAPK/bundleを添付して再取得したhashを確認してから公開する。既存tag/Releaseを上書きしない。署名鍵を変更すると既存のLoupeへ通常の更新ができなくなるため、配布用の署名を維持する。

初回のOTA確認では、更新直後の端末APKを元のmain bundleと読み戻して照合する。OTAで既に更新したAPKに対してADB installをやり直さず、confirmでmain・祖先・署名・hash・版・必須機能を検査し、pendingを閉じる。

配布時のコマンド例（出力先は毎回新しいディレクトリにする）:

```sh
python3 scripts/deploy-apk.py prepare --mode main --output /absolute/path/new-bundle
python3 scripts/publish-apk.py --bundle /absolute/path/new-bundle/bundle.json --notes-file /absolute/path/release-notes.md
```

端末でOTA更新した後の照合:

```sh
python3 scripts/deploy-apk.py confirm --bundle /absolute/path/new-bundle/bundle.json --serial DEVICE_SERIAL
```

配布中に失敗した場合、作成済みdraftとtagは削除せず残す。状態を確認してから対応し、同じ版の再実行で上書きしない。

更新後の実機確認とデータ保全を、単にダウンロードできたことと区別してissueへ記録する。OTAはGoogle Playの自動更新ではなく、ObtainiumとAndroidインストーラーによる配布。

参照: [Obtainium](https://github.com/ImranR98/Obtainium)、[Androidの配布とインストール許可](https://developer.android.com/distribute/marketing-tools/alternative-distribution)。
