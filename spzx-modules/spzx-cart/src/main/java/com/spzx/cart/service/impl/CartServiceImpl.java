package com.spzx.cart.service.impl;

import com.spzx.cart.api.domain.CartInfo;
import com.spzx.cart.service.ICartService;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CartServiceImpl implements ICartService {

    @Autowired
    RedisTemplate redisTemplate;

    @Autowired
    RemoteProductService remoteProductService;

    @Override
    public void addToCart(Long skuId, Integer skuNum) {
        //网关解析jwt令牌，将userId挂载请求头上；
        //微服务拦截器会将请求头userId绑定到线程上。若依使用alibaba提供的TransmittableThreadLocal来进行线程数据绑定的。
        //TransmittableThreadLocal 可以实现父子线程的数据共享。
        //我们直接从线程上获取即可。
        Long userId = SecurityContextHolder.getUserId();
        String userKey = getUserKey(userId);
        String hashKey = skuId.toString();
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        if (hashOperations.hasKey(hashKey)) {//修改

            CartInfo cartInfo = hashOperations.get(hashKey);
            int thresholdMin = 1; //阈值 边界值
            int thresholdMax = 99; //阈值 边界值
            int totalNum = cartInfo.getSkuNum() + skuNum;
            totalNum = totalNum < thresholdMin ? thresholdMin : totalNum;
            cartInfo.setSkuNum(totalNum > thresholdMax ? thresholdMax : totalNum);
            cartInfo.setUpdateTime(new Date());
            hashOperations.put(hashKey, cartInfo);
        } else {//新增

            Long size = hashOperations.size();
            if (++size > 50) {
                throw new ServiceException("购物车最多不能超过50种!");
            }

            CartInfo cartInfo = new CartInfo();
            cartInfo.setUserId(userId);
            cartInfo.setSkuId(skuId);

            //调用远程接口获取商品最新价格
            R<SkuPrice> skuPriceResult = remoteProductService.getSkuPrice(skuId, SecurityConstants.INNER);
            if (skuPriceResult.getCode() == R.FAIL) {
                throw new ServiceException(skuPriceResult.getMsg());
            }
            SkuPrice skuPrice = skuPriceResult.getData();
            cartInfo.setCartPrice(skuPrice.getSalePrice()); //销售价格
            cartInfo.setSkuPrice(skuPrice.getSalePrice()); //商品实时价格
            cartInfo.setSkuNum(skuNum); //新增默认数量值为1

            R<ProductSku> productSkuResult = remoteProductService.getProductSku(skuId, SecurityConstants.INNER);
            if (R.FAIL == productSkuResult.getCode()) {
                throw new ServiceException(productSkuResult.getMsg());
            }
            ProductSku productSku = productSkuResult.getData();
            cartInfo.setThumbImg(productSku.getThumbImg());
            cartInfo.setSkuName(productSku.getSkuName());
            cartInfo.setIsChecked(1); //可以省略，默认值1
            cartInfo.setCreateTime(new Date());
            hashOperations.put(hashKey, cartInfo);
        }
    }

    private String getUserKey(Long userId) {
        return "user:cart:" + userId;
    }

    @Override
    public List<CartInfo> getCartList() {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        //List<CartInfo> cartInfoList = redisTemplate.opsForHash().values(userKey);
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();

        //购物车中没有数据，返回空集合
        if (CollectionUtils.isEmpty(cartInfoList)) {
            return new ArrayList<>();
        }

        //购物车中需要回显实时价格，并显示最新价格和添加购物车时价格比较，不一致给与提示。
        //Set<String> ids = hashOperations.keys();
        List<Long> skuIdlist = cartInfoList.stream().map(CartInfo::getSkuId).toList();

        R<List<SkuPrice>> skuPriceListResult = remoteProductService.getSkuPriceList(skuIdlist, SecurityConstants.INNER);
        if (R.FAIL == skuPriceListResult.getCode()) {
            throw new ServiceException(skuPriceListResult.getMsg());
        }
        List<SkuPrice> skuPriceList = skuPriceListResult.getData(); //list集合数据转变map
        Map<Long, BigDecimal> skuIdToSalePriceMap = skuPriceList.stream().collect(Collectors.toMap(SkuPrice::getSkuId, SkuPrice::getSalePrice));

        cartInfoList.forEach(cartInfo -> {
            cartInfo.setSkuPrice(skuIdToSalePriceMap.get(cartInfo.getSkuId())); //更新实时价格
        });

        return cartInfoList;
    }


    @Override
    public void deleteCart(Long skuId) {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        String hashKey = skuId.toString();
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        if (hashOperations.hasKey(hashKey)) {
            hashOperations.delete(hashKey);
        }
    }


    @Override
    public void checkCart(Long skuId, Integer isChecked) {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        String hashKey = skuId.toString();
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        if (hashOperations.hasKey(hashKey)) {
            CartInfo cartInfo = hashOperations.get(hashKey);
            cartInfo.setIsChecked(isChecked);
            hashOperations.put(hashKey, cartInfo);
        }
    }


    @Override
    public void allCheckCart(Integer isChecked) {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();
        if (!CollectionUtils.isEmpty(cartInfoList)) {
            cartInfoList.forEach(cartInfo -> {
                cartInfo.setIsChecked(isChecked);
                hashOperations.put(cartInfo.getSkuId().toString(), cartInfo);
            });
        }
    }


    @Override
    public void clearCart() {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        if (redisTemplate.hasKey(userKey)) {
            redisTemplate.delete(userKey);
        }
    }

    @Override
    public List<CartInfo> getCartCheckedList(Long userId) {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();
        if (CollectionUtils.isEmpty(cartInfoList)) {
            return new ArrayList<>();
        }
        List<CartInfo> isCheckedList = new ArrayList<>();
        cartInfoList.forEach(cartInfo -> {
            if (cartInfo.getIsChecked() == 1) {
                isCheckedList.add(cartInfo);
            }

        });
        return isCheckedList;
    }


    /*  购物项极限50种商品，远程调用需要50次。系统性能低。
    @Override
    public Boolean updateCartPrice(Long userId) {
        //只更新下单中商品的购物车最新价格
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();
        cartInfoList.forEach(cartInfo -> {
            R<SkuPrice> skuPriceResult = remoteProductService.getSkuPrice(cartInfo.getSkuId(), SecurityConstants.INNER);
            SkuPrice skuPrice = skuPriceResult.getData();
            cartInfo.setCartPrice(skuPrice.getSalePrice());
            cartInfo.setSkuPrice(skuPrice.getSalePrice());
            hashOperations.put(cartInfo.getSkuId().toString(),cartInfo);
        });
        return true;
    }*/

    @Override
    public Boolean updateCartPrice(Long userId) {
        //只更新下单中商品的购物车最新价格
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();
        List<Long> skuIdList = cartInfoList.stream()
                .filter(cartInfo->cartInfo.getIsChecked().intValue()==1)
                .map(CartInfo::getSkuId).toList();
        R<List<SkuPrice>> skuPriceListResult = remoteProductService.getSkuPriceList(skuIdList, SecurityConstants.INNER);
        if(R.FAIL == skuPriceListResult.getCode()){
            throw new ServiceException(skuPriceListResult.getMsg());
        }
        List<SkuPrice> skuPriceList = skuPriceListResult.getData(); // list 转  map
        Map<Long, BigDecimal> skuIdToSalePriceMap = skuPriceList.stream()
                .collect(Collectors.toMap(SkuPrice::getSkuId, SkuPrice::getSalePrice));

        cartInfoList.forEach(cartInfo -> {
            if(cartInfo.getIsChecked().intValue() == 1){
                BigDecimal skuPrice = skuIdToSalePriceMap.get(cartInfo.getSkuId());
                cartInfo.setCartPrice(skuPrice);
                cartInfo.setSkuPrice(skuPrice);
                hashOperations.put(cartInfo.getSkuId().toString(),cartInfo);
            }
        });

        return true;
    }


    @Override
    public Boolean deleteCartCheckedList(Long userId) {
        String userKey = getUserKey(SecurityContextHolder.getUserId());
        BoundHashOperations<String, String, CartInfo> hashOperations = redisTemplate.boundHashOps(userKey);
        List<CartInfo> cartInfoList = hashOperations.values();
        if(!CollectionUtils.isEmpty(cartInfoList)){
            for (CartInfo cartInfo : cartInfoList) {
                if(cartInfo.getIsChecked().intValue() == 1){
                    hashOperations.delete(cartInfo.getSkuId().toString());
                }
            }
        }
        return true;
    }
}