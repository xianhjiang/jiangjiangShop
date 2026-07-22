package com.spzx.channel.service.impl;

import com.alibaba.fastjson2.JSON;
import com.spzx.channel.domain.ItemVo;
import com.spzx.channel.service.IItemService;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.Product;
import com.spzx.product.api.domain.ProductDetails;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuPrice;
import com.spzx.product.api.domain.vo.SkuStockVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

@Service
@Slf4j
public class ItemServiceImpl implements IItemService {

    @Autowired
    private RemoteProductService remoteProductService; //Feign接口： 详情6个接口数据

    @Autowired
    RedisTemplate redisTemplate;

    @Autowired
    ThreadPoolExecutor threadPoolExecutor ;


    //通过线程池 + ComplatableFuture      异步编排   既有同步也有异步        同步：先发哪个请求再发哪个请求，有顺序    异步：同时发起多个请求。
    @Override
    public ItemVo item(Long skuId) throws Exception {
        String dataKey = "product:sku:data";
        Boolean bitData = redisTemplate.opsForValue().getBit(dataKey, skuId);
        if(!bitData){ //bitmap中没有说明商品没有上架，不能查询。不放行。
            throw new ServiceException("用户查询商品sku不存在");
        }

        ItemVo itemVo = new ItemVo();

        //任务1.获取sku信息
        CompletableFuture<ProductSku> productSkuCompletableFuture = CompletableFuture.supplyAsync(() -> {
            R<ProductSku> productSkuResult = remoteProductService.getProductSku(skuId, SecurityConstants.INNER);
            if (R.FAIL == productSkuResult.getCode()) { //是否降级处理了.
                throw new ServiceException(productSkuResult.getMsg());
            }
            ProductSku productSku = productSkuResult.getData();
            itemVo.setProductSku(productSku);
            return productSku;
        }, threadPoolExecutor);

        //ProductSku productSku = productSkuCompletableFuture.get();

        //任务2.获取商品信息
        CompletableFuture<Void> productCompletableFuture = productSkuCompletableFuture.thenAcceptAsync(productSku->{
            R<Product> productResult = remoteProductService.getProduct(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == productResult.getCode()) {
                throw new ServiceException(productResult.getMsg());
            }
            Product product = productResult.getData();
            itemVo.setProduct(product);
            itemVo.setSliderUrlList(Arrays.asList(product.getSliderUrls().split(",")));
            //[{"key":"颜色","valueList":["白色","红色","黑色"]},{"key":"内存","valueList":["8G","18G"]}]
            itemVo.setSpecValueList(JSON.parseArray(product.getSpecValue()));
        },threadPoolExecutor);

        //任务3.获取商品最新价格
        CompletableFuture<Void> skuPriceCompletableFuture = CompletableFuture.runAsync(() -> {
            R<SkuPrice> skuPriceResult = remoteProductService.getSkuPrice(skuId, SecurityConstants.INNER);
            if (R.FAIL == skuPriceResult.getCode()) {
                throw new ServiceException(skuPriceResult.getMsg());
            }
            SkuPrice skuPrice = skuPriceResult.getData();
            itemVo.setSkuPrice(skuPrice);
        }, threadPoolExecutor);


        //任务4.获取商品详情
        CompletableFuture<Void> productDetailsCompletableFuture = productSkuCompletableFuture.thenAcceptAsync(productSku -> {
            R<ProductDetails> productDetailsResult = remoteProductService.getProductDetails(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == productDetailsResult.getCode()) {
                throw new ServiceException(productDetailsResult.getMsg());
            }
            ProductDetails productDetails = productDetailsResult.getData();
            itemVo.setDetailsImageUrlList(Arrays.asList(productDetails.getImageUrls().split(",")));
        }, threadPoolExecutor);


        //任务5.获取商品规格对应商品skuId信息
        CompletableFuture<Void> skuSpecValueCompletableFuture = productSkuCompletableFuture.thenAcceptAsync(productSku -> {
            R<Map<String, Long>> skuSpecValueResult = remoteProductService.getSkuSpecValue(productSku.getProductId(), SecurityConstants.INNER);
            if (R.FAIL == skuSpecValueResult.getCode()) {
                throw new ServiceException(skuSpecValueResult.getMsg());
            }
            Map<String, Long> skuSpecValueMap = skuSpecValueResult.getData();
            itemVo.setSkuSpecValueMap(skuSpecValueMap);
        }, threadPoolExecutor);


        //任务6.获取商品库存信息
        CompletableFuture<Void> skuStockCompletableFuture = productSkuCompletableFuture.thenAcceptAsync(productSku -> {
            R<SkuStockVo> skuStockResult = remoteProductService.getSkuStock(skuId, SecurityConstants.INNER);
            if (R.FAIL == skuStockResult.getCode()) {
                throw new ServiceException(skuStockResult.getMsg());
            }
            SkuStockVo skuStockVo = skuStockResult.getData();
            itemVo.setSkuStockVo(skuStockVo);
            productSku.setStockNum(skuStockVo.getAvailableNum());
        }, threadPoolExecutor);

        //等待任务执行
        CompletableFuture.allOf(
                productSkuCompletableFuture,
                productCompletableFuture,
                skuPriceCompletableFuture,
                productDetailsCompletableFuture,
                skuSpecValueCompletableFuture,
                skuStockCompletableFuture
        ).join(); //阻塞方法。 全部任务完成才会继续执行下面代码。

        return itemVo;
    }
}