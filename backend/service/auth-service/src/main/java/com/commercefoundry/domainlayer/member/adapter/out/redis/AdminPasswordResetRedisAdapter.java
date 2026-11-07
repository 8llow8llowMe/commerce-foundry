package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminPasswordResetTokenPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminPasswordResetRedisAdapter implements AdminPasswordResetTokenPort {

    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>(
        "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; return n", Long.class);
    private final StringRedisTemplate redisTemplate;
    private final TokenStoreRedisSupport tokenStore;

    @Override
    public void save(String fingerprint, AdminPasswordResetGrant grant, Duration ttl) {
        tokenStore.execute(() -> { redisTemplate.opsForValue().set(key(fingerprint), grant.memberId() + ":" + grant.memberVersion(), ttl); return null; });
    }

    @Override
    public Optional<AdminPasswordResetGrant> consume(String fingerprint) {
        // GETDEL 한 문장으로 소비한다. 경쟁 요청 중 한 요청만 grant를 받는다.
        String value = tokenStore.execute(() -> redisTemplate.opsForValue().getAndDelete(key(fingerprint)));
        if (value == null) { return Optional.empty(); }
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) { return Optional.empty(); }
            return Optional.of(new AdminPasswordResetGrant(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
        } catch (NumberFormatException invalid) { return Optional.empty(); }
    }

    @Override
    public void discard(String fingerprint) { tokenStore.execute(() -> redisTemplate.delete(key(fingerprint))); }

    @Override
    public void limit(String dimension, int maximum, Duration window) {
        Long count = tokenStore.execute(() -> redisTemplate.execute(LIMIT,
            List.of("auth:admin-password-reset-attempt:" + dimension), Long.toString(window.toMillis())));
        if (count == null) { throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE); }
        if (count > maximum) { throw new MemberException(MemberErrorCode.ADMIN_PASSWORD_RESET_LIMITED); }
    }

    private static String key(String fingerprint) { return "auth:admin-password-reset:" + fingerprint; }
}
