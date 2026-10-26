package com.commercefoundry.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.apigateway.security.RevocationCheckingAuthenticationManager.FailurePolicy;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.error.SecurityErrorResolver;
import com.commercefoundry.security.principal.MemberPrincipal;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RevocationCheckingAuthenticationManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String JTI = "0b7c7f5e-4c1f-4a3e-9d55-0d2a7d3f1c11";
    private static final MockServerWebExchange EXCHANGE = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/4611686018427387904"));

    @Test
    @DisplayName("차단되지 않은 토큰은 위임 매니저의 인증을 그대로 돌려준다")
    void passesWhenNotRevoked() {
        Authentication result = manager(jti -> Mono.just(false), FailurePolicy.FAIL_CLOSED).authenticate(bearer()).block(TIMEOUT);

        assertThat(result.getPrincipal()).isInstanceOf(MemberPrincipal.class);
    }

    @Test
    @DisplayName("차단된 토큰은 리졸버가 004 로 판정하는 예외다")
    void revokedIs004() {
        assertThat(resolve(manager(jti -> Mono.just(true), FailurePolicy.FAIL_OPEN))).isEqualTo(SecurityErrorCode.TOKEN_REVOKED);
    }

    @Test
    @DisplayName("저장소 장애는 fail-closed 면 006, fail-open 이면 통과다")
    void storageFailureFollowsPolicy() {
        AccessTokenRevocationChecker down = jti -> Mono.error(new RedisConnectionFailureException("simulated outage"));

        assertThat(resolve(manager(down, FailurePolicy.FAIL_CLOSED))).isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
        assertThat(manager(down, FailurePolicy.FAIL_OPEN).authenticate(bearer()).block(TIMEOUT)).isNotNull();
    }

    @Test
    @DisplayName("조회가 멈추면 상한(CHECK_TIMEOUT) 뒤 fail-open 은 통과, fail-closed 는 006 이다 — 가상 시간")
    void hangingLookupTimesOutPerPolicy() {
        StepVerifier.withVirtualTime(() -> manager(jti -> Mono.never(), FailurePolicy.FAIL_OPEN).authenticate(bearer()))
            .expectSubscription()
            .expectNoEvent(RevocationCheckingAuthenticationManager.CHECK_TIMEOUT.minusMillis(1))
            .thenAwait(Duration.ofMillis(1))
            .assertNext(result -> assertThat(result.getPrincipal()).isInstanceOf(MemberPrincipal.class))
            .expectComplete()
            .verify(TIMEOUT);

        StepVerifier.withVirtualTime(() -> manager(jti -> Mono.never(), FailurePolicy.FAIL_CLOSED).authenticate(bearer()))
            .expectSubscription()
            .thenAwait(RevocationCheckingAuthenticationManager.CHECK_TIMEOUT)
            .expectErrorSatisfies(error -> assertThat(SecurityErrorResolver.resolve((AuthenticationException) error))
                .isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE))
            .verify(TIMEOUT);
    }

    @Test
    @DisplayName("빈 응답도 저장소 장애로 본다 — 차단 여부를 모르면 통과로 단정하지 않는다")
    void emptyLookupIsStorageFailure() {
        assertThat(resolve(manager(jti -> Mono.empty(), FailurePolicy.FAIL_CLOSED))).isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("키 규칙에 맞지 않는 jti 는 저장소 장애가 아니라 003 이다 — fail-open 이어도 통과시키지 않는다")
    void malformedJtiIs003EvenWhenFailOpen() {
        AccessTokenRevocationChecker rejectsKey = jti -> {
            throw new IllegalArgumentException("bad key");
        };

        assertThatThrownBy(() -> manager(rejectsKey, FailurePolicy.FAIL_OPEN).authenticate(bearer()).block(TIMEOUT))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("관리자 토큰의 iat 가 컷오프보다 이르면 004 다 (#193)")
    void adminIssuedBeforeCutoffIs004() {
        Instant issuedAt = Instant.parse("2026-10-26T01:00:00Z");

        assertThat(resolve(adminManager(issuedAt, memberId -> Mono.just(issuedAt.plusSeconds(1)), FailurePolicy.FAIL_CLOSED)))
            .isEqualTo(SecurityErrorCode.TOKEN_REVOKED);
    }

    @Test
    @DisplayName("관리자 토큰의 iat 가 컷오프와 같거나 컷오프가 없으면 통과다")
    void adminAtOrWithoutCutoffPasses() {
        Instant issuedAt = Instant.parse("2026-10-26T01:00:01Z");

        assertThat(adminManager(issuedAt, memberId -> Mono.just(issuedAt), FailurePolicy.FAIL_CLOSED).authenticate(bearer()).block(TIMEOUT)).isNotNull();
        assertThat(adminManager(issuedAt, memberId -> Mono.empty(), FailurePolicy.FAIL_CLOSED).authenticate(bearer()).block(TIMEOUT)).isNotNull();
    }

    @Test
    @DisplayName("고객 토큰은 컷오프를 조회하지 않는다")
    void customerSkipsAdminCutoff() {
        AccessTokenRevocationChecker checker = new AccessTokenRevocationChecker() {
            @Override
            public Mono<Boolean> isRevoked(String jti) {
                return Mono.just(false);
            }

            @Override
            public Mono<Instant> adminAccessCutoff(long memberId) {
                return Mono.error(new AssertionError("고객 요청이 컷오프를 조회했다"));
            }
        };

        assertThat(manager(checker, FailurePolicy.FAIL_CLOSED).authenticate(bearer()).block(TIMEOUT)).isNotNull();
    }

    @Test
    @DisplayName("관리자 토큰에 iat 가 없으면 003 이다 — fail-open 이어도 통과시키지 않는다")
    void adminWithoutIssuedAtIs003() {
        ReactiveAuthenticationManager manager = new RevocationCheckingAuthenticationManager(
            adminDelegate(null), notRevoked(memberId -> Mono.empty()), FailurePolicy.FAIL_OPEN, Clock.systemUTC()).forExchange(EXCHANGE);

        assertThatThrownBy(() -> manager.authenticate(bearer()).block(TIMEOUT)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("관리자 컷오프 저장소 장애는 fail-closed 면 006 이다")
    void adminCutoffStorageFailureIs006() {
        AccessTokenRevocationChecker down = new AccessTokenRevocationChecker() {
            @Override
            public Mono<Boolean> isRevoked(String jti) {
                return Mono.just(false);
            }

            @Override
            public Mono<Instant> adminAccessCutoff(long memberId) {
                return Mono.error(new RedisConnectionFailureException("simulated outage"));
            }
        };

        assertThat(resolve(new RevocationCheckingAuthenticationManager(
            adminDelegate(Instant.parse("2026-10-26T01:00:00Z")), down, FailurePolicy.FAIL_CLOSED, Clock.systemUTC()).forExchange(EXCHANGE)))
            .isEqualTo(SecurityErrorCode.TOKEN_VERIFICATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("fail-open warn 은 간격마다 한 번이다 — 간격 안의 나머지는 debug")
    void failOpenWarnIsRateLimited() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        RevocationCheckingAuthenticationManager manager =
            new RevocationCheckingAuthenticationManager(delegate(), jti -> Mono.just(false), FailurePolicy.FAIL_OPEN, clock);

        assertThat(manager.claimWarnSlot()).isTrue();
        assertThat(manager.claimWarnSlot()).isFalse();
        clock.advance(RevocationCheckingAuthenticationManager.FAIL_OPEN_WARN_INTERVAL.minusMillis(1));
        assertThat(manager.claimWarnSlot()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(manager.claimWarnSlot()).isTrue();
    }

    private static ReactiveAuthenticationManager manager(AccessTokenRevocationChecker checker, FailurePolicy policy) {
        return new RevocationCheckingAuthenticationManager(delegate(), checker, policy, Clock.systemUTC()).forExchange(EXCHANGE);
    }

    private static ReactiveAuthenticationManager adminManager(Instant issuedAt, java.util.function.LongFunction<Mono<Instant>> cutoff, FailurePolicy policy) {
        return new RevocationCheckingAuthenticationManager(adminDelegate(issuedAt), notRevoked(cutoff), policy, Clock.systemUTC()).forExchange(EXCHANGE);
    }

    private static AccessTokenRevocationChecker notRevoked(java.util.function.LongFunction<Mono<Instant>> cutoff) {
        return new AccessTokenRevocationChecker() {
            @Override
            public Mono<Boolean> isRevoked(String jti) {
                return Mono.just(false);
            }

            @Override
            public Mono<Instant> adminAccessCutoff(long memberId) {
                return cutoff.apply(memberId);
            }
        };
    }

    private static ReactiveAuthenticationManager adminDelegate(Instant issuedAt) {
        MemberPrincipal principal = new MemberPrincipal(TestTokens.MEMBER_ID, MemberRole.ADMIN, JTI, issuedAt);
        return authentication -> Mono.just(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(MemberRole.ADMIN.authority()))));
    }

    /** 서명 · 만료 검증을 통과했다고 치는 위임 매니저. */
    private static ReactiveAuthenticationManager delegate() {
        MemberPrincipal principal = new MemberPrincipal(4611686018427387904L, MemberRole.CUSTOMER, JTI, null);
        return authentication -> Mono.just(UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority(MemberRole.CUSTOMER.authority()))));
    }

    private static Authentication bearer() {
        return new BearerTokenAuthenticationToken("token");
    }

    private static SecurityErrorCode resolve(ReactiveAuthenticationManager manager) {
        try {
            manager.authenticate(bearer()).block(TIMEOUT);
        } catch (AuthenticationException rejected) {
            return SecurityErrorResolver.resolve(rejected);
        }
        throw new AssertionError("인증이 거부되지 않았다");
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
