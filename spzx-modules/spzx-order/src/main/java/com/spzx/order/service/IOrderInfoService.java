package com.spzx.order.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;

import java.util.List;

public interface IOrderInfoService extends IService<OrderInfo> {
    /**
     * 查询订单列表
     *
     * @param orderInfo 订单
     * @return 订单集合
     */
    public List<OrderInfo> selectOrderInfoList(OrderInfo orderInfo);

    /**
     * 查询订单
     *
     * @param id 订单主键
     * @return 订单
     */
    public OrderInfo selectOrderInfoById(Long id);

    /**
     * 结算(下单前确认页面)
     * @return 交易信息
     */
    TradeVo orderTradeData();

    /**
     * 下单
     * @param orderForm
     * @return
     */
    Long submitOrder(OrderForm orderForm);

    /**
     * 15分钟未支付关闭订单；如果支付了，什么事都不做。
     * @param Long 订单ID
     */
    void processCloseOrder(Long orderId);

    /**
     * 根据订单号查询订单信息
     * @param orderNo
     * @return
     */
    OrderInfo getByOrderNo(String orderNo);

    /**
     * 支付成功后修改订单状态
     * @param orderNo
     */
    void processPaySucess(String orderNo);
}