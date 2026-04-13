package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY ,id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpireFallback(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    逻辑过期解决缓存击穿
//    public Shop queryWithLogicalExpire(Long id){
//        //1.从redis中查询
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.如果未命中，直接返回
//        if (StrUtil.isBlank(shopJson)) {
//            return null;
//        }
//        //3.命中，需要把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        //4.判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {//expireTime是否在当前时间之后（未过期）
//            //未过期，直接返回店铺信息
//            return shop;
//        }
//
//        //5.2 已过期，需要缓存重建
//        //6.缓存重建
//        //6.1 尝试获取互斥锁
//        boolean isLock = tryLock(LOCK_SHOP_KEY + id);
//        //6.2判断是否获取锁成功
//        if (isLock) {
//
//            //6.3 成功，开启新线程，实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally{
//                    //释放锁
//                    unLock(LOCK_SHOP_KEY + id);
//                }
//            });
//        }
//
//        //6.4返回过期的店铺数据
//        return shop;
//    }

    //互斥锁解决缓存击穿
//    public Shop queryWithMutex(Long id){
//        //1.从redis中查询
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.如果存在，直接返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回，使用hutool工具包将json转为对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //查看是否命中空值“”
//        if (shopJson != null) {
//            return null;
//        }
//
//        //缓存重建
//        //获取互斥锁
//        String lock = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lock);
//            //判断获取锁是否成功
//            if (!isLock) {
//                //失败则休眠5秒，再次尝试获取锁
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//
//
//            //获取锁成功，根据id查询数据库
//            //再次检测缓存是否命中
//            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//            if (StrUtil.isNotBlank(shopJson)) {
//                Shop shop1 = JSONUtil.toBean(shopJson, Shop.class);
//                return shop1;
//            }
//            //3.根据id查询数据库
//            shop = getById(id);
//            //数据库中id不存在，将空值写入reids中
//            if (shop == null) {
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //4.存在，写入redis
//            //使用hutool工具包将对象转为json
//            String shopJson1 = JSONUtil.toJsonStr(shop);
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopJson1, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            unLock(lock);
//        }
//
//        //5.返回
//        return shop;
//    }

    //缓存穿透
//    public Shop queryWithPassThrough(Long id){
//        //1.从redis中查询
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        //2.如果存在，直接返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回，使用hutool工具包将json转为对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //查看是否命中空值“”
//        if (shopJson != null) {
//            return null;
//        }
//
//        //3.redis中不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //数据库中id不存在，将空值写入reids中
//        if (shop == null) {
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //4.存在，写入redis
//        //使用hutool工具包将对象转为json
//        String shopJson1 = JSONUtil.toJsonStr(shop);
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopJson1, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        //5.返回
//        return shop;
//    }
    @Override
    @Transactional//事务
    public Result update(Shop shop) {
        //1.先更新mysql数据库
        updateById(shop);

        //判断id是否为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //2.再删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

//
//    //尝试获取互斥锁
//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1");
//        return BooleanUtil.isTrue(flag);
//    }
//
//    //释放锁
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }
//
//    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        //封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
//    }
}
