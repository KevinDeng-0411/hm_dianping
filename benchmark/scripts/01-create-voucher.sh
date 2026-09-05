#!/usr/bin/env bash
# Create a seckill voucher via the API and print its id.
# The backend pre-warms the Redis stock key (seckill:stock:<id>) on creation.
#
# Usage:   ./01-create-voucher.sh [stock]
# Env:     BASE   default http://localhost:8081
# Output:  prints RESP and VOUCHER_ID=<id>
set -euo pipefail

BASE=${BASE:-http://localhost:8081}
STOCK=${1:-200}

BODY='{"shopId":1,"title":"Benchmark seckill voucher","subTitle":"JMeter","type":1,"status":1,"stock":'"$STOCK"',"beginTime":"2026-09-05T00:00:00","endTime":"2099-12-31T23:59:59"}'

RESP=$(curl -s -X POST "$BASE/voucher/seckill" \
  -H 'Content-Type: application/json' \
  -d "$BODY")
echo "API RESP: $RESP"

VOUCHER_ID=$(printf '%s' "$RESP" | grep -o '"data":[0-9]*' | grep -o '[0-9]*' | head -1)
if [ -z "$VOUCHER_ID" ]; then
  echo "ERROR: could not extract voucher id, is the app up on $BASE?" >&2
  exit 1
fi
echo "VOUCHER_ID=$VOUCHER_ID"

echo "Warmed Redis stock: $(docker exec hmdp-redis redis-cli GET "seckill:stock:$VOUCHER_ID")"
echo "Use it when running JMeter:  -JvoucherId=$VOUCHER_ID"