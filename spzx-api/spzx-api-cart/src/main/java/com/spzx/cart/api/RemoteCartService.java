package com.spzx.cart.api;

import com.spzx.cart.api.domain.CartInfo;
import com.spzx.cart.api.factory.RemoteCartFallbackFactory;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.constant.ServiceNameConstants;
import com.spzx.common.core.domain.R;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@FeignClient(contextId = "remoteCartService",
        value = ServiceNameConstants.CART_SERVICE,
        fallbackFactory = RemoteCartFallbackFactory.class)
public interface RemoteCartService {

    //注意：openfen接口声明：遇到@PathVariable注解，需要指定value属性，否则报错。
    @GetMapping("/getCartCheckedList/{userId}")
    public R<List<CartInfo>> getCartCheckedList(@PathVariable("userId") Long userId,
                                                @RequestHeader(SecurityConstants.FROM_SOURCE) String source);


    @GetMapping("/updateCartPrice/{userId}")
    public R<Boolean> updateCartPrice(@PathVariable("userId") Long userId,
                                      @RequestHeader(SecurityConstants.FROM_SOURCE) String source);



    @GetMapping("/deleteCartCheckedList/{userId}")
    public R<Boolean> deleteCartCheckedList(@PathVariable("userId") Long userId,
                                            @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}