package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 用于生成唯一id
 */

@Component
public class RedisIDWorker {

    private static final long BEGIN_TIMESTAMP = 1659398400L;

    private StringRedisTemplate redisTemplate;

    public RedisIDWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long  nextId(String prefix) {

        // 1 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowStamp = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowStamp - BEGIN_TIMESTAMP;

        // 2 生成序列号
        // 2.1 末尾设置日期精确到天 每过一天 key也随着改变
        String daytime = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = redisTemplate.opsForValue().increment("icr" + prefix + ":" + daytime);

        // 3 返回

        return timeStamp << 32 |count;
    }


    public static void main(String[] args) {
        //获取指定时间 对应的秒数
        LocalDateTime time = LocalDateTime.of(2022, 8, 2, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);


    }
}
