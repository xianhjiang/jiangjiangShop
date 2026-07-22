package com.spzx.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.spzx.common.security.annotation.EnableCustomConfig;
import com.spzx.common.security.annotation.EnableRyFeignClients;
import com.spzx.product.api.domain.ProductSku;
import com.spzx.product.mapper.ProductSkuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.CollectionUtils;

import java.util.List;


@EnableCustomConfig
@EnableRyFeignClients
@SpringBootApplication
public class SpzxProductApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(SpzxProductApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  系统模块启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
                " .-------.       ____     __        \n" +
                " |  _ _   \\      \\   \\   /  /    \n" +
                " | ( ' )  |       \\  _. /  '       \n" +
                " |(_ o _) /        _( )_ .'         \n" +
                " | (_,_).' __  ___(_ o _)'          \n" +
                " |  |\\ \\  |  ||   |(_,_)'         \n" +
                " |  | \\ `'   /|   `-'  /           \n" +
                " |  |  \\    /  \\      /           \n" +
                " ''-'   `'-'    `-..-'              ");
    }

    @Autowired
    ProductSkuMapper productSkuMapper;

    @Autowired
    RedisTemplate redisTemplate;

    //初始化方法，服务器启动需要将数据库里存在的商品信息或上架的商品信息保存到bitmap中。
    //注意：不是存储真实的业务数据，只是存储这个业务数据是否存在的bit位。
    @Override
    public void run(String... args) throws Exception {
        String dataKey = "product:sku:data";
        List<ProductSku> productSkuList = productSkuMapper
                .selectList(new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getStatus,1)
                        .select(ProductSku::getId));
        if(!CollectionUtils.isEmpty(productSkuList)){
            productSkuList.forEach(sku->{
                redisTemplate.opsForValue().setBit(dataKey,sku.getId(),true); //数据存在，用id作为偏移位置，设置true表示数据存在，   相当bit位上是 1
            });
        }
    }
}
