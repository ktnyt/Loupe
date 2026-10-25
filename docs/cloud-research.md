# Amazon Photosの接続調査

2026年10月21日。公開資料の確認とアカウントでの実測は区別する。

## 確認した公開資料

- https://community.amazondeveloper.com/t/amazon-drive-api-documentation/27885
  - 2026年3月の回答では旧Amazon Driveの直接の後継はないと案内されている。
- https://github.com/trevorhobenshield/amazon_photos
  - ログインCookieを使う非公式クライアント。検索、アップロード、ダウンロードを備える。
- https://github.com/trevorhobenshield/amazon_photos/blob/main/amazon_photos/_api.py
  - 国別ホストの `/drive/v1/search` をGETし、JSON内の `count` と `data` を読む。
  - 公開実装の接続先判定は北米と欧州を扱い、日本向けのアップロード先を判定する処理がない。
  - 非同期通信にTLS検証を無効化する既定値が見られる。その設定は採用しない。
- https://github.com/trevorhobenshield/amazon_photos/issues/32
  - 2026年2月の利用報告。認証失敗時の再試行と重複行などの修正報告を含む。
- https://github.com/entourloop/amazon-photo-uploader
  - Cookieを利用したアップロード実装。接続リージョンの確認が必要。
- https://digprjsurvey.amazon.co.uk/csad/help/node/GGU2SU8Y22DZYRMQ
  - ORFを挙げ、DNGは限定対応としている。個別機種の完全な原本保存は実ファイルで調べる必要がある。

## 試作で実装する診断

日本向けの `https://www.amazon.co.jp/photos` をアプリ内で開く。
利用者によるログイン後、同じホストの写真検索へ上限１件のGETを送る。
正常なJSONの `count` と `data` を確認してから読み取り接続が成功したと表示する。

Cookieの存在だけではログイン成功としない。
401/403、リダイレクト、HTML、通信失敗を成功扱いしない。
Cookieを別ホストに転送しない。

## 未検証

- 現在の日本アカウントにおける写真検索の動作。
- WebViewログインの可否と認証の持続時間。
- 現在の日本向けアップロード方式。
- ４機種の原本を再取得した際のハッシュ一致。
- バックグラウンド実行、認証切れ、重複送信からの復帰。

これらが未確認の間、自動同期や原本の削除を有効にしない。
