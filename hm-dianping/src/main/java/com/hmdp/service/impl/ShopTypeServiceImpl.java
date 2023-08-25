package com.hmdp.service.impl;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    ObjectMapper objectMapper = new ObjectMapper ();

    /**
     * 查询商品分类 并且缓存
     * @return
     */
    @Override
    public Result queryBySortIfCache(){
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);

        //如果命中
        if (StrUtil.isNotBlank(shopTypeJson)) {
            try {
                List shopType = objectMapper.readValue(shopTypeJson, List.class);
                return Result.ok(shopType);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
//            return Result.ok(shopType);
//            List shopType = JSONUtil.toBean(shopTypeJson, List.class);// 这里不知道什么原因json 转换不了bean 这里换objectMapper 转换就可以了
        }
        //没有命中 查询数据库
        List<ShopType> shopTypes = this.query().orderByAsc("sort").list();
        if (shopTypes.size() == 0) {
            return Result.ok("没有商品分类数据");
        }
        // 缓存到redis中
        String toJsonStr = JSONUtil.toJsonStr(shopTypes);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY,toJsonStr,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shopTypes);
    }
}
