# Benchmark — Seckill Async Optimization

Load-test harness that reproduces the performance numbers published on the
project overview (avg latency / P99 / throughput before vs. after the async
seckill optimization).

```
Before (sync: Lua check + Redisson lock + DB write in the request thread):
  JMeter 1000 threads, 200 stock, avg 500ms, P99 800ms, throughput 1000 QPS

After  (async: Lua check + Kafka, DB write moved to the consumer):
  JMeter 1000 threads, 200 stock, avg 176ms, P99 545ms, throughput 1500 QPS

  avg latency -64.8%     (500 -> 176ms)
  throughput +50%        (1000 -> 1500 QPS)
  P99        -31.9%      (800 -> 545ms)
```

The two `seckill.lua` checks (stock + one-per-user) run atomically in Redis.
On success the request thread only sends a Kafka message and returns
immediately — the DB order insert happens later in `SeckillOrderConsumer`.
That removal of synchronous DB work is exactly what the numbers above capture.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) running
- [Apache JMeter](https://jmeter.apache.org/) (`brew install jmeter` on macOS)
- `jmeter` on `PATH`

## Steps

### 1. Start the stack

```bash
docker compose up -d --build
# wait for healthy:  docker ps | grep hmdp
```

### 2. Create a seckill voucher (stock 200, Redis pre-warmed automatically)

```bash
./benchmark/scripts/01-create-voucher.sh 200
# prints: VOUCHER_ID=<id>
```

Keep the printed voucher id. It also prints the Redis stock key
`seckill:stock:<id>` to confirm the warm-up (via `VoucherServiceImpl.addSeckillVoucher`).

### 3. Generate 1000 fake login tokens

```bash
./benchmark/scripts/02-gen-tokens.sh 1000
# writes: benchmark/user_tokens.txt, seeds login:token:* hashes in hmdp-redis
```

> Why not the shipped `BatchTokenGenerator` test class? The local Spring
> profile points at `localhost:6379/3306` while Docker publishes Redis on
> `6380` and MySQL on `3307`, so the plain JVM test cannot reach the
> containers. Writing the `login:token:*` hashes straight into Redis is
> zero-dependency. Synthetic `userId`s are fine — the one-per-user check keys
> on `userId` only.

### 4. Run the JMeter plan

```bash
cd benchmark/jmeter
cp ../user_tokens.txt .
DOCKER_JVM_ARGS="-Xms1g -Xmx1g" \
jmeter -n -t seckill.jmx \
       -JvoucherId=<id from step 2> \
       -l result_seckill.csv
```

`seckill.jmx`:
- 1000 threads, ramp-up 10s, 1 iteration each (`1000 * 1 = 1000` requests)
- reads `user_tokens.txt` for per-thread `Authorization` tokens
- `POST /voucher-order/seckill/${voucherId}` (host/port from User Defined
  Variables, override with `-Jhost`, `-Jport`)
- writes raw samples to `result_seckill.csv`

### 5. Analyze

```bash
python3 ../scripts/03-analyze.py result_seckill.csv --stock 200
```

prints avg / P99 / throughput and a delta table vs. the pre-optimization
baseline (defaults `500/800/1000`, override with `--baseline-*`).

## Metrics

JMeter measures the **whole request face**, including the ~800 "stock
exhausted" failures (they return fast and legitimately receive HTTP 200 with a
business-error body). Both the baseline and the current run use the same
criterion, so the comparison stays apples-to-apples.

| metric | definition |
|---|---|
| avg (ms)      | arithmetic mean of `elapsed` |
| P99 (ms)      | 99th percentile of `elapsed`, linear interpolation (JMeter convention) |
| throughput    | `samples / wall-clock span` (req/s) |

Formula check against the published numbers:

```
avg:  (500 - 176) / 500 = 64.8%
P99:  (800 - 545) / 800 = 31.875% ~ 31.9%
QPS:  (1500 - 1000) / 1000 = 50%
```

## Notes & caveats

- **Sync baseline**: the current code is the async version only. The
  "before" figures came from the earlier implementation that wrote the order
  in the request thread (see `VoucherOrderServiceImpl` evolution comment,
  `v1..v5`). To re-measure it, `git log` for a commit before the Kafka
  switch and run the same plan against that build.
- `@RateLimit(10/60s/USER)` on the seckill endpoint does not interfere: each
  of the 1000 users fires once per run.
- Keepalive and baseline hardware matter for absolute QPS. What the resume
  claims (and this harness reproduces) is the **relative** -64.8% / +50% /
  -31.9%.

## Cache tier benchmark (MySQL / Redis / two-level)

Quantifies the multi-level cache payoff on the hot-shop read path (`/shop/{id}
in production, `/bench/shop/{id}` here) across three tiers:

- `mysql`: straight DB read (`getById`) — no cache
- `redis`: Redis L2 + DB (`queryWithMutex`) — no Caffeine L1
- `two`:   Caffeine L1 -> Redis L2 -> DB (current product path)

The `two`/`mysql` avg gap is "how much faster the hot data is"; the `two` vs
`redis` Redis ops gap is the offload; Caffeine `stats()` provides the L1 hit
rate. Backed by a `bench`-profile-only controller that is absent in normal
runs; `docker-compose.yml` sets `SPRING_PROFILES_ACTIVE: docker,bench`.

### Run

```bash
./benchmark/scripts/04-run-cache-bench.sh
# tune: SHOP_ID=1 THREADS=200 LOOPS=500 MODES="mysql redis two" HOST PORT
```

Per tier the script:

1. runs the JMeter plan (`jmeter/cache-tier.jmx`, GET `/bench/shop/{id}?mode=`)
2. samples Redis `instantaneous_ops_per_sec` throughout the run
3. reads Caffeine `stats()` (hit/miss/request) before & after via `/bench/l1stats`
4. prints avg / P99 / throughput via `03-analyze.py`

### Reading the numbers

- **Visit speedup**: avg/P99 across `mysql -> redis -> two`.
- **L1 hit rate**: window hit-rate = (hitAfter - hitBefore) / (reqAfter - reqBefore).
- **Redis offload**: sampled ops/sec during the `two` run vs the `redis` run.

| tier  | sample | avg (ms) | P99 (ms) | QPS | Redis ops/s | L1 hit rate |
|-------|--------|----------|----------|-----|-------------|-------------|
| mysql | -      | -        | -        | -   | -           | n/a         |
| redis | -      | -        | -        | -   | -           | n/a         |
| two   | -      | -        | -        | -   | -           | -           |

> Placeholder — fill in after running `04-run-cache-bench.sh`.