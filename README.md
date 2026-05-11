# hm-dianping 点评平台

基于 Spring Boot + MyBatis-Plus + Redis 开发的生活服务点评平台，实现商户查询、优惠券秒杀、探店博客等功能。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.3.12 | 应用框架 |
| MyBatis-Plus | 3.4.3 | ORM |
| MySQL | 5.7 | 数据库 |
| Redis | 7 | 缓存 / 分布式锁 / 消息队列 |
| Redisson | 3.13.6 | 分布式锁 |
| Nginx | 1.18 | 前端静态资源 / 反向代理 |
| Docker | — | 容器化部署 |
| Lombok | 1.18.30 | 代码简化 |
| Hutool | 5.7.17 | 工具库 |

## 功能模块

- **商户查询** — 缓存穿透（空值缓存）、缓存击穿（互斥锁 / 逻辑过期双方案）
- **秒杀下单** — Lua 脚本原子化库存扣减 + Redis Stream 消息队列异步下单 + 一人一单
- **用户登录** — 手机验证码登录，Redis Token 会话管理
- **探店博客** — 发布 / 点赞 / 热点查询
- **文件上传** — 图片上传（UUID 分目录存储）

## 项目结构

```
hm-dianping/
├── src/main/java/com/hmdp/
│   ├── config/          # 配置类（MVC、MyBatis、Redisson）
│   ├── controller/      # 控制器
│   ├── service/         # 服务接口及实现
│   ├── mapper/          # MyBatis Mapper
│   ├── entity/          # 实体类
│   ├── dto/             # 数据传输对象
│   └── utils/           # 工具类（Redis锁、ID生成器、拦截器）
├── src/main/resources/
│   ├── application.yaml          # 本地配置
│   ├── application-docker.yml    # Docker 环境配置
│   ├── db/hmdp.sql               # 数据库初始化脚本
│   ├── seckill.lua               # 秒杀 Lua 脚本
│   └── unlock.lua                # 分布式锁释放 Lua 脚本
├── frontend/html/       # 前端静态文件
├── docker/              # Docker 配置
│   ├── nginx/nginx.conf
│   └── mysql/my.cnf
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## 快速开始

### 本地启动

1. 确保本地 MySQL 和 Redis 已启动
2. 导入数据库：执行 `src/main/resources/db/hmdp.sql`
3. 修改 `application.yaml` 中的数据库密码（默认 1234）
4. 启动应用：
```bash
mvn spring-boot:run
```
5. 启动前端 Nginx（或直接用 Docker 的 nginx）
6. 访问：http://localhost:8080

### Docker 启动（一键启动全部服务）

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

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/shop/{id}` | 查询商铺详情 |
| GET | `/shop/of/type?typeId=&current=` | 按类型分页查询 |
| POST | `/shop` | 新增商铺 |
| PUT | `/shop` | 更新商铺 |
| GET | `/shop-type/list` | 商铺类型列表 |
| POST | `/voucher` | 新增普通券 |
| POST | `/voucher/seckill` | 新增秒杀券 |
| GET | `/voucher/list/{shopId}` | 查询店铺优惠券 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单 |
| POST | `/user/code` | 发送验证码 |
| POST | `/user/login` | 登录 |
| GET | `/user/me` | 当前用户信息 |
| POST | `/blog` | 发布探店博客 |
| GET | `/blog/hot` | 热门博客 |
| PUT | `/blog/like/{id}` | 点赞博客 |
| POST | `/upload/blog` | 上传图片 |

## 缓存架构

```
请求 → Caffeine(计划中) → Redis → MySQL
        ↑ L1                 ↑ L2      ↑ 数据源
```

| 策略 | 用途 |
|------|------|
| 空值缓存（TTL 2分钟） | 防缓存穿透 |
| 互斥锁 + Double Check | 防缓存击穿 |
| 逻辑过期 + 异步重建 | 热点数据降级 |
| 先写 DB 后删缓存 | 数据一致性 |

## 秒杀流程

```
用户请求 → Lua脚本(原子校验+扣库存) → Redis Stream → 异步消费 → 生成订单
                                      ↓
                                 库存不足/重复 → 拒绝
                                      ↓
                                 消费者异常 → Pending List 重试
```
