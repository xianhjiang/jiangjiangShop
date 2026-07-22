package com.spzx.payment.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.spzx.payment.domain.PaymentInfo;

import java.util.Map;

/**
 * 付款信息Service接口
 */
public interface IPaymentInfoService extends IService<PaymentInfo> {

    /**
     * 保存支付信息
     * @param orderNo
     * @return 返回支付信息
     */
    PaymentInfo savePaymentInfo(String orderNo);

    /**
     * 更新支付信息
     * @param paramMap
     * @param payType
     */
    void updatePaymentStatus(Map<String, String> paramMap, int payType);
}