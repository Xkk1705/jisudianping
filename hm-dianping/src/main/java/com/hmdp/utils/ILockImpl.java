package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class ILockImpl implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public ILockImpl(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:"; //key的前缀
    private static final String VALUE_PREFIX =  UUID.randomUUID().toString(true) + "-"; //key的前缀
    // Lua脚本只需要加载一次 类加载的时候加载 初始化脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));//设置脚本位置
        UNLOCK_SCRIPT.setResultType(Long.class);// 设置返回值类型
    }


    /**
     * 尝试获取锁
     *
     * @param timeoutSec
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId =VALUE_PREFIX + Thread.currentThread().getId();//这里为了保证分布情况下相同线程线程id也不同
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId + ""
                , timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);// 防止空指针


    }

    @Override
    public void unlock() {
        //判断当前释放锁的线程是否为同一个线程
        // 判断线程value
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),//KEY[1]
                VALUE_PREFIX + Thread.currentThread().getId());//ARGV[1]
    }

//    @Override
//    public void unlock() {
//        //判断当前释放锁的线程是否为同一个线程
//        // 判断线程value  这里由于fullGC会导致线程阻塞 判断和释放锁不具有原子性 可以使用lua脚本解决这个问题
//        String threadId = VALUE_PREFIX + Thread.currentThread().getId();
//        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(value)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
