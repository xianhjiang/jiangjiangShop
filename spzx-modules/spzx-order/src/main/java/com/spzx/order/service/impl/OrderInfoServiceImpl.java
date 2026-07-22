package com.spzx.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.spzx.cart.api.RemoteCartService;
import com.spzx.cart.api.domain.CartInfo;
import com.spzx.common.core.constant.SecurityConstants;
import com.spzx.common.core.context.SecurityContextHolder;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.exception.ServiceException;
import com.spzx.common.core.utils.StringUtils;
import com.spzx.common.core.utils.uuid.UUID;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.common.rabbit.service.RabbitService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.api.domain.OrderItem;
import com.spzx.order.domain.OrderLog;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;
import com.spzx.order.mapper.OrderInfoMapper;
import com.spzx.order.mapper.OrderItemMapper;
import com.spzx.order.mapper.OrderLogMapper;
import com.spzx.order.service.IOrderInfoService;
import com.spzx.product.api.RemoteProductService;
import com.spzx.product.api.domain.vo.SkuLockVo;
import com.spzx.product.api.domain.vo.SkuPrice;
import com.spzx.user.api.RemoteUserAddressService;
import com.spzx.user.domain.UserAddress;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements IOrderInfoService {
    @Autowired
    private OrderInfoMapper orderInfoMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderLogMapper orderLogMapper;

    @Autowired
    private RemoteCartService remoteCartService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RemoteProductService remoteProductService;

    @Autowired
    private RemoteUserAddressService remoteUserAddressService;


    @Autowired
    RabbitService rabbitService; //来自于公共模块：spzx-common-rabbit

    /**
     * 查询订单列表
     *
     * @param orderInfo 订单
     * @return 订单
     */
    @Override
    public List<OrderInfo> selectOrderInfoList(OrderInfo orderInfo) {
        return orderInfoMapper.selectOrderInfoList(orderInfo);
    }

    /**
     * 查询订单
     *
     * @param id 订单主键
     * @return 订单
     */
    @Override
    public OrderInfo selectOrderInfoById(Long id) {
        OrderInfo orderInfo = orderInfoMapper.selectById(id);
        List<OrderItem> orderItemList = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id));
        orderInfo.setOrderItemList(orderItemList);
        return orderInfo;
    }


    @Override
    public TradeVo orderTradeData() {

        //1.订单项集合
        //调用购物车接口，获取打钩商品列表，然后进行类型转换即可。
        List<OrderItem> orderItemList = new ArrayList<>();
        Long userId = SecurityContextHolder.getUserId();
        R<List<CartInfo>> cartCheckedListResult = remoteCartService.getCartCheckedList(userId, SecurityConstants.INNER);
        if (R.FAIL == cartCheckedListResult.getCode()) {
            throw new ServiceException(cartCheckedListResult.getMsg());
        }
        List<CartInfo> cartInfoList = cartCheckedListResult.getData();
        if (CollectionUtils.isEmpty(cartInfoList)) {
            throw new ServiceException("数据不存在");
        }
        cartInfoList.forEach(cartInfo -> {
            OrderItem orderItem = new OrderItem();
            BeanUtils.copyProperties(cartInfo, orderItem);
            orderItemList.add(orderItem);
        });


        //2.总价格
        BigDecimal totalPrice = new BigDecimal(0);
        for (OrderItem orderItem : orderItemList) {
            totalPrice = totalPrice.add(orderItem.getSkuPrice().multiply(new BigDecimal(orderItem.getSkuNum())));
        }

        //3.生成交易流水号，也是后面订单号。还可以用于去重处理
        String tradeNo = getUserTradeNo(userId);

        //4.封装数据
        TradeVo tradeVo = new TradeVo();
        tradeVo.setOrderItemList(orderItemList);
        tradeVo.setTotalAmount(totalPrice);
        tradeVo.setTradeNo(tradeNo);

        //5.返回数据
        return tradeVo;
    }

    @NotNull
    private String getUserTradeNo(Long userId) {
        String userTradeKey = "user:tradeNo:" + userId;
        String tradeNo = UUID.randomUUID().toString().replaceAll("-", "");
        redisTemplate.opsForValue().set(userTradeKey, tradeNo, 5, TimeUnit.MINUTES);
        return tradeNo;
    }


    @Override
    public Long submitOrder(OrderForm orderForm) {
        //1.去重
        //第一次请求过来，如果缓存流程号在，删除，开始业务逻辑执行
        //第二次请求过来(重复提交),缓存流水号不在，不能执行业务逻辑处理。
        Long userId = SecurityContextHolder.getUserId();
        String userTradeKey = "user:tradeNo:" + userId;
        String redisTradeNo = (String) redisTemplate.opsForValue().get(userTradeKey); //有可能过期
        String formTradeNo = orderForm.getTradeNo(); //表单隐藏参数。
        //判断和删除保证原子性。
        String script = "if redis.call('get',KEYS[1]) == ARGV[1]\n" +
                "then\n" +
                "\treturn redis.call('del',KEYS[1])\n" +
                "else\n" +
                "\treturn 0\n" +
                "end\n" +
                "\t";
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setResultType(Long.class);
        redisScript.setScriptText(script);

        Long result = (Long) redisTemplate.execute(redisScript, Arrays.asList(userTradeKey), formTradeNo);
        if (result != 1) {
            throw new ServiceException("重复提交");
        }

        //2.验证数据
        List<OrderItem> orderItemList = orderForm.getOrderItemList();
        if (CollectionUtils.isEmpty(orderItemList)) {
            throw new ServiceException("数据为空");
        }


        //3.验证价格
        //思路：表单提交商品价格  和   数据库最新价格进行比较    如果都一致，继续下面步骤。否则，修改购物车商品价格，并且抛出异常，停止下单。
        List<Long> skuIdList = orderItemList.stream().map(OrderItem::getSkuId).toList();
        R<List<SkuPrice>> skuPriceListResult = remoteProductService.getSkuPriceList(skuIdList, SecurityConstants.INNER);
        if (R.FAIL == skuPriceListResult.getCode()) {
            throw new ServiceException(skuPriceListResult.getMsg());
        }
        List<SkuPrice> skuPriceList = skuPriceListResult.getData(); //数据库最新商品价格    转Map
        Map<Long, BigDecimal> skuIdToSalePriceMap = skuPriceList.stream()
                .collect(Collectors.toMap(SkuPrice::getSkuId, SkuPrice::getSalePrice));

        StringBuffer buffer = new StringBuffer();
        for (OrderItem orderItem : orderItemList) {
            if (orderItem.getSkuPrice().compareTo(skuIdToSalePriceMap.get(orderItem.getSkuId())) != 0) {
                buffer.append(orderItem.getSkuName() + "价格变动了;");
            }
        }
        if (StringUtils.hasText(buffer.toString())) { //存在商品价格变动情况
            //更新购物车商品价格
            remoteCartService.updateCartPrice(userId, SecurityConstants.INNER);
            throw new ServiceException(buffer.toString());
        }

        //4.检查并锁定库存
        List<SkuLockVo> skuLockVoList = new ArrayList<>();
        for (OrderItem orderItem : orderItemList) {
            SkuLockVo vo = new SkuLockVo();
            vo.setSkuId(orderItem.getSkuId());
            vo.setSkuNum(orderItem.getSkuNum());
            //vo.setIsHaveStock(false); //默认值false
            skuLockVoList.add(vo);
        }

        R<String> checkAndLockResult = remoteProductService.checkAndLock(formTradeNo, skuLockVoList, SecurityConstants.INNER);
        if (R.FAIL == checkAndLockResult.getCode()) {
            throw new ServiceException(checkAndLockResult.getData());
        }
        if (StringUtils.isNotEmpty(checkAndLockResult.getData())) {
            throw new ServiceException(checkAndLockResult.getData());
        }


        //5.保存订单
        Long orderId = null;
        try {
            orderId = this.saveOrder(orderForm);
        } catch (Exception e) {
            //下单失败，解锁库存。
            //4.1 下单失败，解锁库存。 解锁库存的数据是从缓存中获取。因为锁定库存时将其保存到了缓存中。
            rabbitService.sendMessage(MqConst.EXCHANGE_PRODUCT, MqConst.ROUTING_UNLOCK, orderForm.getTradeNo());
            throw new ServiceException("下单失败");
        }

        //6.删除购物车选中商品
        remoteCartService.deleteCartCheckedList(userId, SecurityConstants.INNER);


        //7.发送延迟消息,取消订单 (15分钟未支付，消费者就会进行关闭订单->解锁库存。)
        rabbitService.sendDealyMessage(MqConst.EXCHANGE_CANCEL_ORDER,
                MqConst.ROUTING_CANCEL_ORDER,
                String.valueOf(orderId), MqConst.CANCEL_ORDER_DELAY_TIME);
        //返回订单ID
        return orderId;
    }


    @Transactional(rollbackFor = Exception.class)
    public Long saveOrder(OrderForm orderForm) {
        // 获取当前登录用户的id
        Long userId = SecurityContextHolder.getUserId();
        String userName = SecurityContextHolder.getUserName();

        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setOrderNo(orderForm.getTradeNo());
        orderInfo.setUserId(userId);
        orderInfo.setNickName(userName);
        orderInfo.setRemark(orderForm.getRemark());
        UserAddress userAddress = remoteUserAddressService.getUserAddress(orderForm.getUserAddressId(), SecurityConstants.INNER).getData();
        orderInfo.setReceiverName(userAddress.getName());
        orderInfo.setReceiverPhone(userAddress.getPhone());
        orderInfo.setReceiverTagName(userAddress.getTagName());
        orderInfo.setReceiverProvince(userAddress.getProvinceCode());
        orderInfo.setReceiverCity(userAddress.getCityCode());
        orderInfo.setReceiverDistrict(userAddress.getDistrictCode());
        orderInfo.setReceiverAddress(userAddress.getFullAddress());

        List<OrderItem> orderItemList = orderForm.getOrderItemList();
        BigDecimal totalAmount = new BigDecimal(0);
        for (OrderItem orderItem : orderItemList) {
            totalAmount = totalAmount.add(orderItem.getSkuPrice().multiply(new BigDecimal(orderItem.getSkuNum())));
        }
        orderInfo.setTotalAmount(totalAmount);
        orderInfo.setCouponAmount(new BigDecimal(0));
        orderInfo.setOriginalTotalAmount(totalAmount);
        orderInfo.setFeightFee(orderForm.getFeightFee());
        //OrderInfo类的orderStatus属性的类型改为Integer
        orderInfo.setOrderStatus(0);
        orderInfoMapper.insert(orderInfo);

        //保存订单明细
        for (OrderItem orderItem : orderItemList) {
            orderItem.setOrderId(orderInfo.getId());
            orderItemMapper.insert(orderItem);
        }

        //记录日志
        OrderLog orderLog = new OrderLog();
        orderLog.setOrderId(orderInfo.getId());
        orderLog.setProcessStatus(0);
        orderLog.setNote("提交订单");
        orderLog.setOperateUser("用户");
        orderLogMapper.insert(orderLog);
        return orderInfo.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processCloseOrder(Long orderId) {
        OrderInfo orderInfo = orderInfoMapper.selectById(orderId);
        //订单状态【0->待付款；1->待发货；2->已发货；3->待用户收货，已完成；-1->已取消】
        if (orderInfo.getOrderStatus().intValue() == 0) { //待支付
            orderInfo.setOrderStatus(-1);
            orderInfo.setCancelTime(new Date());
            orderInfo.setCancelReason("支付超时,系统自动取消等待");
            orderInfoMapper.updateById(orderInfo);

            OrderLog orderLog = new OrderLog();
            orderLog.setOrderId(orderId);
            orderLog.setOperateUser("系统");
            orderLog.setProcessStatus(-1);
            orderLog.setNote("系统群取消订单");
            orderLogMapper.insert(orderLog);

            //发送MQ消息通知商品系统解锁库存
            rabbitService.sendMessage(MqConst.EXCHANGE_PRODUCT, MqConst.ROUTING_UNLOCK, orderInfo.getOrderNo());
        }
    }


    @Override
    public OrderInfo getByOrderNo(String orderNo) {
        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
        List<OrderItem> orderItemList = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderInfo.getId()));
        orderInfo.setOrderItemList(orderItemList);
        return orderInfo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processPaySucess(String orderNo) {
        //获取订单信息
        OrderInfo orderInfo = orderInfoMapper.selectOne(new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo)
                .select(OrderInfo::getId, OrderInfo::getOrderStatus));
        //未支付
        if (orderInfo.getOrderStatus().intValue() == 0) { // 0未支付 -> 1已支付
            orderInfo.setOrderStatus(1);
            orderInfo.setPaymentTime(new Date());
            orderInfoMapper.updateById(orderInfo);
        }
    }
}