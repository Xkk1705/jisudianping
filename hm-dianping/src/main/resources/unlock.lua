
-- 比较线程标识与锁中的标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    --释放锁
    return redis.call('del',key)
end
return 0