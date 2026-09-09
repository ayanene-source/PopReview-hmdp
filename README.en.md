# PopReview HMDP

[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

A Spring Boot backend for a local-lifestyle services platform. It includes shop discovery, SMS login, blog interactions, follow feeds, and flash-sale vouchers, demonstrating Redis-based cache resilience, high-concurrency control, and asynchronous load shedding.

## Technology Stack

- Java 8, Spring Boot 2.3, MyBatis-Plus
- MySQL 5.x, Redis, Redis Stream
- Redisson, Lua, Nginx

## Key Features

- **Shop caching**: Null-value caching prevents cache penetration. Logical expiration and mutual-exclusion locks reduce database pressure during hot-key rebuilds; Lua makes unlock operations atomic.
- **Login and check-in**: Redis Hashes and tokens hold login state, with token TTL refreshed in an interceptor. Bitmaps support check-in and consecutive-day statistics.
- **Social interactions**: Blog likes, like leaderboards, follow relationships, and Redis ZSet-based feed pagination.
- **Flash-sale ordering**: Lua atomically validates stock and one-order-per-user eligibility, then publishes to `stream.orders`. A consumer group creates orders asynchronously, while the pending list, Redisson locks, and transactions mitigate overselling, duplicates, and processing failures.
- **Global IDs**: Time-ordered order IDs generated from a timestamp and Redis incrementing counter.

## Flash-sale Flow

```text
Flash-sale request
  -> Redis Lua: validate stock and eligibility; reserve stock
  -> Redis Stream: publish an order message and return the order ID
  -> Consumer group: consume the order asynchronously
  -> Per-user Redisson lock + transaction: validate, decrement DB stock, create order
  -> ACK; failed messages remain in the pending list for recovery
```

## Run Locally

### 1. Prerequisites

- JDK 8+
- Maven 3.6+
- MySQL (create an `hmdp` database)
- Redis 6+

Import the initial data:

```bash
mysql -u root -p hmdp < hm-dianping/src/main/resources/db/hmdp.sql
```

### 2. Configure environment variables

The application listens on port `8081` by default. Provide database and Redis credentials through environment variables rather than committing local secrets:

```text
HMDP_DB_URL=jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
HMDP_DB_USERNAME=your_mysql_user
HMDP_DB_PASSWORD=your_mysql_password
HMDP_REDIS_HOST=127.0.0.1
HMDP_REDIS_PORT=6379
HMDP_REDIS_PASSWORD=your_redis_password
HMDP_IMAGE_UPLOAD_DIR=/absolute/path/to/nginx/html/hmdp/imgs
```

### 3. Start the application

```bash
cd hm-dianping
mvn spring-boot:run
```

Or package and run it:

```bash
cd hm-dianping
mvn clean package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## Additional Documentation

- [Project highlights and interview notes (Chinese)](hm-dianping/docs/hmdp-interview-prep.md)
- [Resume project notes (Chinese)](hm-dianping/docs/resume-project-notes.md)

## Notes

This is a monolithic learning and demonstration project. Production deployments should add multi-instance consumer coordination, retry and dead-letter handling, monitoring and alerting, centralized configuration, and stronger secret management.
