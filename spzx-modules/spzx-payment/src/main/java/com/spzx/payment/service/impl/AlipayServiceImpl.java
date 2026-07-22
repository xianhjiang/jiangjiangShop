package com.spzx.payment.service.impl;

import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.spzx.payment.configure.AlipayConfig;
import com.spzx.payment.domain.PaymentInfo;
import com.spzx.payment.service.IAlipayService;
import com.spzx.payment.service.IPaymentInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;

@Slf4j
@Service
public class AlipayServiceImpl implements IAlipayService {

    @Autowired
    AlipayClient alipayClient;

    @Autowired
    IPaymentInfoService paymentInfoService;


    @Override
    public String submitAlipay(String orderNo) throws AlipayApiException {
        //1.保存支付信息
        PaymentInfo paymentInfo = paymentInfoService.savePaymentInfo(orderNo);

        //2.调用支付宝  支付接口   生成h5表单  (表单就是用于打开手机支付宝，输入密码进行支付。)
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest(); //构建手机网页支付方式请求
        request.setReturnUrl(AlipayConfig.return_payment_url); //调用支付宝支付接口时需要携带同步地址，支付成功后，支付宝会回调这个地址，通知用户扣款成功。
        //调用支付宝支付接口时需要携带异步地址，支付成功后，支付宝会回调这个地址，通知后端服务扣款成功。用于完成一些额外的业务逻辑：更新支付信息、更新订单状态、减库存。。。
        request.setNotifyUrl(AlipayConfig.notify_payment_url);

        // 参数
        // 声明一个map 集合
        HashMap<String, Object> map = new HashMap<>();
        map.put("out_trade_no",paymentInfo.getOrderNo()); //对外交易编号，电商网站唯一订单号。
        map.put("product_code","QUICK_WAP_WAY"); //电商网站和支付宝签约产品码：   手机网页支付产品
        map.put("total_amount",paymentInfo.getAmount()); //订单实付金额。  我们采用沙箱环境，钱随便花，假的测试的账号余额。
        //map.put("total_amount","0.01"); //如果使用正式支付宝app支付，可以支付1分钱进行测试。
        map.put("subject",paymentInfo.getContent()); //标题

        request.setBizContent(JSON.toJSONString(map)); //数据挂载到请求上，发给支付宝支付接口

        AlipayTradeWapPayResponse alipayTradeWapPayResponse = alipayClient.pageExecute(request); //调用支付宝支付接口。支付响应结果
        String h5Form = alipayTradeWapPayResponse.getBody(); //支付接口返回 支付h5表单   用于申请打开手机支付宝。
        log.info("--------------------------------------------------\n");
        log.info(h5Form+"\n");
        log.info("--------------------------------------------------\n");
        return h5Form;
    }
}
