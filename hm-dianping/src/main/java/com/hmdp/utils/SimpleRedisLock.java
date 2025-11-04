package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    // 锁的key前缀
    private static final String KEY_PREFIX = "lock:";
    // 锁的value值前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 释放锁的Lua脚本初始化
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程标识threadId：uuid+线程id作为value存入
        String threadId =ID_PREFIX + Thread.currentThread().getId();

        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name, threadId,timeoutSec, TimeUnit.SECONDS);

        //Boolean自动拆箱为返回值类型boolean有空指针风险，需用Boolean.TRUE.equals(flag)避免结果为 null时的错误
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unLock() {
        //调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
/*
        //获取当前线程的线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
        if (threadId.equals(id)) {
            //只有线程标识一致才释放锁
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
*/


}
