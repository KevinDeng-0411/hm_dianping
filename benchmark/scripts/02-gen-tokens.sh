#!/usr/bin/env bash
# Generate N fake login tokens directly into the hmdp-redis container
# and dump them to user_tokens.txt for JMeter.
#
# Why not the BatchTokenGenerator test class? The local Spring profile points
# at localhost:6379/3306 while Docker publishes redis on 6380 and mysql on 3307,
# so the JVM test class cannot reach the containers. Writing the login:token:*
# hashes straight into Redis keeps this zero-dependency.
#
# LoginInterceptor only checks the hash exists and reads id/nickName/icon.
# userId values are synthetic (ui = START_ID + i), which is fine: the seckill
# path keys "one order per user" by userId only.
#
# Usage: ./02-gen-tokens.sh [N]
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