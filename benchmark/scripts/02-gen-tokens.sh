#!/usr/bin/env bash
# 批量生成 N 个假登录 token：直接写进 hmdp-redis 容器，并导出到
# user_tokens.txt 供 JMeter 读取。
#
# 为什么不用项目里的 BatchTokenGenerator 测试类？本地 Spring profile 指向
# localhost:6379/3306，而 Docker 把 Redis 发布在 6380、MySQL 在 3307，
# JVM 测试类连不上容器。直接写 login:token:* hash 是零依赖方案。
#
# LoginInterceptor 只校验 hash 存在并读 id/nickName/icon；userId 是合成的
# （uid = START_ID + i），没问题——秒杀的一人一单只按 userId 判重。
#
# 用法: ./02-gen-tokens.sh [数量N]
set -euo pipefail

N=${1:-1000}
START_ID=${START_ID:-1000001}
OUT="$(cd "$(dirname "$0")/.." && pwd)/user_tokens.txt"
: > "$OUT"

tokens() {
  for ((i = 1; i <= N; i++)); do
    printf 'bench-%06d\n' "$i"
  done
}

redis_cmds() {
  for ((i = 1; i <= N; i++)); do
    uid=$((START_ID + i - 1))
    tok="bench-$(printf '%06d' "$i")"
    printf 'HMSET login:token:%s id "%d" nickName "bench%d" icon ""\n' "$tok" "$uid" "$uid"
    printf 'EXPIRE login:token:%s 1800\n' "$tok"
  done
}

tokens >> "$OUT"                 # token list (one per line) for JMeter
redis_cmds | docker exec -i hmdp-redis redis-cli >/dev/null   # seed login:token:* hashes

COUNT=$(wc -l < "$OUT" | tr -d ' ')
echo "Generated $COUNT tokens in $OUT"
docker exec hmdp-redis redis-cli DBSIZE >/dev/null
echo "Sample line: $(head -1 "$OUT") (token) -> login:token:$(head -1 "$OUT")"