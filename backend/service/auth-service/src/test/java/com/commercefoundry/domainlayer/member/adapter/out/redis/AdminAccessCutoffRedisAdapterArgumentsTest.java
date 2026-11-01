package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.revocation.AdminAccessCutoffKeys;
import com.commercefoundry.security.token.revocation.RevokedAccessTokenKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Docker 없이 Redis Lua 호출의 키·인자와 결과 해석을 본다. Lua 실행은 Testcontainers 테스트가 본다. */
class AdminAccessCutoffRedisAdapterArgumentsTest {

    private static final long MEMBER_ID = 42L;
    private static final Instant CUTOFF = Instant.parse("2026-10-29T03:00:01Z");
    private static final Duration SKEW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AdminAccessCutoffRedisAdapter adapter = new AdminAccessCutoffRedisAdapter(redisTemplate,
        new JwtVerificationProperties("test-issuer", "test-audience", SKEW, null, null),
        new TokenStoreRedisSupport(Clock.systemUTC()));

    @Test
    @DisplayName("컷오프는 한 Lua 호출에 키·epoch milli·TTL을 넘기고 갱신/오래된 쓰기 모두 성공 처리한다")
    void advanceArguments() {
        List<String> keys = List.of(AdminAccessCutoffKeys.of(MEMBER_ID).value());
        String ttlMillis = Long.toString(RevokedAccessTokenKeys.ttl(SKEW).toMillis());
        given(redisTemplate.execute(AdminAccessCutoffRedisScripts.ADVANCE, keys,
            Long.toString(CUTOFF.toEpochMilli()), ttlMillis)).willReturn(1L, 0L);

        adapter.cutOff(MEMBER_ID, CUTOFF);
        adapter.cutOff(MEMBER_ID, CUTOFF);

        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(AdminAccessCutoffRedisScripts.ADVANCE, keys,
            Long.toString(CUTOFF.toEpochMilli()), ttlMillis);
    }

    @Test
    @DisplayName("Lua 결과가 없으면 컷오프 성공으로 보고하지 않고 AUTH_013이다")
    void missingResultFailsClosed() {
        assertThatThrownBy(() -> adapter.cutOff(MEMBER_ID, CUTOFF))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }
}
