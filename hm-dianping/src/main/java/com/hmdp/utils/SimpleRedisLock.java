package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前前程的id
        long threadId = Thread.currentThread().getId();

        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name, threadId+"",timeoutSec, TimeUnit.SECONDS);

        //Boolean自动拆箱为返回值类型boolean有空指针风险，需用Boolean.TRUE.equals(flag)避免结果为 null时的错误
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unLock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);

    }
}
