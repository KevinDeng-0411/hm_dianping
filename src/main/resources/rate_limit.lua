-- KEYS[1]: 限流key
-- ARGV[1]: 时间窗口（秒）
-- ARGV[2]: 最大请求次数
-- ARGV[3]: 当前时间戳（毫秒）

local key = KEYS[1]
local windowSec = tonumber(ARGV[1])
local maxPermits = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local windowStart = now - windowSec * 1000

-- 移除窗口外的记录
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
-- 统计窗口内次数
local count = redis.call('ZCARD', key)
if count >= maxPermits then
    return 0
end
-- 记录本次请求
redis.call('ZADD', key, now, now)
-- 设置过期（窗口+1秒，避免未清理的数据堆积）
redis.call('EXPIRE', key, windowSec + 1)
return 1
