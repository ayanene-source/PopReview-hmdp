-- 如果锁的值等于我传入的线程标识，就删除
--否则不删
-- 比较线程标识和锁标识是否一致
if(redis.call("get",KEYS[1]) == ARGV[1]) then
-- 解锁
return redis.call("del",KEYS[1])
end
return 0
