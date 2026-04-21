-- 设置两个参数
-- 秒杀券ID
local voucherID = ARGV[1]
--用户ID
local userID = ARGV[2]
--秒杀券库存的key
local stockKey = "seckill:stock:" .. voucherID
-- 订单  key
local orderKey = "seckill:order:" .. voucherID

--脚本业务
--1 判断库存是否充足
if(tonumber(redis.call("get",stockKey))<=0) then
    --不足 返回1
    return 1
end

--2判断当前订单是否已经在集合里
if ((redis.call("sismember", orderKey, userID)) == 1) then
    --在 返回2
    return 2
end
--3 订单生成
--3.1 扣减库存
redis.call("incrby", stockKey, -1)
--3.2 将订单加入集合中
redis.call("sadd", orderKey, userID)
return 0