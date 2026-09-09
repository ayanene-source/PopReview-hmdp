# PopReview HMDP

[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

基于 Spring Boot 的本地生活服务平台后端。项目覆盖商户查询、短信登录、博客互动、关注推送与优惠券秒杀等业务场景，并重点演示 Redis 在缓存治理、高并发控制和异步削峰中的应用。

## 技术栈

- Java 8、Spring Boot 2.3、MyBatis-Plus
- MySQL 5.x、Redis、Redis Stream
- Redisson、Lua、Nginx

## 核心能力

- **商户缓存**：使用空值缓存防止缓存穿透；使用逻辑过期与互斥锁降低热点 Key 缓存重建时的数据库压力；Lua 脚本保证解锁操作的原子性。
- **登录与签到**：以 Redis Hash 和 Token 维护登录态，并通过拦截器刷新有效期；使用 Bitmap 实现签到和连续签到统计。
- **社交互动**：支持博客点赞、点赞排行榜、关注关系与基于 Redis ZSet 的 Feed 流滚动分页。
- **秒杀下单**：Lua 脚本在 Redis 中原子完成库存与“一人一单”预校验，并将订单写入 `stream.orders`；消费者组异步创建订单，结合 pending list、Redisson 锁和事务处理降低超卖、重复下单与消息处理失败风险。
- **全局 ID**：使用时间戳与 Redis 自增计数生成趋势递增的订单 ID。

## 秒杀流程

```text
请求秒杀
  -> Redis Lua：校验库存与购买资格、预扣库存
  -> Redis Stream：写入订单消息并立即返回订单 ID
  -> Consumer Group：异步消费订单消息
  -> Redisson 用户锁 + 事务：校验、扣减数据库库存、创建订单
  -> ACK；失败消息保留在 pending list 以便补偿处理
```

## 本地运行

### 1. 准备依赖

- JDK 8+
- Maven 3.6+
- MySQL（创建 `hmdp` 数据库）
- Redis 6+

导入初始化数据：

```bash
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 2. 配置环境变量

应用默认监听 `8081` 端口。建议通过环境变量提供数据库和 Redis 连接信息，避免将本地凭据提交到仓库：

```text
HMDP_DB_URL=jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
HMDP_DB_USERNAME=your_mysql_user
HMDP_DB_PASSWORD=your_mysql_password
HMDP_REDIS_HOST=127.0.0.1
HMDP_REDIS_PORT=6379
HMDP_REDIS_PASSWORD=your_redis_password
HMDP_IMAGE_UPLOAD_DIR=/absolute/path/to/nginx/html/hmdp/imgs
```

### 3. 启动

```bash
mvn spring-boot:run
```

或构建后运行：

```bash
mvn clean package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## 项目文档

- [项目亮点与面试准备](docs/hmdp-interview-prep.md)
- [简历项目说明](docs/resume-project-notes.md)

## 说明

本项目为学习与演示用途的单体后端。生产化部署时，建议进一步完善多实例消费者协调、失败重试与死信队列、监控告警、配置中心和敏感信息管理。
