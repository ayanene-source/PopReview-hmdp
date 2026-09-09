# PopReview HMDP

[中文](README.md) | [English](README.en.md) | [日本語](README.ja.md)

A local-lifestyle services backend built with Spring Boot. It covers shop discovery, SMS login, blog interactions, follow feeds, and flash-sale vouchers, with an emphasis on Redis for cache resilience, high-concurrency control, and asynchronous load shedding.

## Technology Stack

- Java 8, Spring Boot 2.3, MyBatis-Plus
- MySQL 5.x, Redis, Redis Stream
- Redisson, Lua, Nginx

## Key Features

- **Shop caching**: Prevents cache penetration with null-value caching. Logical expiration and mutual exclusion locks reduce database pressure while rebuilding hot keys. Lua makes unlock operations atomic.
- **Login and daily check-in**: Maintains login state with Redis Hashes and tokens, refreshes token TTL in an interceptor, and uses bitmaps for check-in and consecutive-day statistics.
- **Social interactions**: Supports blog likes, like leaderboards, follow relationships, and Redis ZSet-based feed pagination.
- **Flash-sale ordering**: A Lua script atomically validates stock and one-order-per-user eligibility, then writes an order to `stream.orders`. A consumer group creates orders asynchronously; the pending list, Redisson locks, and transactions help prevent overselling, duplicate orders, and message-processing loss.
- **Global IDs**: Generates time-ordered order IDs from a timestamp and a Redis incrementing counter.

## Flash-sale Flow

```text
Flash-sale request
  -> Redis Lua: validate stock and eligibility; reserve stock
  -> Redis Stream: publish an order message and return the order ID
  -> Consumer group: consume the order asynchronously
  -> Per-user Redisson lock + transaction: validate, decrement database stock, create order
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
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 2. Configure environment variables

The application listens on port `8081` by default. Supply database and Redis credentials through environment variables rather than committing local credentials:

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
mvn spring-boot:run
```

Or package and run it:

```bash
mvn clean package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

## Additional Documentation

- [Project highlights and interview notes (Chinese)](docs/hmdp-interview-prep.md)
- [Resume project notes (Chinese)](docs/resume-project-notes.md)

## Notes

This is a monolithic learning and demonstration project. For production use, add multi-instance consumer coordination, retry and dead-letter handling, monitoring and alerting, centralized configuration, and stronger secret management.
