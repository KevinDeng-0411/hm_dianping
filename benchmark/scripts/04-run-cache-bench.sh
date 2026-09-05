#!/usr/bin/env bash
# 用 JMeter 依次跑缓存三档压测（mysql / redis / two），接口
# GET /bench/shop/{id}?mode=...。每档运行期间采样 Redis ops/s，
# two 档前后各读一次 Caffeine L1 stats。
#
# 前提：app 以 bench profile 启动（docker-compose 已设 docker,bench）、
#        jmeter 在 PATH、hmdp-redis 容器在跑。
#
# 用法:  ./04-run-cache-bench.sh
# 环境变量: MODES="mysql redis two"  HOST PORT
# 产物: benchmark/jmeter/result_cache_{mode}.csv + 控制台汇总。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMETER_DIR="$(cd "$SCRIPT_DIR/../jmeter" && pwd)"

HOST=${HOST:-localhost}
PORT=${PORT:-8081}
MODES=${MODES:-"mysql redis two"}

sample_ops() {
  docker exec hmdp-redis redis-cli INFO stats |
    awk -F: '/instantaneous_ops_per_sec/ {gsub("\r", ""); print $2}'
}

l1_json() {
  curl -s "http://$HOST:$PORT/bench/l1stats"
}

start_ops_sampler() {
  local tag=$1
  ( for _ in $(seq 1 60); do sample_ops; sleep 0.5; done ) >"/tmp/ops_$tag.txt" &
  echo $!
}

avg_ops() {
  local tag=$1
  awk '{s+=$1; n++} END{if(n>0) printf "%.0f", s/n; else print 0}' "/tmp/ops_$tag.txt"
}

cd "$JMETER_DIR"

echo "Warming caches (two-tier reads) so redis/two runs measure hot hits..."
for _ in $(seq 1 20); do curl -s "http://$HOST:$PORT/bench/shop/1?mode=two" >/dev/null; done

for MODE in $MODES; do
  echo "================ mode=$MODE ================"
  rm -f "result_cache_$MODE.csv"

  L1_BEFORE=$(l1_json 2>/dev/null || echo '{}')

  OPS_PID=$(start_ops_sampler "$MODE")
  jmeter -n -t "cache-$MODE.jmx" -l "result_cache_$MODE.csv" 2>&1 | tail -2
  kill "$OPS_PID" 2>/dev/null || true

  L1_AFTER=$(l1_json 2>/dev/null || echo '{}')
  OPS_AVG=$(avg_ops "$MODE")

  echo "Redis ops/sec (avg during run): $OPS_AVG"
  echo "Caffeine L1 before: $L1_BEFORE"
  echo "Caffeine L1 after : $L1_AFTER"

  python3 "$SCRIPT_DIR/03-analyze.py" "result_cache_$MODE.csv"
  rm -f "/tmp/ops_$MODE.txt"
done