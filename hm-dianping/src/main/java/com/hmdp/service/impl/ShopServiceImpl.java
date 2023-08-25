package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final Logger logger = LoggerFactory.getLogger(ShopServiceImpl.class);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 通过id查询商铺信息 并缓存
     *
     * @param id
     * @return
     */
    public Result QueryShopByIdIfCacheAndlock(Long id) {
        Shop shop = this.QueryShopByIdIfCache(id);
        if (shop == null) {
            return Result.fail("没有查询到该商铺");
        }
        return Result.ok(shop);
    }

    @Override
    public Shop QueryShopByIdIfCache(Long id) {
        // redis是否命中
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {//命中返回数据  这里null 和空字符跳出if
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            logger.debug("从redis中获取到shop：" + shopJson);
            return shop;
        } else if ("".equals(shopJson)) {// 如果这里查询到的数据为空字符 直接返回
            logger.debug("shopJson==========" + shopJson);
            return null;
        }
        // 防止多线程情况下 多个线程查询数据库并且缓存数据  这里需要加上锁
        String shopKey = RedisConstants.LOCK_SHOP_KEY + id;// 每个店铺的key
        try {
            if (!tryLock(shopKey)) {// 如果没有拿到锁 尝试获取锁
                QueryShopByIdIfCache(id);
                Thread.sleep(50);
            }
            //拿到锁后 查询数据库 并且写入缓存
            // 未命中查询数据库
            Shop shop = this.getById(id);
            if (shop == null) {// 数据库没有信息
                // 为了防止缓存穿透 这里我们返回空字符
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            String jsonStr = JSONUtil.toJsonStr(shop);
            // 把数据缓存到redis
            stringRedisTemplate.opsForValue().set(key, jsonStr, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(shopKey);
        }
    }


    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 2, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺信息
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateByIdIfCache(Shop shop) {
        if (shop == null) {
            return Result.fail("店铺id不能为null");
        }
        //跟新数据库
        this.updateById(shop);
        // 删除缓存数据 为了防止发生异常 得加上事务
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
