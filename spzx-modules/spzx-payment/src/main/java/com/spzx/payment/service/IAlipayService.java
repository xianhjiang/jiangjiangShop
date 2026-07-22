package com.spzx.payment.service;

import com.alipay.api.AlipayApiException;

public interface IAlipayService {

    /**
     * 支付业务
     * @param orderNo
     * @return 支付给咱们生成H5表单
     */
    String submitAlipay(String orderNo) throws AlipayApiException;
}
