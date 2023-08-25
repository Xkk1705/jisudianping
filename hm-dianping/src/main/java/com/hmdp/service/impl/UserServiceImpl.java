package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 发送验证码功能
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 判断手机号是否正确
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if (phoneInvalid) { //不符合
            return Result.fail("手机号码格式不正确");
        } else {
            //生成验证码
            String code = RandomUtil.randomNumbers(6);
            // 把手机号存入session 方便请求验证码的时候发送到后端
            //  session.setAttribute("code", code);
            // 把验证码存入到redis众
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            log.debug("发送验证码成功" + code);
            return Result.ok();
        }
    }

    /**
     * 登录功能
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //判断手机号码和验证码是否一致
        //从redis获取code
        String phone = loginForm.getPhone();
        String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (redisCode == null || !redisCode.equals(code)) {//不一致
            return Result.fail("验证码不正确");
        }
        //判断用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            //用户不存在 保存用户到session中 并且保存到数据库
            user = this.saveWithPhone(loginForm.getPhone());
        }
        // 防止敏感信息泄露 把user转换成userDto
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 生成一个token
        String token = UUID.randomUUID().toString(true);
        // 把token放入redis中  这里我们在校验是否登录模块里设置 时常刷新
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fileNmae, fileValue) -> fileValue.toString()
        ));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        return Result.ok(token);
    }


    public User saveWithPhone(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));
        this.save(user);
        return query().eq("phone", phone).one();
    }
}
