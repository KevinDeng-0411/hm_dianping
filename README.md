# hm-dianping 点评平台

基于 Spring Boot + MyBatis-Plus + Redis 开发的生活服务点评平台，实现商户查询、优惠券秒杀、探店博客等功能。

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 2.3.12 | 应用框架 |
| MyBatis-Plus 3.4.3 | ORM |
| MySQL 8.0 | 数据库 |
| Redis 7 | 缓存 / 分布式锁 / 消息队列 / 滑动窗口限流 |
| Redisson 3.13.6 | 分布式锁 |
| Caffeine | 本地缓存（L1） |
| Lua | 秒杀原子操作 / 分布式锁释放 / 滑动窗口限流 |
| Nginx | 前端静态资源 / 反向代理 |
| Docker | 容器化部署 |

## 功能模块

- **商户查询** — Caffeine L1 + Redis L2 多级缓存，缓存穿透（空值缓存）、缓存击穿（互斥锁 / 逻辑过期双方案）
- **秒杀下单** — Lua 脚本原子化库存扣减 + Redis Stream 消息队列异步下单 + 一人一单
- **缓存一致性** — 先更新 DB 后删缓存，删除失败通过 Stream 补偿重试 + TTL 兜底
- **滑动窗口限流** — @RateLimit 注解 + AOP + Redis Lua 脚本，支持全局 / IP / 用户多维度
- **用户登录** — 手机验证码登录，Redis Token 会话管理
- **探店博客** — 发布 / 点赞 / 热点查询

## 项目结构

```
hm-dianping/
├── src/main/java/com/hmdp/
│   ├── annotation/       # 自定义注解（@RateLimit）
│   ├── aspect/           # AOP 切面（RateLimitAspect）
│   ├── config/           # 配置类（MVC、MyBatis、Redisson、Caffeine、Swagger）
│   ├── controller/       # 控制器
│   ├── service/          # 服务接口及实现（含 CacheInvalidateService）
│   ├── mapper/           # MyBatis Mapper
│   ├── entity/           # 实体类
│   ├── dto/              # 数据传输对象
│   ├── exception/        # 自定义异常（RateLimitException）
│   └── utils/            # 工具类（Redis锁、ID生成器、拦截器）
├── src/main/resources/
│   ├── application.yaml          # 本地配置
│   ├── application-docker.yml    # Docker 环境配置
│   ├── db/hmdp.sql               # 数据库初始化脚本
│   ├── seckill.lua               # 秒杀 Lua 脚本
│   ├── unlock.lua                # 分布式锁释放 Lua 脚本
│   └── rate_limit.lua            # 滑动窗口限流 Lua 脚本
├── frontend/html/       # 前端静态文件
├── docker/              # Docker 配置
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## 快速开始

### Docker 启动（推荐，一键启动全部服务）

```bash
docker compose up -d
```

| 服务 | 地址 |
|------|------|
| 前端页面 | http://localhost:8080 |
| 后端 API | http://localhost:8081 |
| phpMyAdmin（MySQL 管理） | http://localhost:8082 |
| Redis Commander（Redis 管理） | http://localhost:8083 |

数据库连接：`root` / `1234`

### 本地启动

```bash
mvn spring-boot:run
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/shop/{id}` | 查询商铺详情 |
| POST | `/shop` | 新增商铺 |
| PUT | `/shop` | 更新商铺 |
| GET | `/shop-type/list` | 商铺类型列表 |
| POST | `/voucher/seckill` | 新增秒杀券 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单（@RateLimit 限流） |
| POST | `/user/code` | 发送验证码（@RateLimit 限流） |
| POST | `/user/login` | 登录 |
| GET | `/blog/hot` | 热门博客 |
| PUT | `/blog/like/{id}` | 点赞博客 |

## 缓存架构

```
请求 → Caffeine(L1 本地, <1ms) → Redis(L2, ~2ms) → MySQL(L3, ~20ms)
        TTL 5min                    TTL 30min          数据源
```

| 策略 | 用途 |
|------|------|
| Caffeine 本地缓存（TTL 5分钟） | L1 加速热点数据 |
| 空值缓存（TTL 2分钟） | 防缓存穿透 |
| 互斥锁 + Double Check | 防缓存击穿 |
| 逻辑过期 + 异步重建 | 热点数据降级 |
| 先更新 DB 后删缓存 + Stream 补偿重试 | 数据一致性 |

## 秒杀流程

```
用户请求 → @RateLimit限流 → Lua脚本(原子校验+扣库存+防重) → Redis Stream → 异步消费 → 生成订单
                ↓                                    ↓                        ↓
              超频拒绝                          库存不足/重复 → 拒绝         异常 → Pending List 重试
```
