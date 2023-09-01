package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // Lua脚本只需要加载一次 类加载的时候加载 初始化脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));//设置脚本位置
        SECKILL_SCRIPT.setResultType(Long.class);// 设置返回值类型
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池用于消费
    public static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    //代理类 调用事务
    private IVoucherOrderService proxy;


    @Override
    public Result UseOrderByVoucher(Long voucherId) {
        Long userid = UserHolder.getUser().getId();
        // 执行lua脚本查看是否拥有下单权限
        Long num = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userid.toString()
        );
        System.out.println(num);
        // 如果不为0则没有权限
        int i = num.intValue();
        if (i != 0) {
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }
        //有权限生成订单
        long orderId = redisIDWorker.nextId("order");
        // 放入消息队列 异步生成订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userid);
        blockingQueue.add(voucherOrder);
        // 异步的在数据库中创建优惠券订单
        proxy = (IVoucherOrderService) AopContext.currentProxy();// 在这里初始化才能得到代理类

        return Result.ok(orderId);
    }

    /**
     * 在初始化spring的时候就执行异步任务
     */
    @PostConstruct
    private void init() {
        executorService.submit(new CreateVoucherOrderTask());
    }

    /**
     * 异步的在数据库中创建优惠券订单
     */
    class CreateVoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = blockingQueue.take();
                    Long userId = voucherOrder.getUserId();
                    // 创建订单保存到数据库 以防redis宕机 这里再次枷锁
                    RLock lock = redissonClient.getLock("lock:order" + userId);
                    boolean tryLock = lock.tryLock();
                    if (!tryLock) {// 没有获取到锁
                        return;
                    }
                    // 获取到锁 判断是否是同一个人
                    try {
                        // 这里得用代理类去去调用改方法
                        proxy.createOrderByQueue(voucherOrder);
//                        createOrderByQueue(voucherOrder);
                    } finally {
                        lock.unlock();
                    }

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    @Transactional
    public void createOrderByQueue(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {// 已经获取到优惠卷
            return;
        }
        // 扣减库存
        boolean success = iSeckillVoucherService.update().setSql("stock = stock -1").
                eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {// 库存不足
            return;
        }
        // 没有获取优惠券 写入数据库
        save(voucherOrder);

    }


//    @Override
//    public Result UseOrderByVoucher(Long voucherId) {
//        if (StringUtils.isEmpty(voucherId)) {
//            return Result.fail("优惠券id不能为空");
//        }
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//        if (seckillVoucher == null) {
//            return Result.fail("没有改优惠券");
//        }
//        // 判断优惠券时间是否过期
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀还没有开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        // 判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userid = UserHolder.getUser().getId();
////        synchronized (userid.toString().intern()) { // 这里等事务提交完毕 再释放锁
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);// 这里会事务失效 因为spring的事务是代理对
//        // 象去处理事务 这里是this当前类调用 所以会失效
////        ILockImpl lock = new ILockImpl("order:" + userid, stringRedisTemplate);
//
//        RLock lock = redissonClient.getLock("lock:order:" + userid);
//        boolean tryLock = lock.tryLock();
//        if (!tryLock) {//没有获取到锁
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

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
