package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.port.out.SocialStateStorePort;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SocialStateRedisAdapter implements SocialStateStorePort {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final TokenStoreRedisSupport support;

    @Override
    public boolean save(String stateHash, SocialProvider provider, String verifierHash, Duration ttl) {
        return support.execute(() -> Boolean.TRUE.equals(redis.opsForValue()
            .setIfAbsent(key(stateHash), binding(provider, verifierHash), ttl)));
    }

    @Override
    public boolean consume(String stateHash, SocialProvider provider, String verifierHash) {
        return support.execute(() -> Long.valueOf(1).equals(redis.execute(CONSUME, List.of(key(stateHash)), binding(provider, verifierHash))));
    }

    private static String key(String stateHash) {
        return com.commercefoundry.redis.key.RedisKey.of("auth", "oauth-state", stateHash).value();
    }

    private static String binding(SocialProvider provider, String verifierHash) {
        return provider.name() + ":" + verifierHash;
    }
}