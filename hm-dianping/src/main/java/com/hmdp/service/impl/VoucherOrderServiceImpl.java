package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILockImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result UseOrderByVoucher(Long voucherId) {
        if (StringUtils.isEmpty(voucherId)) {
            return Result.fail("优惠券id不能为空");
        }
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("没有改优惠券");
        }
        // 判断优惠券时间是否过期
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还没有开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userid = UserHolder.getUser().getId();
//        synchronized (userid.toString().intern()) { // 这里等事务提交完毕 再释放锁
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);// 这里会事务失效 因为spring的事务是代理对
        // 象去处理事务 这里是this当前类调用 所以会失效
//        ILockImpl lock = new ILockImpl("order:" + userid, stringRedisTemplate);

        RLock lock = redissonClient.getLock("lock:order:" + userid);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {//没有获取到锁
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 用户id
        Long userid = UserHolder.getUser().getId();
        //一个用户只能获取一个该类型优惠券
        Integer count = query().eq("user_id", userid).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("已经领取优惠卷！！");
        }
        //扣减库存
        boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足");
        }

        //创建订单
        //订单id
        long orderId = redisIDWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userid);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
