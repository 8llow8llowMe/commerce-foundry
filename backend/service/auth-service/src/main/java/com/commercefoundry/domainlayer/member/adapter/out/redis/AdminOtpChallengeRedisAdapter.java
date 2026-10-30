package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpChallengeStorePort;
import com.commercefoundry.redis.key.RedisKey;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 2단계 챌린지(Redis, #238 PR-B). 키는 {@code auth:admin-otp-challenge:{SHA-256(챌린지) base64url}} STRING 이고
 * 값은 {@code {관리자 memberId}:{발급 시각 epoch milli}}(둘 다 10진), TTL 은 {@code auth.admin-otp.challenge-ttl} 이다.
 * 챌린지 원문은 Redis 에 닿지 않는다 — 키를 읽을 수 있어도 2단계 요청을 만들 수 없다.
 *
 * <p>저장은 SET NX PX, 소비는 GETDEL 한 명령이라 같은 챌린지의 동시 2단계는 한쪽만 값을 받는다. 모양이 다른 값(손상 · 발급 시각 없는 값)은 없는 챌린지로 본다.
 * Redis 오류는 {@link TokenStoreRedisSupport} 가 {@code AUTH_013} 으로 바꾼다 — 키(해시)는 예외 · 로그에 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AdminOtpChallengeRedisAdapter implements AdminOtpChallengeStorePort {

    private static final Pattern VALUE = Pattern.compile("([1-9][0-9]{0,18}):(0|[1-9][0-9]{0,18})");

    private final StringRedisTemplate redisTemplate;
    private final TokenStoreRedisSupport tokenStore;

    @Override
    public boolean save(String challengeHash, AdminOtpChallenge challenge, Duration ttl) {
        if (ttl.toMillis() < 1) {
            throw new IllegalArgumentException("ttl 은 1ms 이상이어야 한다");
        }
        String value = challenge.memberId() + ":" + challenge.issuedAt().toEpochMilli();
        return tokenStore.execute(() -> Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key(challengeHash), value, ttl)));
    }

    @Override
    public Optional<AdminOtpChallenge> find(String challengeHash) {
        return challenge(tokenStore.execute(() -> redisTemplate.opsForValue().get(key(challengeHash))));
    }

    @Override
    public Optional<AdminOtpChallenge> consume(String challengeHash) {
        return challenge(tokenStore.execute(() -> redisTemplate.opsForValue().getAndDelete(key(challengeHash))));
    }

    static String key(String challengeHash) {
        return RedisKey.of("auth", "admin-otp-challenge", challengeHash).value();
    }

    private static Optional<AdminOtpChallenge> challenge(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = VALUE.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AdminOtpChallenge(Long.parseLong(matcher.group(1)), Instant.ofEpochMilli(Long.parseLong(matcher.group(2)))));
        } catch (NumberFormatException | DateTimeException overflow) {
            return Optional.empty();
        }
    }
}
