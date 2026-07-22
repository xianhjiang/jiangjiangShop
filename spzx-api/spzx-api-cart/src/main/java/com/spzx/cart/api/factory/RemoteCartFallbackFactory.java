package com.spzx.cart.api.factory;

import com.spzx.cart.api.RemoteCartService;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.constant.ServiceNameConstants;
import com.spzx.common.core.domain.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 降级处理类：
 *      当远程接口调用失败(超时、宕机、异常),需要通过降级方法返回兜底结果。
 */
@Component
public class RemoteCartFallbackFactory implements FallbackFactory<RemoteCartService> {

    private Logger log = LoggerFactory.getLogger(RemoteCartFallbackFactory.class);

    @Override
    public RemoteCartService create(Throwable throwable) {

        log.error("远程调用服务【{}】出现降级", ServiceNameConstants.CART_SERVICE);

        return new RemoteCartService() {
            @Override
            public R<List<CartInfo>> getCartCheckedList(Long userId, String source) {
                return R.fail("远程调用【查询用户购物车列表中选中商品列表】服务接口失败了.");
            }

            @Override
            public R<Boolean> updateCartPrice(Long userId, String source) {
                return R.fail("远程调用【更新购物车价格】服务接口失败了.");
            }

            @Override
            public R<Boolean> deleteCartCheckedList(Long userId, String source) {
                return R.fail("远程调用【删除用户购物车列表中选中商品列表】服务接口失败了.");
            }
        };
    }
}
