# LoupeのOTA更新

GitHub Releasesの `ktnyt/Loupe` とObtainiumを使う。スマホで更新を確認し、Androidの通常インストーラーで同じ署名のLoupeを上書き更新する。写真・設定・履歴を消す操作は行わない。

## スマホ側

Obtainiumへ `https://github.com/ktnyt/Loupe` を追加する。stable Releaseの `loupe.apk` を対象とし、pre-releaseや検査APKを選ばない。更新が見つかったらダウンロードし、Androidの確認画面で更新する。提供元のインストール許可が必要な場合はObtainiumだけを許可する。

## 配布側

配布元は最新mainのcleanなソースから `scripts/deploy-apk.py prepare` で作ったmode=mainのbundle。mainのcommit/treeとmodeを署名対象assetへ埋め込み、APK hash・署名・版・4必須機能を照合する。現行署名を保ち、recoveryや検査用APK・個人データ・鍵を公開しない。

Releaseはvバージョンtagをsource commitへ対応させ、draftでAPK/bundleを添付して再取得したhashを確認してから公開する。既存tag/Releaseを上書きしない。署名鍵を変更すると既存のLoupeへ通常の更新ができなくなるため、配布用の署名を維持する。

初回のOTA確認では、更新直後の端末APKを元のmain bundleと読み戻して照合する。OTAで既に更新したAPKに対してADB installをやり直さず、confirmでmain・祖先・署名・hash・版・必須機能を検査し、pendingを閉じる。

更新後の実機確認とデータ保全を、単にダウンロードできたことと区別してissueへ記録する。OTAはGoogle Playの自動更新ではなく、ObtainiumとAndroidインストーラーによる配布。

参照: [Obtainium](https://github.com/ImranR98/Obtainium)、[Androidの配布とインストール許可](https://developer.android.com/distribute/marketing-tools/alternative-distribution)。
