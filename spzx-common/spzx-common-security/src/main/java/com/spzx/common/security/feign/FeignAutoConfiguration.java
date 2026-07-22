package com.spzx.common.security.feign;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import feign.RequestInterceptor;

/**
 * Feign 配置注册
 *
 * @author spzx
 **/
@Configuration
public class FeignAutoConfiguration
{
    @Bean
    public RequestInterceptor requestInterceptor()
    {
        return new FeignRequestInterceptor(); //解决OpenFeign远程调用，丢失请求头的问题的拦截器。
    }
}
