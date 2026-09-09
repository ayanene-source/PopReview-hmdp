# PopReview HMDP

[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

Spring Boot を使用したローカル生活サービス向けバックエンドです。店舗検索、SMS ログイン、ブログ操作、フォロー配信、クーポンのフラッシュセールを提供し、Redis によるキャッシュ保護、高負荷制御、非同期ピークシフトを実装しています。

## 技術スタック

- Java 8、Spring Boot 2.3、MyBatis-Plus
- MySQL 5.x、Redis、Redis Stream
- Redisson、Lua、Nginx

## 主な機能

- **店舗キャッシュ**: null 値キャッシュでキャッシュ貫通を防止します。論理有効期限と排他ロックでホットキー再構築時の DB 負荷を抑え、Lua でロック解放をアトミックに実行します。
- **ログインとチェックイン**: Redis Hash とトークンでログイン状態を管理し、インターセプターでトークン TTL を更新します。チェックインと連続日数の集計には Bitmap を使います。
- **ソーシャル機能**: ブログへの「いいね」、いいねランキング、フォロー関係、Redis ZSet による Feed のスクロールページングをサポートします。
- **フラッシュセール注文**: Lua スクリプトが在庫と一人一注文の条件をアトミックに検証し、注文を `stream.orders` に書き込みます。コンシューマーグループが非同期で注文を作成し、pending list、Redisson ロック、トランザクションによって売り越し、重複注文、メッセージ処理失敗のリスクを下げます。
- **グローバル ID**: タイムスタンプと Redis のインクリメントカウンターから、時系列順の注文 ID を生成します。

## フラッシュセールの流れ

```text
フラッシュセール要求
  -> Redis Lua: 在庫と購入条件を検証し、在庫を予約
  -> Redis Stream: 注文メッセージを発行し、注文 ID を返却
  -> コンシューマーグループ: 注文を非同期で処理
  -> ユーザー単位の Redisson ロック + トランザクション: 検証、DB 在庫減算、注文作成
  -> ACK。失敗したメッセージは復旧用に pending list に残る
```

## ローカルでの実行

### 1. 前提条件

- JDK 8 以降
- Maven 3.6 以降
- MySQL（`hmdp` データベースを作成）
- Redis 6 以降

初期データをインポートします。

```bash
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 2. 環境変数の設定

アプリケーションはデフォルトでポート `8081` を使用します。ローカルの認証情報をリポジトリにコミットしないよう、DB と Redis の接続情報は環境変数で設定してください。

```text
HMDP_DB_URL=jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
HMDP_DB_USERNAME=your_mysql_user
HMDP_DB_PASSWORD=your_mysql_password
HMDP_REDIS_HOST=127.0.0.1
HMDP_REDIS_PORT=6379
HMDP_REDIS_PASSWORD=your_redis_password
HMDP_IMAGE_UPLOAD_DIR=/absolute/path/to/nginx/html/hmdp/imgs
```

### 3. アプリケーションの起動

```bash
mvn spring-boot:run
```

または、パッケージ化して起動します。

```bash
mvn clean package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## 関連ドキュメント

- [プロジェクトのポイントと面接メモ（中国語）](docs/hmdp-interview-prep.md)
- [履歴書向けプロジェクト説明（中国語）](docs/resume-project-notes.md)

## 補足

本プロジェクトは学習・デモ用のモノリシックなバックエンドです。本番運用では、複数インスタンス間のコンシューマー協調、リトライとデッドレター処理、監視・アラート、設定管理、シークレット管理を追加することを推奨します。
