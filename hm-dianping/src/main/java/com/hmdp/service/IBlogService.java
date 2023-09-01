package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    Result queryByid(Long id);

    Result isLike(Long id);

    Result queryisLikeRange(Long id);
}
