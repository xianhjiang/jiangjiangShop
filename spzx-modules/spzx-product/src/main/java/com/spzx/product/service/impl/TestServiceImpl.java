package com.spzx.product.service.impl;

import com.spzx.common.core.utils.StringUtils;
import com.spzx.product.service.TestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
通过JMeter压测工具，并发100请求，初始值num=0,最终结果并不是100。存在并发问题。
    解决办法：
        1.方法增加同步关键字synchronized   -  本地锁            只对当前JVM内部多个线程进行同步控制（好使）。
            问题，如果是微服务实例集群情况下，本地锁失效。

    解决办法：
        1.本地锁失效后，用分布式锁。使用redis里setnx命令来实现分布式锁。

        问题1：setnx刚好获取到锁，业务逻辑出现异常或宕机，导致锁无法释放
            解决：增加过期时间,保证 key-value 和 过期时间 原子性。

        问题2：可能会释放其他服务器的锁。
            业务执行时间长，过期时间短，导致业务逻辑没完成就被redis过期自动释放了锁，并且被其他进程(线程)获取了锁。
            当前业务完成后释放锁，那么，可能释放的就是其他进程(线程)使用的锁。

            解决：
                加锁时设置一个动态锁的值，释放锁时判断这个值是不是自己的值，是才需要释放锁。否则就不能释放，因为锁的值是别人的。锁自动被释放过了。我们就不能再释放了。

        问题3：判断锁是否是自己的  和  释放锁   的代码，不是原子性。依然可能释放其他人的锁。
            使用Lua脚本实现同步机制。保存原子性。

 */
@Service
public class TestServiceImpl implements TestService {

    @Autowired
    StringRedisTemplate stringRedisTemplate; //SpringBoot自动化配置

    //分布式锁实现    redis     setnx
    @Override
    public synchronized void testLock() {

        /*  设置key-value 和 过期时间 这两行代码不是原子的,依然会出现死锁情况。
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent("lock", "lock");
        stringRedisTemplate.expire("lock",1, TimeUnit.MINUTES); //给key设置过期时间，避免死锁。*/



        String uuid = UUID.randomUUID().toString().replaceAll("-",""); //动态生成锁的值

        //设置key-value 和 过期时间 保证原子性。
        Boolean ifAbsent = stringRedisTemplate.opsForValue().setIfAbsent("lock", uuid,1, TimeUnit.MINUTES);

        if(ifAbsent){ //加锁成功

            try {
                //int i = 1/0;  //模拟业务异常，代码无法释放锁。通过超时自动释放锁，避免死锁。
                // 查询Redis中的num值
                String value = (String) this.stringRedisTemplate.opsForValue().get("num");
                // 没有该值return
                if (StringUtils.isBlank(value)) {
                    return;
                }
                // 有值就转成成int
                int num = Integer.parseInt(value);
                // 把Redis中的num值+1
                this.stringRedisTemplate.opsForValue().set("num", String.valueOf(++num)); //  java 中  ++ 操作不是原子的。

                /*if(uuid.equals(stringRedisTemplate.opsForValue().get("lock"))) { //相等说明是自己的锁。
                    System.out.println("锁是自己的，咱可以释放。");
                    //释放分布式锁
                    stringRedisTemplate.delete("lock");
                }else{
                    System.out.println("锁自动释放了，咱就别再释放了。");
                }*/
            } finally {
                //释放锁的代码放在finnally语句块中。
                String script = "if redis.call('get',KEYS[1]) == ARGV[1]\n" +
                        "then\n" +
                        "\treturn redis.call('del',KEYS[1])\n" +
                        "else\n" +
                        "\treturn 0\n" +
                        "end"; //脚本字符串
                RedisScript<Long> redisScript = new DefaultRedisScript(script,Long.class);
                Long result = stringRedisTemplate.execute(redisScript, Arrays.asList("lock"), uuid);
                if(result==1){
                    System.out.println("自己释放锁成功");
                }else{
                    System.out.println("释放锁失败，过期自动释放过了。");
                }
            }

        }else{ //加锁失败
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            this.testLock(); // 递归再次抢锁。  自旋
        }



    }




    //本地锁实现。
/*    @Override
    public synchronized void testLock() {

        // 查询Redis中的num值
        String value = (String) this.stringRedisTemplate.opsForValue().get("num");
        // 没有该值return
        if (StringUtils.isBlank(value)) {
            return;
        }
        // 有值就转成成int
        int num = Integer.parseInt(value);
        // 把Redis中的num值+1
        this.stringRedisTemplate.opsForValue().set("num", String.valueOf(++num)); //  java 中  ++ 操作不是原子的。

    }*/

}
