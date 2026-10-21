package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static com.commercefoundry.domainlayer.member.adapter.out.redis.TokenStoreRedisSupport.execute;

import com.commercefoundry.domainlayer.member.application.port.out.LoginAttemptCounterPort;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 · 회원가입 시도 계수기(Redis). 키는 {@link LoginAttemptRedisKeys}, 원자 처리는 {@link LoginAttemptRedisScripts} 다.
 * 이메일은 {@link LoginAttemptKeyHasher} 의 HMAC 으로만 키에 들어간다. Boot 가 만드는 {@link StringRedisTemplate} 만 쓴다 ({@code coding-conventions.md} §9-7).
 * Redis 오류는 {@link TokenStoreRedisSupport} 가 {@code AUTH_013} 으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptRedisAdapter implements LoginAttemptCounterPort {

    private final StringRedisTemplate redisTemplate;
    private final LoginAttemptKeyHasher keyHasher;

    /** 창은 밀리초 정수 문자열로 넘긴다 — 1ms 미만이면 PEXPIRE 가 키를 바로 지워 창이 서지 않으므로 받지 않는다. */
    @Override
    public LoginAttemptOutcome record(AttemptKind kind, String normalizedEmail, Duration window) {
        if (window.toMillis() < 1) {
            throw new IllegalArgumentException("window 는 1ms 이상이어야 한다");
        }
        List<String> keys = List.of(key(kind, normalizedEmail));
        List<Object> result = execute(() -> redisTemplate.execute(LoginAttemptRedisScripts.RECORD, keys, String.valueOf(window.toMillis())));
        return toOutcome(result);
    }

    @Override
    public void reset(AttemptKind kind, String normalizedEmail) {
        String key = key(kind, normalizedEmail);
        execute(() -> redisTemplate.delete(key));
    }

    /** 스크립트 반환 {@code {시도 수, 남은 시간(ms)}} — 둘 다 정수 응답(Long)이다. 모양이 다르면 스크립트와 코드가 어긋난 것이라 그대로 실패한다. */
    static LoginAttemptOutcome toOutcome(List<Object> result) {
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Number count) || !(result.get(1) instanceof Number ttlMillis)) {
            throw new IllegalStateException("로그인 시도 계수 스크립트의 결과 모양이 다릅니다: " + result);
        }
        return new LoginAttemptOutcome(count.longValue(), Duration.ofMillis(ttlMillis.longValue()));
    }

    private String key(AttemptKind kind, String normalizedEmail) {
        return LoginAttemptRedisKeys.attempt(kind, keyHasher.hash(normalizedEmail)).value();
    }
}
