package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminLoginChallengeInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpChallengeStorePort;
import com.commercefoundry.global.properties.AdminOtpProperties;
import com.commercefoundry.support.InMemoryAdminOtpChallengeStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AdminLoginChallengeProcessorTest {

    private static final AdminOtpProperties PROPERTIES = new AdminOtpProperties(
        AdminOtpSecretCipherTest.KEY, null, "Test Issuer", Duration.ofMinutes(15), Duration.ofMinutes(5), true);
    private static final Instant ISSUED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private static final AdminOtpChallenge CHALLENGE = new AdminOtpChallenge(9L, ISSUED_AT);

    private final InMemoryAdminOtpChallengeStore store = new InMemoryAdminOtpChallengeStore(Clock.systemUTC());
    private final AdminLoginChallengeProcessor processor = new AdminLoginChallengeProcessor(store, PROPERTIES);

    @Test
    @DisplayName("32바이트 base64url(43자) 챌린지를 내주고 저장소에는 SHA-256(base64url) 만 TTL(challenge-ttl)과 함께 둔다 — 원문은 저장소 · toString 에 없다")
    void issueStoresOnlyHash() throws Exception {
        AdminOtpChallengeStorePort port = mock(AdminOtpChallengeStorePort.class);
        given(port.save(anyString(), eq(CHALLENGE), eq(Duration.ofMinutes(5)))).willReturn(true);

        AdminLoginChallengeInfo info = new AdminLoginChallengeProcessor(port, PROPERTIES).issue(9L, ISSUED_AT);

        assertThat(info.challengeToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(info.expiresIn()).isEqualTo(300);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(port).save(hash.capture(), eq(CHALLENGE), eq(Duration.ofMinutes(5)));
        String expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(info.challengeToken().getBytes(StandardCharsets.US_ASCII)));
        assertThat(hash.getValue()).isEqualTo(expected).isNotEqualTo(info.challengeToken());
        assertThat(info.toString()).doesNotContain(info.challengeToken());
    }

    @Test
    @DisplayName("해시가 겹치면 새 챌린지로 다시 시도하고, 세 번 모두 겹치면 저장소 이상(AUTH_013)이다")
    void retriesOnCollision() {
        AdminOtpChallengeStorePort port = mock(AdminOtpChallengeStorePort.class);
        given(port.save(anyString(), any(AdminOtpChallenge.class), any())).willReturn(false);

        assertThatThrownBy(() -> new AdminLoginChallengeProcessor(port, PROPERTIES).issue(9L, ISSUED_AT))
            .isInstanceOfSatisfying(MemberException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE));
        verify(port, times(3)).save(anyString(), any(AdminOtpChallenge.class), any());
    }

    @Test
    @DisplayName("조회는 소비하지 않는다 — 같은 챌린지를 여러 번 읽을 수 있고, 소비는 한 번만 통과한다(두 번째는 AUTH_034)")
    void findDoesNotConsume() {
        String token = processor.issue(9L, ISSUED_AT).challengeToken();

        assertThat(processor.find(token)).isEqualTo(CHALLENGE);
        assertThat(processor.find(token)).isEqualTo(CHALLENGE);
        processor.consume(token, CHALLENGE);

        assertThatThrownBy(() -> processor.consume(token, CHALLENGE)).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
        assertThatThrownBy(() -> processor.find(token)).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
    }

    @Test
    @DisplayName("다른 관리자의 아이디로는 소비가 통과하지 않는다(AUTH_034)")
    void consumeChecksOwner() {
        String token = processor.issue(9L, ISSUED_AT).challengeToken();

        assertThatThrownBy(() -> processor.consume(token, new AdminOtpChallenge(10L, ISSUED_AT))).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "short", "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCg=", "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCggX",
        "n4bQgYhMfWWaL qgxVrQFaO_TxsrC4Is0V1sFbDwCgg"})
    @DisplayName("모양이 다른 챌린지는 저장소를 부르지 않고 AUTH_034 다")
    void malformedSkipsStore(String token) {
        AdminOtpChallengeStorePort port = mock(AdminOtpChallengeStorePort.class);
        AdminLoginChallengeProcessor guarded = new AdminLoginChallengeProcessor(port, PROPERTIES);

        assertThatThrownBy(() -> guarded.find(token)).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
        assertThatThrownBy(() -> guarded.consume(token, CHALLENGE)).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("저장소 장애(AUTH_013)는 바꾸지 않고 그대로 올린다(fail-closed)")
    void storeUnavailablePropagates() {
        AdminOtpChallengeStorePort port = mock(AdminOtpChallengeStorePort.class);
        MemberException unavailable = new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        given(port.find(anyString())).willThrow(unavailable);
        given(port.consume(anyString())).willReturn(Optional.empty());
        AdminLoginChallengeProcessor guarded = new AdminLoginChallengeProcessor(port, PROPERTIES);
        String token = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg";

        assertThatThrownBy(() -> guarded.find(token)).isSameAs(unavailable);
        assertThatThrownBy(() -> guarded.consume(token, CHALLENGE)).satisfies(AdminLoginChallengeProcessorTest::isChallengeInvalid);
        verify(port, never()).save(anyString(), any(AdminOtpChallenge.class), any());
    }

    private static void isChallengeInvalid(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(MemberException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(MemberErrorCode.OTP_CHALLENGE_INVALID));
    }
}
