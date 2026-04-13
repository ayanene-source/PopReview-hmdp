package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SimpleRedisLock.UNLOCK_SCRIPT;

@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //缓存到redis并且设置过期时间
    public void set(String key, Object value, Long time, TimeUnit  unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //缓存到redis并且设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit  unit) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透解决方案的查询
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.如果存在，直接返回
        if (StrUtil.isNotBlank(Json)) {
            //存在，直接返回，使用hutool工具包将json转为对象
            return JSONUtil.toBean(Json, type);
        }
        //查看是否命中空值“”
        if (Json != null) {
            return null;
        }

        //3.redis中不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //数据库中id不存在，将空值写入reids中
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //4.存在，写入redis
        //使用hutool工具包将对象转为json
        this.set(key, r, time, unit);

        //5.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //带有逻辑过期时间的缓存查询机制
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis中查询
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.如果未命中，直接返回
        if (StrUtil.isBlank(Json)) {//结果是 null 或空字符串
            return null;
        }
        //3.命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {//expireTime是否在当前时间之后（未过期）
            //未过期，直接返回店铺信息
            return r;
        }

        //5.2 已过期，需要缓存重建
        //6.缓存重建
        //6.1 尝试获取互斥锁
        String lockKey = lockKeyPrefix + id;
        String lockValue = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (lockValue != null) {
            //6.3 成功，开启新线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存,查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit );

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    //释放锁
                    unLock(lockKey, lockValue);
                }
            });
        }
        //6.4返回旧数据作为兜底（无论是否获取锁，都不阻塞用户请求）
        return r;
    }
    //尝试获取互斥锁
//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }

    public <R, ID> R queryWithLogicalExpireFallback(
            String keyPrefix,
            String lockKeyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            if (json != null) {
                return null;
            }
            R r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.setWithLogicalExpire(key, r, time, unit);
            return r;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        String lockKey = lockKeyPrefix + id;
        String lockValue = tryLock(lockKey);
        if (lockValue != null) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshData = dbFallback.apply(id);
                    if (freshData == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return;
                    }
                    this.setWithLogicalExpire(key, freshData, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey, lockValue);
                }
            });
        }
        return r;
    }

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private String tryLock(String key) {
        String lockValue = ID_PREFIX + UUID.randomUUID().toString(true);
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, lockValue, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? lockValue : null;
    }

    //释放锁
    private void unLock(String key, String lockValue) {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                lockValue
        );
    }
}
