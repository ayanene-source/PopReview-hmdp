package com.hmdp.utils;

// 生成全局唯一id

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Component// 将类交给spring管理
public class RedisIdWorker {
    // 起始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {

        //1.生成时间戳
        LocalDateTime nowTime = LocalDateTime.now();//得到包含年、月、日、时、分、秒、纳秒信息
        long nowTimeStamp = nowTime.toEpochSecond(ZoneOffset.UTC);//获取当前时间戳（秒级
        long timeStamp = nowTimeStamp - BEGIN_TIMESTAMP;//获取当前时间戳（秒级）减去起始时间戳（秒级）

        //2.生成序列号
        String date = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+ keyPrefix +":" + date);//increment方法返回自增后的值，每一天从

        //3.拼接返回
        long id = (timeStamp << 32) | count;
        return id;
    }

    // 一、假设当前时间戳减去起始时间戳 得到 timeStamp = 126112800 (十进制)
    // 第二步：将时间戳转为二进制
    //126112800 (十进制) = 00000111 10000111 01000111 11100000 (二进制)
    // 注意：一个 long 是 64 位，这里我们只展示 32 位部分。（在100多年内这个时间戳不会超过32位，放心）
    // 第三步：左移 32 位
    //timeStamp << 32
    //意思是：
    // 把这个时间戳的二进制往左推 32 格（空出来的低位填 0）。

    //00000111 10000111 01000111 11100000 00000000 00000000 00000000 00000000
    //这时的十进制结果：
    //timeStamp << 32 = 126112800 × 2^32 = 126112800 × 4294967296 = 541285249773568000

    // 第四步：Redis 自增计数部分
    //Redis 中：
    //INCR icr:order:2025:10:30

    //假设返回值是：
    //count = 1
    //二进制是：
    //00000000 00000000 00000000 00000001

    //🔗 第五步：按位或（|）拼接
    //id = (timeStamp << 32) | count;

    //就是把高 32 位（时间）+ 低 32 位（序列号）拼在一起：

    //高32位：00000111 10000111 01000111 11100000
    //低32位：00000000 00000000 00000000 00000001
    //———————————————————————————————————————————
    //合并后：00000111 10000111 01000111 11100000 00000000 00000000 00000000 00000001

    // 第六步：换算成十进制 ID
    //id = 541285249773568000 | 1 = 541285249773568001

    //也就是说：
    //id = 541285249773568001
}
