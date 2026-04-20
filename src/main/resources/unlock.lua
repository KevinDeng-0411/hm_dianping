-- 判断锁标识和线程标识是否相等
if(redis.call('get',KEYS[1])==ARGV[1]) then
    -- 相等 释放锁
    return redis.call('del',KEYS[1])
end
return 0