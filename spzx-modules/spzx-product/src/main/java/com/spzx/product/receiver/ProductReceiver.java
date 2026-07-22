package com.spzx.product.receiver;

import com.rabbitmq.client.Channel;
import com.spzx.common.core.utils.StringUtils;
import com.spzx.common.rabbit.constant.MqConst;
import com.spzx.product.service.IProductService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.spzx.common.rabbit.constant.MqConst;

import java.io.IOException;

/**
 * 商品库存消费者：主要功能，解锁库存、减库存。
 */
@Slf4j
@Component
public class ProductReceiver {

    @Autowired
    private IProductService productService;

    @SneakyThrows //异常直接抛出。
    @RabbitListener(bindings = {
            @QueueBinding(exchange = @Exchange(name = MqConst.EXCHANGE_PRODUCT,durable = "true"),
            value = @Queue(name = MqConst.QUEUE_UNLOCK,durable = "true"),
            key = {
                    MqConst.ROUTING_UNLOCK
            })
    })
    public void unlock(String orderNo, Message message, Channel channel) /*throws IOException*/ {
        if(StringUtils.isNotEmpty(orderNo)){
            log.info("[商品服务]监听解锁库存消息：{}", orderNo);
            productService.unlock(orderNo); //去重处理在方法内部完成。
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }



    @SneakyThrows //异常直接抛出。
    @RabbitListener(bindings = {
            @QueueBinding(exchange = @Exchange(name = MqConst.EXCHANGE_PRODUCT,durable = "true"),
                    value = @Queue(name = MqConst.QUEUE_MINUS,durable = "true"),
                    key = {
                            MqConst.ROUTING_MINUS
                    })
    })
    public void minus(String orderNo, Message message, Channel channel) /*throws IOException*/ {
        if(StringUtils.isNotEmpty(orderNo)){
            log.info("[商品服务]监听减库存消息：{}", orderNo);
            productService.minus(orderNo); //去重处理在方法内部完成。
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
    }
}