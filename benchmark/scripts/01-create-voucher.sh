#!/usr/bin/env bash
# Create a seckill voucher (tb_voucher + tb_seckill_voucher) directly in the
# hmdp-mysql container and pre-warm the Redis stock key. Prints the voucher id.
#
# Note: goes straight to SQL because the /voucher/seckill API path can't map a
# full Voucher onto the current tb_voucher schema cleanly (some entity fields
# have no matching column). For benchmark data, direct SQL is the stable route.
#
# Usage:   ./01-create-voucher.sh [stock]
# Output:  VOUCHER_ID=<id>
set -euo pipefail

STOCK=${1:-200}

VOUCHER_ID=$(docker exec hmdp-mysql mysql -uroot -p1234 hm_dianping -N -e \
  "INSERT INTO tb_voucher (title,pay_value,actual_value,type,status)
     VALUES ('bench-seckill',100,80,1,1);
   SET @v = LAST_INSERT_ID();
   INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time)
     VALUES (@v,$STOCK,'2026-09-05 00:00:00','2099-12-31 23:59:59');
   SELECT @v;" 2>/dev/null)

if [ -z "$VOUCHER_ID" ]; then
  echo "ERROR: insert failed" >&2
  exit 1
fi

docker exec hmdp-redis redis-cli SET "seckill:stock:$VOUCHER_ID" "$STOCK" >/dev/null
echo "VOUCHER_ID=$VOUCHER_ID"
echo "Redis stock: $(docker exec hmdp-redis redis-cli GET "seckill:stock:$VOUCHER_ID")"
echo "Set voucherId in seckill.jmx TestPlan UDV, then copy a fresh user_tokens.txt (script 02)."