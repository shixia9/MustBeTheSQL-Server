package com.sql.logic.engine.test;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootTest
public class RedisTest {
    
     @Autowired
     private StringRedisTemplate redisTemplate;

     @Autowired
     private RedissonClient redissonClient;

     static final Logger logger = LoggerFactory.getLogger(RedisTest.class);

     @Test
     public void redisTest() {
        redisTemplate.opsForValue().set("test", "hello Redis", Duration.ofMinutes(3));
        String val = redisTemplate.opsForValue().get("test");
        logger.info("value: {}", val);
     }

     @Test
     public void redissonPubSubTest() throws InterruptedException{
        String channel = "test-redisson-channel";
        RTopic topic = redissonClient.getTopic(channel);
        topic.addListener(String.class, new MessageListener<String>() {
            @Override
            public void onMessage(CharSequence channel, String msg) {
                System.out.println("[SUBS] channel: " + channel + " | msg: " + msg);
            }
        });
        System.out.println("start...");
        topic.publish("Hello Redisson Pub/Sub!");
        topic.publish("This is second Pub message!");
        Thread.sleep(1000);
        System.out.println("done...");  
     }

}
