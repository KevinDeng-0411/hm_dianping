#!/usr/bin/env bash
# Run the three cache-tier benchmarks (mysql / redis / two) back-to-back via
# JMeter against /bench/shop/{id}?mode=..., sampling Redis ops/sec during each
# run and capturing Caffeine L1 stats around the "two" tier.
#
# Requires: app up with the "bench" profile (docker-compose sets docker,bench),
#           jmeter on PATH, hmdp-redis container.
#
# Usage:  ./04-run-cache-bench.sh
# Env:    SHOP_ID=1  THREADS=200  LOOPS=500  MODES="mysql redis two"  HOST PORT
# Result: benchmark/jmeter/result_cache_{mode}.csv + printed summary.
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