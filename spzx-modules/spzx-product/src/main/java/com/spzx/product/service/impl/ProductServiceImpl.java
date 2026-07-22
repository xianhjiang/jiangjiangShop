package com.spzx.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.core.utils.StringUtils;
import com.spzx.common.core.utils.bean.BeanUtils;
import com.spzx.product.api.domain.Product;
import com.spzx.product.api.domain.ProductDetails;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.api.domain.vo.SkuLockVo;
import com.spzx.product.api.domain.vo.SkuPrice;
import com.spzx.product.api.domain.vo.SkuQuery;
import com.spzx.product.api.domain.vo.SkuStockVo;
import com.spzx.product.domain.SkuStock;
import com.spzx.product.mapper.ProductDetailsMapper;
import com.spzx.product.mapper.ProductMapper;
import com.spzx.product.mapper.ProductSkuMapper;
import com.spzx.product.mapper.SkuStockMapper;
import com.spzx.product.service.IProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 商品Service业务层处理
 */
@Slf4j
@Service
@Transactional
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private ProductDetailsMapper productDetailsMapper;

    @Autowired
    private SkuStockMapper skuStockMapper;

    //@Autowired
    //private StringRedisTemplate stringRedisTemplate; //适合 key和value都是字符串类型

    @Autowired
    private RedisTemplate redisTemplate; //适合值是任意类型

    /**
     * 查询商品列表
     *
     * @param product 商品
     * @return 商品
     */
    @Override
    public List<Product> selectProductList(Product product) {
        return productMapper.selectProductList(product);
    }

    //原子性
    @Override
    public int insertProduct(Product product) {
        //1.保存Product对象到product表
        productMapper.insert(product); //主键回填

        //2.保存List<ProductSku>对象到product_sku表
        List<ProductSku> productSkuList = product.getProductSkuList();
        if (CollectionUtils.isEmpty(productSkuList)) {
            throw new ServiceException("SKU数据为空");
        }
        int size = productSkuList.size();
        for (int i = 0; i < size; i++) {
            ProductSku productSku = productSkuList.get(i);
            productSku.setSkuCode(product.getId() + "_" + i);
            productSku.setSkuName(product.getName() + " " + productSku.getSkuSpec());
            productSku.setProductId(product.getId());
            productSkuMapper.insert(productSku);

            //添加商品库存  //3.保存List<SkuStock>对象到sku_stock表
            SkuStock skuStock = new SkuStock();
            skuStock.setSkuId(productSku.getId());
            skuStock.setTotalNum(productSku.getStockNum());
            skuStock.setLockNum(0);
            skuStock.setAvailableNum(productSku.getStockNum());
            skuStock.setSaleNum(0);
            skuStockMapper.insert(skuStock);
        }

        //4.保存ProductDetails对象到product_details表
        ProductDetails productDetails = new ProductDetails();
        productDetails.setImageUrls(String.join(",", product.getDetailsImageUrlList()));
        productDetails.setProductId(product.getId());
        productDetailsMapper.insert(productDetails);

        return 1;
    }


    @Override
    public Product selectProductById(Long id) {
        //1.根据id查询Product对象
        Product product = productMapper.selectById(id);

        //2.封装扩展字段：查询商品对应多个List<ProductSku>
        //select * from product_sku where product_id =?
        List<ProductSku> productSkuList = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
        List<Long> productSkuIdList = productSkuList.stream().map(productSku -> productSku.getId()).toList();


        // select * from sku_stock where sku_id in (1,2,3,4,5,6)
        List<SkuStock> skuStockList = skuStockMapper.selectList(new LambdaQueryWrapper<SkuStock>().in(SkuStock::getSkuId, productSkuIdList));

        Map<Long, Integer> skuIdToTatalNumMap = skuStockList.stream().collect(Collectors.toMap(SkuStock::getSkuId, SkuStock::getTotalNum));
        productSkuList.forEach(productSku -> {
            //返回ProductSku对象，携带了库存数据；
            productSku.setStockNum(skuIdToTatalNumMap.get(productSku.getId()));
        });

        product.setProductSkuList(productSkuList);

        //3.封装扩展字段：商品详情图片List<String>
        ProductDetails productDetails = productDetailsMapper.selectOne(new LambdaQueryWrapper<ProductDetails>().eq(ProductDetails::getProductId, id));
        String imageUrls = productDetails.getImageUrls();   //url,url,url
        String[] urls = imageUrls.split(",");
        product.setDetailsImageUrlList(Arrays.asList(urls));
        //返回Product对象
        return product;
    }


    @Override
    public int updateProduct(Product product) {
        //1.更新Product
        productMapper.updateById(product);

        //2.更新SKU   List<ProductSku>
        List<ProductSku> productSkuList = product.getProductSkuList();
        if (CollectionUtils.isEmpty(productSkuList)) {
            throw new ServiceException("SKU数据为空");
        }
        productSkuList.forEach(productSku -> {
            productSkuMapper.updateById(productSku);

            //3.更新库存   List<ProductSku> -> 获取扩展字段stockNum
            SkuStock skuStock = skuStockMapper.selectOne(new LambdaQueryWrapper<SkuStock>().eq(SkuStock::getSkuId, productSku.getId()));
            skuStock.setTotalNum(productSku.getStockNum());
            skuStock.setAvailableNum(skuStock.getTotalNum() - skuStock.getLockNum());
            skuStockMapper.updateById(skuStock);
        });

        //4.更新详情ProductDetails
        ProductDetails productDetails = productDetailsMapper
                .selectOne(new LambdaQueryWrapper<ProductDetails>().eq(ProductDetails::getProductId, product.getId()));
        productDetails.setImageUrls(String.join(",", product.getDetailsImageUrlList()));
        productDetailsMapper.updateById(productDetails);

        return 1;
    }


    @Override
    public int deleteProductByIds(Long[] ids) {
        //1.删除Product表数据
        // delete from product where id in (1,2)
        productMapper.deleteBatchIds(Arrays.asList(ids));

        //2.删除ProductSku表数据
        List<ProductSku> productSkuList = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>().in(ProductSku::getProductId, Arrays.asList(ids)));
        List<Long> productSkuIdList = productSkuList.stream().map(ProductSku::getId).toList();
        productSkuMapper.deleteBatchIds(productSkuIdList);

        //3.删除SkuStock表数据
        skuStockMapper.delete(new LambdaQueryWrapper<SkuStock>().in(SkuStock::getSkuId, productSkuIdList));

        //4.删除ProductDetails表数据
        // delete from product_details where product_id in (1,2)
        productDetailsMapper.delete(new LambdaQueryWrapper<ProductDetails>().in(ProductDetails::getProductId, Arrays.asList(ids)));
        return 1;
    }


    @Override
    public void updateAuditStatus(Long id, Integer auditStatus) {
        Product product = new Product();
        product.setId(id);
        if (auditStatus == 1) {
            product.setAuditStatus(1);
            product.setAuditMessage("审批通过");
        } else {
            product.setAuditStatus(-1);
            product.setAuditMessage("审批拒绝");
        }
        productMapper.updateById(product);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        String dataKey = "product:sku:data";
        Product product = new Product();
        ProductSku productSku = new ProductSku();
        product.setId(id);
        //  select id from product_sku where product_id=1
        List<ProductSku> productSkuList = productSkuMapper
                .selectList(new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getProductId, id).select(ProductSku::getId));
        if (status == 1) {
            product.setStatus(1);//上架
            productSku.setStatus(1);
            productSkuList.forEach(item -> {
                redisTemplate.opsForValue().setBit(dataKey, item.getId(), true);
            });
        } else {
            product.setStatus(-1); //下架
            productSku.setStatus(-1);
            productSkuList.forEach(item -> {
                redisTemplate.opsForValue().setBit(dataKey, item.getId(), false);
            });
        }
        productMapper.updateById(product); //  update product set status=1 where id=1
        //update product_sku set status=1 where product_id=1
        productSkuMapper.update(productSku, new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
    }


    @Override
    public List<ProductSku> getTopSale() {
        return productSkuMapper.getTopSale();
    }


    @Override
    public List<ProductSku> skuList(SkuQuery skuQuery) {
        return productSkuMapper.skuList(skuQuery);
    }


    /**
     * 服务提供者：6个接口来服务于商品详情查询。需要进行优化，提供查询效率。
     * 需要使用redis来提高性能。
     */
    @Override
    public ProductSku getProductSku(Long skuId) {

        try {
            //1.先查询缓存，缓存有直接返回         大大提供性能。快
            ProductSku productSku = null;
            String redisKey = "product:sku:" + skuId;
            if (redisTemplate.hasKey(redisKey)) {
                productSku = (ProductSku) redisTemplate.opsForValue().get(redisKey);
                return productSku;
            }

            //2.缓存没有,查询数据库

            //2.1 获取分布式锁       解决缓存击穿问题
            String lockKey = "product:sku:lock:" + skuId;
            String lockValue = UUID.randomUUID().toString().replaceAll("-", "");
            Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, 1, TimeUnit.MINUTES);
            if (ifAbsent) {
                productSku = getSkuFromDB(skuId);
                //2.2 数据在缓存10分钟，不在缓存1分钟    不在为什么缓存？解决同一个key查询缓存穿透。     不能解决随机key穿透问题。用布隆过滤器或bitmap
                int ttl = productSku == null ? 1 * 60 : 10 * 60;
                redisTemplate.opsForValue().set(redisKey, productSku, ttl, TimeUnit.MINUTES);

                //2.3 释放锁   保证自己的锁，不能释放别人的锁  LUA  保证原子性。
                String script = "if redis.call('get',KEYS[1]) == ARGV[1]\n" +
                        "then\n" +
                        "\treturn redis.call('del',KEYS[1])\n" +
                        "else\n" +
                        "\treturn 0\n" +
                        "end";
                RedisScript<Long> redisScript = new DefaultRedisScript(script, Long.class);
                redisTemplate.execute(redisScript, Arrays.asList(lockKey), lockValue);
                return productSku;
            } else {
                //3. 获取不到锁的线程自旋
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return this.getProductSku(skuId); //歇息一会，自旋重新获取数据。
            }
        } catch (Exception e) {
            e.printStackTrace();
            return getSkuFromDB(skuId); //兜底处理。
        }

    }

    private ProductSku getSkuFromDB(Long skuId) {
        return productSkuMapper.selectById(skuId);
    }


    @Override
    public Product getProduct(Long id) {
        return productMapper.selectById(id);
    }


    @Override
    public SkuPrice getSkuPrice(Long skuId) {
        ProductSku productSku = productSkuMapper.selectOne(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getId, skuId).select(ProductSku::getSalePrice, ProductSku::getMarketPrice));
        SkuPrice skuPrice = new SkuPrice();
        BeanUtils.copyProperties(productSku, skuPrice);
        return skuPrice;
    }


    @Override
    public ProductDetails getProductDetails(Long id) {
        return productDetailsMapper.selectOne(new LambdaQueryWrapper<ProductDetails>().eq(ProductDetails::getProductId, id));
    }


    @Override
    public Map<String, Long> getSkuSpecValue(Long id) {
        List<ProductSku> productSkuList = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id).select(ProductSku::getId, ProductSku::getSkuSpec));
        Map<String, Long> skuSpecValueMap = new HashMap<>();
        productSkuList.forEach(item -> {
            skuSpecValueMap.put(item.getSkuSpec(), item.getId());
        });
        return skuSpecValueMap;
    }


    @Override
    public SkuStockVo getSkuStock(Long skuId) {
        SkuStock skuStock = skuStockMapper.selectOne(new LambdaQueryWrapper<SkuStock>().eq(SkuStock::getSkuId, skuId));
        SkuStockVo skuStockVo = new SkuStockVo();
        BeanUtils.copyProperties(skuStock, skuStockVo);
        return skuStockVo;
    }


    // select * from product_sku where id in (1,2,3)
    // select id,sale_price,market_price from product_sku where id in (1,2,3)
    @Override
    public List<SkuPrice> getSkuPriceList(List<Long> skuIdList) {
        if (CollectionUtils.isEmpty(skuIdList)) {
            throw new ServiceException("参数为空");
        }

        List<ProductSku> skuList = productSkuMapper.selectList(new LambdaQueryWrapper<ProductSku>()
                .in(ProductSku::getId, skuIdList)
                .select(ProductSku::getId, ProductSku::getSalePrice, ProductSku::getMarketPrice));

        if (CollectionUtils.isEmpty(skuList)) {
            return new ArrayList<>();
        }
        List<SkuPrice> skuPriceList = skuList.stream().map(sku -> {
            SkuPrice skuPrice = new SkuPrice();
            skuPrice.setSkuId(sku.getId());
            skuPrice.setSalePrice(sku.getSalePrice());
            skuPrice.setMarketPrice(sku.getMarketPrice());
            return skuPrice;
        }).toList();

        return skuPriceList;
    }


    @Transactional
    @Override
    public String checkAndLock(String orderNo, List<SkuLockVo> skuLockVoList) {
        //1.去重
        //用分布式锁实现去重。
        String lockKey = "sku:checkAndLock:" + orderNo;
        String checkAndLockKey = "sku:checkAndLock:data:" + orderNo;
        Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(lockKey, orderNo, 1, TimeUnit.HOURS);
        if (!ifAbsent) {
            if (redisTemplate.hasKey(checkAndLockKey)) {
                return "";
            }
            throw new ServiceException("重复提交");
        }

        //2.检查库存
        for (SkuLockVo skuLockVo : skuLockVoList) {
            //SELECT * FROM `sku_stock` where sku_id = 1 and 105 <= available_num for update
            SkuStock check = skuStockMapper.check(skuLockVo.getSkuId(), skuLockVo.getSkuNum());
            skuLockVo.setIsHaveStock(check == null ? false : true);
        }

        boolean isExits = skuLockVoList.stream().anyMatch(skuLockVo -> {
            return !skuLockVo.getIsHaveStock();
        });
        if (isExits) {
            //3.如果存在库存不够情况，拼串返回字符串.
            List<SkuLockVo> noHaveStockSkulist = skuLockVoList.stream().filter(skuLockVo -> {
                return !skuLockVo.getIsHaveStock();
            }).toList();
            StringBuilder builder = new StringBuilder();
            for (SkuLockVo skuLockVo : noHaveStockSkulist) {
                ProductSku productSku = productSkuMapper.selectById(skuLockVo.getSkuId());
                builder.append(productSku.getSkuName() + "库存不足;");
            }
            if (StringUtils.isNotEmpty(builder.toString())) {
                redisTemplate.delete(lockKey);
                return builder.toString();
            }
        } else {
            //4.如果库存都够情况，锁库存。
            for (SkuLockVo skuLockVo : skuLockVoList) {
                //update sku_stock set available_num=available_num-5,lock_num=lock_num+5 where sku_id=1
                int rows = skuStockMapper.lock(skuLockVo.getSkuId(), skuLockVo.getSkuNum());
                if (rows == 0) {
                    redisTemplate.delete(lockKey);
                    throw new ServiceException("锁库存失败");
                }
            }
        }

        //5.把锁定库存的数据存储在缓存中，用于后续  解锁库存  或  减库存使用。
        //不需要设置过期时间。因为锁定库存保存数据到缓存是给   解锁库存  或  减库存业务使用。他们负责删除缓存。
        redisTemplate.opsForValue().set(checkAndLockKey, skuLockVoList);
        return ""; //空串表示成功
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void unlock(String orderNo) {
        //1.去重，消息幂等保证
        String lockKey = "sku:unlock:"+orderNo;
        Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(lockKey, orderNo, 1, TimeUnit.HOURS);
        if(!ifAbsent){
            log.info("重复解锁被幂等处理。");
            return;
        }

        //2.从缓存获取锁定库存数据
        String checkAndLockKey = "sku:checkAndLock:data:" + orderNo;
        List<SkuLockVo> skuStocklist =  (List<SkuLockVo>)redisTemplate.opsForValue().get(checkAndLockKey);
        if(CollectionUtils.isEmpty(skuStocklist)){
            //如果关单时不能阻止用户支付，那么，出现支付了，又关闭订单，有过来减库存情况。
            //另一种：已经解锁过库存了，删除缓存。一个小时后，分布式锁失效了。重试过来的请求。
            log.info("数据不存在，不需要解锁...");
            return;
        }

        //3.解锁库存。
        for (SkuLockVo skuLockVo : skuStocklist) {
            //update sku_stock set available_num=available_num+5,lock_num=lock_num-5 where sku_id=1
            int rows = skuStockMapper.unlock(skuLockVo.getSkuId(), skuLockVo.getSkuNum());
            if(rows==0){
                redisTemplate.delete(lockKey); //解锁失败，那么让其他请求来重新处理。
                throw new ServiceException("解锁库存失败,事务回滚.");
            }
        }

        //4.删除缓存。为了防止重复解锁。也避免减库存。
        redisTemplate.delete(checkAndLockKey);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void minus(String orderNo) {
        //1.去重，消息幂等保证
        String lockKey = "sku:minus:"+orderNo;
        Boolean ifAbsent = redisTemplate.opsForValue().setIfAbsent(lockKey, orderNo, 1, TimeUnit.HOURS);
        if(!ifAbsent){
            log.info("重复减库存被幂等处理。");
            return;
        }

        //2.从缓存获取锁定库存数据
        String checkAndLockKey = "sku:checkAndLock:data:" + orderNo;
        List<SkuLockVo> skuStocklist =  (List<SkuLockVo>)redisTemplate.opsForValue().get(checkAndLockKey);
        if(CollectionUtils.isEmpty(skuStocklist)){
            //1.可能线程消息执行关单并解锁库存了(会删除缓存)。
            //2.分布式锁1个小时后失效了，再重新增加分布式锁，获取缓存为空。因为已经减库存了。
            log.info("数据不存在，不需要减库存...");
            return;
        }

        //3.解锁库存。
        for (SkuLockVo skuLockVo : skuStocklist) {
            //update sku_stock set lock_num=lock_num-5,sale_num=sale_num+5,total_num=total_num-5 where sku_id=1
            int rows = skuStockMapper.minus(skuLockVo.getSkuId(), skuLockVo.getSkuNum());
            if(rows==0){
                redisTemplate.delete(lockKey); //减失败，那么让其他请求来重新处理。
                throw new ServiceException("减库存失败,事务回滚.");
            }
        }

        //4.删除缓存。为了防止重复减库存。也避免减解锁库存。
        redisTemplate.delete(checkAndLockKey);//1.去重，消息幂等保证

    }
}