package com.spzx.common.rabbit.config;

import com.alibaba.fastjson2.JSON;
import com.spzx.common.rabbit.entity.GuiguCorrelationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 服务器启动时，执行rabbitTemplate初始化，设置确认函数和回退函数
 * ApplicationEvent      一些子事件的父类。
 * ApplicationReadyEvent 具体子事件。表示应用程序启动好，IOC容器初始化好，存在相关bean对象了。再进行相关的初始化。
 * 也可以使用相关的注解替代： @EventListener
 */
@Slf4j
@Component
public class RabbitInitConfigApplicationListener implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RedisTemplate redisTemplate;

    //当IOC容器触发ApplicationReadyEvent时进行初始化工作
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        this.setUpInitRabbitTemplete();
    }

    //初始化，给rabbitTemplate设置确认回调函数和退回回调函数。
    private void setUpInitRabbitTemplete() {

        //交换机收没收到消息，都会执行确认回调。
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                //消息到交换器成功
                log.info("消息发送到Exchange成功：{}", correlationData);
            } else {
                //消息到交换器失败
                log.error("消息发送到Exchange失败：{}", cause);

                //消息发送失败，重发
                this.retrySendMsg(correlationData);
            }
        });

        //队列没收到消息才会执行。收到了就不执行。
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("Returned: " + returned.getMessage() + "\nreplyCode: " + returned.getReplyCode()
                    + "\nreplyText: " + returned.getReplyText() + "\nexchange/rk: "
                    + returned.getExchange() + "/" + returned.getRoutingKey());


            //从回退消息对象的头中可以获取关联数据保存再redis中的key信息。
            Object correlationID = returned.getMessage().getMessageProperties().getHeader("spring_returned_message_correlation");
            String dataStr = (String) redisTemplate.opsForValue().get(correlationID);
            GuiguCorrelationData guiguCorrelationData = JSON.parseObject(dataStr, GuiguCorrelationData.class);

            //消息发送失败，重发      注意：延迟插件问题，由于交互机延迟转发消息到队列，会触发回退函数执行，那么，延迟消息不进行消息重发。
            if (!guiguCorrelationData.isDelay()) {
                this.retrySendMsg(guiguCorrelationData);
            }

        });
    }


    private void retrySendMsg(CorrelationData correlationData) {
        GuiguCorrelationData guiguCorrelationData = (GuiguCorrelationData) correlationData;
        int retryCount = guiguCorrelationData.getRetryCount();

        if (retryCount >= 3) {
            //重发3次就不再重发。    首次+重发3次  一共4次
            //throw new ServiceException("消息重发次数达到上限3次!");
            //超过最大重试次数
            log.error("生产者超过最大重试次数，将失败的消息存入数据库用人工处理；给管理员发送邮件；给管理员发送短信；");
        } else {
            //重发消息
            retryCount++;
            guiguCorrelationData.setRetryCount(retryCount);

            //一定要先写缓存再发消息。否则，可能出现次数获取不对情况。
            redisTemplate.opsForValue().set(guiguCorrelationData.getId(), JSON.toJSONString(guiguCorrelationData), 10, TimeUnit.MINUTES);

            if (guiguCorrelationData.isDelay()) {
                rabbitTemplate.convertAndSend(guiguCorrelationData.getExchange(),
                        guiguCorrelationData.getRoutingKey(),
                        guiguCorrelationData.getMessage(),
                        message -> {
                            message.getMessageProperties().setDelay(guiguCorrelationData.getDelayTime() * 1000);
                            return message;
                        },
                        guiguCorrelationData);
                log.error("延迟消息消息重发...");
            } else {
                //重试
                rabbitTemplate.convertAndSend(guiguCorrelationData.getExchange(),
                        guiguCorrelationData.getRoutingKey(),
                        guiguCorrelationData.getMessage(),
                        guiguCorrelationData);
                log.error("消息重发...");
            }

        }
    }

}
