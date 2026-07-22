package com.spzx.payment.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.common.rabbit.service.RabbitService;
import com.spzx.order.api.RemoteOrderInfoService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.api.domain.OrderItem;
import com.spzx.payment.domain.PaymentInfo;
import com.spzx.payment.mapper.PaymentInfoMapper;
import com.spzx.payment.service.IPaymentInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 付款信息Service业务层处理
 */
@Service
public class PaymentInfoServiceImpl extends ServiceImpl<PaymentInfoMapper, PaymentInfo> implements IPaymentInfoService {
    @Autowired
    private PaymentInfoMapper paymentInfoMapper;

    @Autowired
    private RemoteOrderInfoService remoteOrderInfoService;

    @Autowired
    private RabbitService rabbitService;


    @Override
    public PaymentInfo savePaymentInfo(String orderNo) {
        //1.先查询支付信息，
        PaymentInfo paymentInfo = paymentInfoMapper
                .selectOne(new LambdaQueryWrapper<PaymentInfo>().eq(PaymentInfo::getOrderNo, orderNo));
        if(paymentInfo==null){
            //如果不存在则保存并返回结果
            R<OrderInfo> orderInfoResult = remoteOrderInfoService.getByOrderNo(orderNo, SecurityConstants.INNER);
            if(R.FAIL == orderInfoResult.getCode()){
                throw new ServiceException(orderInfoResult.getMsg());
            }
            OrderInfo orderInfo = orderInfoResult.getData();

            paymentInfo = new PaymentInfo() ;
            paymentInfo.setUserId(orderInfo.getUserId());
            paymentInfo.setOrderNo(orderInfo.getOrderNo());
            paymentInfo.setPayType(2); //1 微信     2 支付宝
            paymentInfo.setAmount(orderInfo.getTotalAmount()); //实付总金额
            List<OrderItem> orderItemList = orderInfo.getOrderItemList();
            StringBuilder builder = new StringBuilder();
            for (OrderItem orderItem : orderItemList) {
                builder.append(orderItem.getSkuName()+";");
            }
            paymentInfo.setContent(builder.toString());
            paymentInfo.setPaymentStatus("0"); //支付状态：0-未支付 1-已支付 -1-关闭

            paymentInfoMapper.insert(paymentInfo);
        }
        //如果存在直接返回结果，不用重复保存。
        return paymentInfo;
    }


    @Override
    public void updatePaymentStatus(Map<String, String> paramMap, int payType) {
        PaymentInfo paymentInfo = paymentInfoMapper.selectOne(new LambdaQueryWrapper<PaymentInfo>()
                .eq(PaymentInfo::getOrderNo, paramMap.get("out_trade_no"))); //paramMap.get("out_trade_no") 获取订单号
        if ("1".equals(paymentInfo.getPaymentStatus())) { //支付成功，已经更新过支付信息了。不要重复更新。去重。
            return;
        }

        //更新支付信息
        paymentInfo.setPayType(payType);
        paymentInfo.setPaymentStatus("1");
        paymentInfo.setTradeNo(paramMap.get("trade_no")); //交易编号   支付宝和用户之间扣款产生流程号
        paymentInfo.setCallbackTime(new Date());
        paymentInfo.setCallbackContent(JSON.toJSONString(paramMap));
        paymentInfoMapper.updateById(paymentInfo);

        //基于MQ通知订单系统，修改订单状态
        rabbitService.sendMessage(MqConst.EXCHANGE_PAYMENT_PAY, MqConst.ROUTING_PAYMENT_PAY, paymentInfo.getOrderNo());
    }
}