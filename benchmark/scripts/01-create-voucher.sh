#!/usr/bin/env bash
# 直接在 hmdp-mysql 容器里插入一张秒杀券（tb_voucher + tb_seckill_voucher），
# 并预热 Redis 库存 key。打印券 id。
#
# 说明：走 SQL 是因为 /voucher/seckill 接口在当前 tb_voucher 表结构下无法
# 完整映射 Voucher 实体（部分实体字段没有对应列）。压测造数走 SQL 最稳。
#
# 用法:   ./01-create-voucher.sh [库存]
# 输出:   VOUCHER_ID=<id>
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