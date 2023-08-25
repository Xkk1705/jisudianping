package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopService extends IService<Shop> {



    Shop QueryShopByIdIfCache(Long id);

    Result updateByIdIfCache(Shop shop);

    Result QueryShopByIdIfCacheAndlock(Long id);
}
