package com.spzx.payment.controller;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.spzx.common.core.web.controller.BaseController;
import com.spzx.common.core.web.domain.AjaxResult;
import com.spzx.common.security.annotation.RequiresLogin;
import com.spzx.payment.configure.AlipayConfig;
import com.spzx.payment.service.IAlipayService;
import com.spzx.payment.service.IPaymentInfoService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/alipay")
public class AlipayController extends BaseController {

    @Autowired
    private IAlipayService alipayService;

    @Autowired
    private IPaymentInfoService paymentInfoService;


    @Operation(summary = "支付宝下单")
    @RequiresLogin
    @RequestMapping("submitAlipay/{orderNo}")
    @ResponseBody
    public AjaxResult submitAlipay(@PathVariable(value = "orderNo") String orderNo) throws AlipayApiException {
        String form = alipayService.submitAlipay(orderNo);
        return success(form);
    }


    /**
     * 异步通知  https://opendocs.alipay.com/open/203/105286?pathHash=022a439c
     * 1. 在进行异步通知交互时，如果支付宝收到的应答不是 success ，支付宝会认为通知失败，会通过一定的策略定期重新发起通知。
     * 通知的间隔频率为：4m、10m、10m、1h、2h、6h、15h。
     * 2. 商家设置的异步地址（notify_url）需保证无任何字符，如空格、HTML 标签，且不能重定向。（如果重定向，支付宝会收不到 success 字符，
     * 会被支付宝服务器判定为该页面程序运行出现异常，而重发处理结果通知）
     * 3. 支付宝是用 POST 方式发送通知信息，商户获取参数的方式如下：request.Form("out_trade_no")、$_POST['out_trade_no']。
     * 4. 支付宝针对同一条异步通知重试时，异步通知参数中的 notify_id 是不变的。
     */
    @RequestMapping("callback/notify")
    @ResponseBody
    public String alipayNotify(@RequestParam Map<String, String> paramMap, HttpServletRequest request) {
        log.info("AlipayController...alipayNotify方法执行了...");
        log.info("paramMap="+paramMap);
        //验签： 支付宝支付接口执行扣款后，会异步回调咱们这个接口，会传回来一些数据，我们要验证数据合法有效性。防止数据篡改。
        boolean signVerified = false; //调用SDK验证签名
        try {
            signVerified = AlipaySignature.rsaCheckV1(paramMap, AlipayConfig.alipay_public_key, AlipayConfig.charset, AlipayConfig.sign_type);

        } catch (AlipayApiException e) {
            e.printStackTrace();
        }
        if(signVerified){ //验签成功
            // 交易状态
            String trade_status = paramMap.get("trade_status");
            if("TRADE_SUCCESS".equalsIgnoreCase(trade_status) || "TRADE_FINISHED".equalsIgnoreCase(trade_status)){
                //支付成功   去完成业务扩展: 修改支付信息，将支付宝支付接口返回的数据更新到支付信息表中，存储起来。
                paymentInfoService.updatePaymentStatus(paramMap, 2);  //  1.微信   2.支付宝
                return "success" ; //必须返回  "success" 表示业务扩展完成了，交易可以结束了.
            }
        }else{ //验签失败

            return "failure" ;
        }

        return "failure" ;
    }
}