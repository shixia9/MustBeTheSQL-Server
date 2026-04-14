package com.sql.logic.engine.common.DCC.config;

import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sql.logic.engine.common.DCC.domain.model.valobj.AttributeVO;
import com.sql.logic.engine.common.DCC.domain.service.DynamicConfigCenterService;
import com.sql.logic.engine.common.DCC.domain.service.IDynamicConfigCenterService;
import com.sql.logic.engine.common.DCC.listener.DynamicConfigCenterAdjustListener;
import com.sql.logic.engine.common.DCC.types.common.Constants;

@Configuration
@EnableConfigurationProperties(value = {
    DynamicConfigCenterAutoProperties.class,
    DynamicConfigCenterRegisterAutoProperties.class})
public class DynamicConfigCenterRegisterAutoConfig {

    private Logger logger = LoggerFactory.getLogger(DynamicConfigCenterRegisterAutoConfig.class);

    @Bean("dccRedissonClient")
    public RedissonClient redissonClient(DynamicConfigCenterRegisterAutoProperties properties) {
        Config config = new Config();
        config.setCodec(JsonJacksonCodec.INSTANCE);
        config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                // .setPassword(properties.getPassword())
                .setConnectionPoolSize(properties.getPoolSize())
                .setConnectionMinimumIdleSize(properties.getMinIdleSize())
                .setIdleConnectionTimeout(properties.getIdleTimeout())
                .setConnectTimeout(properties.getConnectTimeout())
                .setRetryAttempts(properties.getRetryAttempts())
                .setRetryInterval(properties.getRetryInterval())
                .setPingConnectionInterval(properties.getPingInterval())
                .setKeepAlive(properties.isKeepAlive());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            config.useSingleServer().setPassword(properties.getPassword());
        }
        RedissonClient redissonClient = Redisson.create(config);
        logger.info("register redis initial: {} {} {}", properties.getHost(), properties.getPoolSize(), !redissonClient.isShutdown());
        return redissonClient;
    }

    @Bean
    public IDynamicConfigCenterService dynamicConfigCenterService(DynamicConfigCenterAutoProperties dynamicConfigCenterAutoProperties, RedissonClient dccRedissonClient) {
        return new DynamicConfigCenterService(dynamicConfigCenterAutoProperties, dccRedissonClient);
    }

    @Bean
    public DynamicConfigCenterAdjustListener dynamicConfigCenterAdjustListener(IDynamicConfigCenterService dynamicConfigCenterService) {
        return new DynamicConfigCenterAdjustListener(dynamicConfigCenterService);
    }

    @Bean(name = "dynamicConfigCenterRedisTopic")
    public RTopic dynamicConfigCenterRedisTopic(DynamicConfigCenterAutoProperties dynamicConfigCenterAutoProperties,
                                                RedissonClient redissonClient,
                                                DynamicConfigCenterAdjustListener dynamicConfigCenterAdjustListener) {
        RTopic topic = redissonClient.getTopic(Constants.getTopic(dynamicConfigCenterAutoProperties.getSystem()));
        topic.addListener(AttributeVO.class, dynamicConfigCenterAdjustListener);
        return topic;
    }

}
