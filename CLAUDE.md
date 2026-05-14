# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工作规则

- **提交规范**：每次代码改动完成后，自动 `git add` + `git commit`（中文 feat/fix/docs 格式，说明做了什么）+ `git push origin master && git push github master`，无需等用户确认。
- **CLAUDE.md 同步**：每次新增功能或架构变更，同步更新本文档对应章节。
- **注释保留**：不删除现有注释（尤其是 TODO、演进说明、历史方案标记），只在需要时新增注释。注释记录了项目的优化历程，有价值。

## 项目概述

黑马点评 — Spring Boot 本地生活服务点评平台。技术栈：Spring Boot 2.3.12 + MyBatis-Plus 3.4.3 + Redis + MySQL + Redisson 3.13.6 + Docker。

## 开发/构建命令

```bash
# Docker 一键启动（推荐）
docker compose up -d --build

# 单独重新构建某个服务
docker compose build nginx
docker compose up -d nginx

# 停止并清理
docker compose down -v

# 本地 Maven 构建（不用 Docker）
mvn clean package -DskipTests

# 查看日志
docker logs hmdp-app
docker logs hmdp-mysql
docker logs hmdp-nginx
```

## Docker 环境（Apple Silicon 已知问题）

`docker-compose.yml` 已针对 ARM64 Mac 做了兼容修复：
- **MySQL 8.0**（原为 5.7，ARM64 无 5.7 镜像），认证方式 `mysql_native_password`，SQL mode 为 `NO_ENGINE_SUBSTITUTION`
- **Nginx 前端文件**直接 COPY 进镜像（非 volume 挂载），因为 macOS Docker virtiofs 在读取挂载文件时会出现 `pread() failed (35: Resource deadlock)` 和 `sendfile() failed`，修复措施：`sendfile off` + `chmod a+r`

服务端口：
| 服务 | 端口 |
|------|------|
| Nginx 前端 | 8080 |
| 后端 API | 8081 |
| phpMyAdmin | 8082 |
| Redis Commander | 8083 |

## 架构要点

### 秒杀系统（核心亮点）

完整的三代方案演进记录在 `VoucherOrderServiceImpl.java`（注释中保留了前两版）：

```
用户请求 → seckill.lua（原子化校验+扣库存+防重）→ Redis Stream → 异步消费 → 生成订单
```

- **Lua 脚本**：`src/main/resources/seckill.lua` — 判断库存 → 判断重复 → 扣 Redis 库存 → 标记用户 → XADD 到 Stream
- **消息队列**：Redis Stream Consumer Group（`g1`），`@PostConstruct` 启动单线程消费者
- **异常处理**：消费失败进入 Pending-List，`handlePendingList()` 从 `0` 偏移量重读
- **一人一单**：`handlerOrder()` 用 Redisson 锁 `lock:order:{userId}`，`createVoucherOrder()` 内 DB 判重 + 乐观锁 `WHERE stock > 0`
- **消费组**需手动创建或应用启动时创建：`XGROUP CREATE stream.orders g1 0 MKSTREAM`

### 缓存策略（`ShopServiceImpl.java` 三种方案）

1. `queryWithPassThrough()` — 缓存空值防穿透（TTL 2分钟）
2. `queryWithMutex()` — SETNX 互斥锁 + Double Check 防击穿
3. `queryWithLogicalExpire()` — 逻辑过期 + 线程池异步重建（最优方案，需配合 `RedisData` 封装）

缓存更新：先写 DB 后删缓存（`Cache-Aside`）。删除失败时发布到 Redis Stream `stream.cache-invalidate`，由 `CacheInvalidateService` 退避重试（1s/2s/3s），最大 3 次后放弃，依赖 TTL 兜底保证最终一致性。

### 滑动窗口限流

`@RateLimit(permits, windowSeconds, keyType)` 注解 + AOP 切面 `RateLimitAspect`，支持全局/IP/用户三维度。通过 Lua 脚本 `rate_limit.lua`（ZADD + ZREMRANGEBYSCORE + ZCARD）原子校验 Redis Sorted Set 滑动窗口，超频抛出 `RateLimitException`，由 `WebExceptionAdvice` 统一返回"请求过于频繁"。

- 秒杀接口：10次/60秒/用户
- 发验证码：1次/60秒/IP

### 分布式 ID 生成器（`RedisIdWorker.java`）

类 Snowflake：高 32 位时间戳（2026-01-01 基准偏移）+ 低 32 位 Redis 自增序列号（key 按天分组 `icr:order:yyyy:MM:dd`），不依赖机器码。

### 分布式锁

- **手写版**：`SimpleRedisLock.java` — SET NX + Lua 原子释放（`unlock.lua`，判断线程标识再删除）
- **生产版**：Redisson `RLock` — 带 WatchDog 自动续期

### 认证

Token 存 Redis Hash，`LoginInterceptor` 从 Header `Authorization` 取 token → 查 Redis → 写入 `UserHolder`（ThreadLocal）→ `afterCompletion` 清理防止内存泄漏。每次请求自动刷新 token TTL。

## 数据库

`src/main/resources/db/hmdp.sql` 在容器首次启动时自动导入。包含 tb_blog、tb_blog_comments、tb_follow、tb_seckill_voucher、tb_shop、tb_shop_type、tb_user、tb_user_info、tb_voucher、tb_voucher_order 十张表。

