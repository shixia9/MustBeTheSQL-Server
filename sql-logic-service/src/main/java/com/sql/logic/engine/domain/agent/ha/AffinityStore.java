package com.sql.logic.engine.domain.agent.ha;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AffinityStore {

    private final RedissonClient redissonClient;
    private final Duration ttl = Duration.ofMinutes(HaConstants.AFFINITY_EXPIRE_MINUTES);

    public AffinityStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public Long getBinding(String sessionId) {
        RBucket<Long> bucket = bucket(sessionId);
        Long configId = bucket.get();
        if (configId != null) {
            bucket.expire(ttl);
        }
        return configId;
    }

    public void bind(String sessionId, Long configId) {
        bucket(sessionId).set(configId, ttl);
    }

    public void unbind(String sessionId) {
        bucket(sessionId).delete();
    }

    private RBucket<Long> bucket(String sessionId) {
        return redissonClient.getBucket(HaConstants.AFFINITY_RBUCKET_KEY_PREFIX + sessionId);
    }
}
