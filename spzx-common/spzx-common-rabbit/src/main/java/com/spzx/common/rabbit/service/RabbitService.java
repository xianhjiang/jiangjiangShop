package com.spzx.common.rabbit.service;

import com.alibaba.fastjson2.JSON;
import com.spzx.common.core.utils.uuid.UUID;
import com.spzx.common.rabbit.entity.GuiguCorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RabbitService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 发送消息
     *
     * @param exchange   交换机
     * @param routingKey 路由键
     * @param message    消息
     */
    public boolean sendMessage(String exchange, String routingKey, Object message) {
        //定义关联数据，类似发邮件时的附件
        String id = "mq:" + UUID.randomUUID().toString().replaceAll("-", "");
        GuiguCorrelationData guiguCorrelationData = new GuiguCorrelationData();
        guiguCorrelationData.setId(id);
        guiguCorrelationData.setMessage(message);
        guiguCorrelationData.setExchange(exchange);
        guiguCorrelationData.setRoutingKey(routingKey);

        //关联数据保存到缓存。消费发送失败执行回退函数时，可以从缓存中获取关联数据进行消息重发。
        redisTemplate.opsForValue().set(id, JSON.toJSONString(guiguCorrelationData), 10, TimeUnit.MINUTES);

        //发送消息携带关联数据。
        rabbitTemplate.convertAndSend(exchange, routingKey, message, guiguCorrelationData);

        //发送成功结果
        return true;
    }

    /**
     * 发送延迟消息(延迟插件)
     *
     * @param exchangeDelay
     * @param routingDelay
     * @param msg
     * @param delayTime  延迟时间，单位秒
     * @return
     */
    public boolean sendDealyMessage(String exchangeDelay, String routingDelay, String msg, int delayTime) {

        //定义关联数据，类似发邮件时的附件
        String id = "mq:" + UUID.randomUUID().toString().replaceAll("-", "");
        GuiguCorrelationData guiguCorrelationData = new GuiguCorrelationData();
        guiguCorrelationData.setId(id);
        guiguCorrelationData.setExchange(exchangeDelay);
        guiguCorrelationData.setRoutingKey(routingDelay);
        guiguCorrelationData.setMessage(msg);
        guiguCorrelationData.setDelay(true); //延迟消息
        guiguCorrelationData.setDelayTime(delayTime); //延长时间

        //关联数据保存到缓存。消费发送失败执行回退函数时，可以从缓存中获取关联数据进行消息重发。
        redisTemplate.opsForValue().set(id, JSON.toJSONString(guiguCorrelationData), 10, TimeUnit.MINUTES);

        //发送消息携带关联数据。
        rabbitTemplate.convertAndSend(exchangeDelay, routingDelay, msg, message -> {
            message.getMessageProperties().setDelay(delayTime * 1000); //毫秒
            return message;
        }, guiguCorrelationData);

        //发送成功结果
        return true;
    }
}