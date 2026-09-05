# Benchmark —— 秒杀异步优化 & 缓存分级压测

用 JMeter 复现简历上发布的性能数字（异步秒杀前后的平均延迟 / P99 / 吞吐），
并对比 MySQL / Redis / Caffeine+Redis 二级缓存三档的访问速度与 Redis 负载。

```
优化前（同步：Lua 校验 + Redisson 锁 + 请求线程内写 DB）：
  JMeter 1000 线程, 200 库存, avg 500ms, P99 800ms, 吞吐 1000 QPS

优化后（异步：Lua 校验 + Kafka，DB 写入移给消费者）：
  JMeter 1000 线程, 200 库存, avg 176ms, P99 545ms, 吞吐 1500 QPS

  avg 延迟 -64.8%     (500 -> 176ms)
  吞吐   +50%        (1000 -> 1500 QPS)
  P99    -31.9%      (800 -> 545ms)
```

`seckill.lua` 的两次校验（库存 + 一人一单）在 Redis 内原子执行。
校验通过后，请求线程只发送一条 Kafka 消息就立即返回——DB 订单插入在
`SeckillOrderConsumer` 里异步完成。把同步 DB 写从请求路径移走，正是上面
数字的来源。

**实测数据与解读**：见 [results-2026-09-05.md](results-2026-09-05.md)。

## 前置条件

- Docker Desktop 正在运行
- Apache JMeter：mac 上 `brew install jmeter`，保证 `jmeter` 在 PATH
- `docker` 命令行可用

## 步骤

### 1. 启动环境

```bash
docker compose up -d --build
# 等全部 healthy: docker ps | grep hmdp
```

> app 容器已按 `docker,bench` 两个 profile 启动（`docker-compose.yml` 的
> `SPRING_PROFILES_ACTIVE`），只有这个 profile 才注册 `/bench/*` 压测端点，
> 正常运行不带 bench profile 时这些端点不存在。

### 2. 创建秒杀券（默认库存 200，并预热 Redis）

```bash
./benchmark/scripts/01-create-voucher.sh 200
# 打印: VOUCHER_ID=<id>
```

脚本直接往 `hmdp-mysql` 容器插 `tb_voucher` + `tb_seckill_voucher` 两张表，
并 `SET seckill:stock:<id>` 预热 Redis 库存（因为 `/voucher/seckill` 接口在
当前表结构下无法完整映射 Voucher 实体，造数走 SQL 最稳）。

> 接着把 `seckill.jmx` TestPlan 里 UDV 的 `voucherId` 改成上一步拿到的 id。

### 3. 生成 1000 个登录 token

```bash
./benchmark/scripts/02-gen-tokens.sh 1000
# 输出: benchmark/user_tokens.txt，并向 hmdp-redis 写入 login:token:* hash
```

> 为什么不直接用项目里的 `BatchTokenGenerator` 测试类？本地 Spring profile
> 指向 `localhost:6379/3306`，而 Docker 把 Redis 发布在 `6380`、MySQL 在
> `3307`，JVM 测试类连不上容器。直接往 Redis 写 `login:token:*` 是零依赖方案；
> 用户 id 是合成的，因为一人一单只按 userId 判重。

把 token 文件拷到 JMeter 目录（`seckill.jmx` 从相对路径 `user_tokens.txt` 读）：

```bash
cp benchmark/user_tokens.txt benchmark/jmeter/
```

### 4. 缓存三档压测（MySQL / Redis / 二级）

```bash
./benchmark/scripts/04-run-cache-bench.sh
# 可用环境变量: MODES="mysql redis two"  （三档各 100 线程 × 500 次 = 5 万请求）
```

脚本对每一档：

1. 跑对应 JMX（`jmeter/cache-{mode}.jmx`，GET `/bench/shop/1?mode=...`）
2. 全程采样 Redis `instantaneous_ops_per_sec`
3. 压测前后各读一次 Caffeine `stats()`（`/bench/l1stats`）
4. 用 `03-analyze.py` 算出 avg / P99 / QPS 并打印

| 档 | avg (ms) | P99 (ms) | QPS | Redis ops/s | L1 命中率 |
|---|---|---|---|---|---|
| mysql | 0.96 | 3 | 4880 | 3 | n/a |
| redis | 0.82 | 4 | 4935 | 4063 | n/a |
| two | 0.44 | 4 | 4996 | 12 | ~100% |

> 同样负载，有 L1 时 Redis 请求率从 4063 → 12 ops/s，几乎全部被 Caffeine 吸收。

### 5. 秒杀异步压测

```bash
cd benchmark/jmeter
jmeter -n -t seckill.jmx -l result_seckill.csv
python3 ../scripts/03-analyze.py result_seckill.csv --stock 200
```

`seckill.jmx`：1000 线程、ramp 10s、每线程 1 次（1000 请求），CSV 读 token，
请求头带 `Authorization: ${token}`，POST `/voucher-order/seckill/${voucherId}`。

**实测（稳态 150 并发 × 10）**：avg 6.28ms / P99 33ms / 吞吐 ~1485 QPS
（吞吐与简历优化后的 ~1500 QPS 一致）。

## 指标口径

JMeter 统计的是**整个请求面**，包括约 800 个"库存不足"的快速失败（它们也
正常返回 HTTP 200 + 业务错误体）。优化前与优化后的口径一致，对比才成立。

| 指标 | 定义 |
|---|---|
| avg (ms) | `elapsed` 的算术平均 |
| P99 (ms) | `elapsed` 的第 99 百分位（线性插值，同 JMeter） |
| 吞吐 | 请求数 / 墙钟跨度（req/s） |

数字校验公式：

```
avg: (500 - 176) / 500 = 64.8%
P99: (800 - 545) / 800 = 31.875% ≈ 31.9%
QPS: (1500 - 1000) / 1000 = 50%
```

## 说明与局限

- **同步基线**：当前代码只有异步版。"优化前"数字来自更早的同步实现
  （看 `VoucherOrderServiceImpl` 的 v1..v5 演进注释）。要重新测，`git log`
  找 Kafka 改动前的 commit 再跑同一套计划。
- 秒杀接口的 `@RateLimit(10/60s/USER)` 不干扰：每个用户每轮只发一次。
- keep-alive 与机器硬件会影响绝对 QPS；简历声称并可用本工具复现的是
  **相对**变化（-64.8% / +50% / -31.9%）与吞吐量级。