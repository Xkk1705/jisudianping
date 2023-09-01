package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByid(Long id) {
        // 判断id是否存在
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("没有博客信息");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        // 判断博客是否被点赞
        String key = "blog:islike" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
        return Result.ok(blog);
    }

    /**
     * 博客点赞功能
     *
     * @param id
     * @return
     */
    @Override
    public Result isLike(Long id) {
        Long userid = UserHolder.getUser().getId();
        //判断是否点赞
        String key = "blog:islike" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userid.toString());
        if (score == null) {// 没有点赞 数据库islike字段加一
            boolean success = this.update().setSql("liked = liked +1").eq("id", id).update();
            // 把点赞数据保存到redis
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userid.toString(), System.currentTimeMillis());
            }
        } else {
            boolean success = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userid.toString());
            }
        }
        // 没有点赞 再次点赞 islike字段减一
        return Result.ok();
    }

    /**
     * 点赞排名
     *
     * @param id
     * @return
     */
    @Override
    public Result queryisLikeRange(Long id) {
        String key = "blog:islike" + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 取出每个用户的id
        List<Long> top5ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> collect = userService.listByIds(top5ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(collect);
    }
}
