package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Override
    @Transactional
    public Result UseOrderByVoucher(Long voucherId) {
        if (StringUtils.isEmpty(voucherId)) {
            return Result.fail("优惠券id不能为空");
        }
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
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
        //扣减库存
        boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").
                eq("voucher_id", seckillVoucher.getVoucherId()).update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //创建订单
        // 用户id
        Long userid = UserHolder.getUser().getId();
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
