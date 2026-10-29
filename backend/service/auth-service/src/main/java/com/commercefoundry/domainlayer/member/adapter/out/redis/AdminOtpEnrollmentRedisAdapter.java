package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher.Sealed;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpEnrollmentStorePort;
import com.commercefoundry.redis.key.RedisKey;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 확인 전 관리자 OTP 등록(Redis, #238 PR-A2). 키는 {@code auth:admin-otp-enrollment:{memberId}} STRING 이고 TTL 은 {@code auth.admin-otp.enrollment-ttl} 이다.
 * 값은 {@code {keyId}:{base64(IV ‖ 암호문 ‖ 태그)}} 다 — {@link AdminOtpSecretCipher#sealEnrollment} 의 AAD 가 회원을 묶어, 다른 관리자 키로 값을 옮겨 붙이면
 * 복호화가 실패({@code AUTH_039})한다. 원문 비밀은 Redis 에 닿지 않는다. 두 부분 모두 base64 라 구분자 {@code :} 가 섞이지 않는다.
 *
 * <p>저장은 덮어쓰기(SET … PX)다 — 같은 관리자가 등록을 다시 시작하면 이전 비밀은 버려진다. Redis 오류는 {@link TokenStoreRedisSupport} 가 {@code AUTH_013} 으로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class AdminOtpEnrollmentRedisAdapter implements AdminOtpEnrollmentStorePort {

    private static final String SEPARATOR = ":";

    private final StringRedisTemplate redisTemplate;
    private final AdminOtpSecretCipher cipher;
    private final TokenStoreRedisSupport tokenStore;

    @Override
    public void save(long memberId, byte[] secret, Duration ttl) {
        if (ttl.toMillis() < 1) {
            throw new IllegalArgumentException("ttl 은 1ms 이상이어야 한다");
        }
        Sealed sealed = cipher.sealEnrollment(memberId, secret);
        String value = sealed.keyId() + SEPARATOR + sealed.ciphertext();
        tokenStore.execute(() -> {
            redisTemplate.opsForValue().set(key(memberId), value, ttl);
            return null;
        });
    }

    /** 모양이 깨진 값(구분자 없음)도 복호화 실패와 같은 {@code AUTH_039} 다 — 값은 예외 · 로그에 싣지 않는다. */
    @Override
    public Optional<byte[]> find(long memberId) {
        String value = tokenStore.execute(() -> redisTemplate.opsForValue().get(key(memberId)));
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.indexOf(SEPARATOR);
        if (separator <= 0) {
            throw new MemberException(MemberErrorCode.OTP_UNAVAILABLE);
        }
        return Optional.of(cipher.openEnrollment(memberId, value.substring(separator + 1), value.substring(0, separator)));
    }

    @Override
    public void delete(long memberId) {
        tokenStore.execute(() -> redisTemplate.delete(key(memberId)));
    }

    static String key(long memberId) {
        return RedisKey.of("auth", "admin-otp-enrollment", String.valueOf(memberId)).value();
    }
}
