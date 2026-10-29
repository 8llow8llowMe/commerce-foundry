package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.AdminOtpRepositoryAdapter;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpCredentialRepository;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpRecoveryCodeRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpProof;
import com.commercefoundry.domainlayer.member.application.model.IssuedRecoveryCodes;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpEnrollmentStorePort;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.RecoveryCode;
import com.commercefoundry.domainlayer.member.domain.model.Totp;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.global.properties.AdminOtpProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 같은 관리자의 OTP 등록 확인끼리 · 해제와 복구 코드 재발급이 겹칠 때 (#238 PR-A2) — 실제 PostgreSQL 에서 유니크 키 대기 · 자격 행 잠금 · 조건부 UPDATE · 롤백을 본다. 잠금 대기 상한은 엔진에 기대므로
 * H2 로는 볼 수 없다. 테스트 트랜잭션을 끄고(NOT_SUPPORTED) 스레드마다 실제 Processor 트랜잭션으로 돈다. 커밋된 행이 남으므로 회원 아이디는 테스트마다 새로 만든다.
 * Docker 가 없으면 건너뛴다(CI 는 돈다). 값은 전부 테스트 전용이다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "auth.admin-otp.secret-key=" + AdminOtpSecretCipherTest.KEY, "auth.admin-otp.allow-test-only-key=true"})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, AdminOtpSecretCipher.class, AdminOtpRepositoryAdapter.class, AdminOtpProcessor.class,
    AdminOtpConcurrencyTest.Beans.class})
class AdminOtpConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant ENROLLED_AT = Instant.parse("2026-10-29T03:00:00Z");
    private static final long ENROLLED_STEP = Totp.timeStep(ENROLLED_AT);
    /** 확인 step 다음 step — 해제 · 재발급의 TOTP 가 이 step 이다. */
    private static final Instant NOW = ENROLLED_AT.plusSeconds(Totp.PERIOD_SECONDS);
    private static final AtomicLong MEMBER_IDS = new AtomicLong(70_000L);

    @TestConfiguration
    @EnableConfigurationProperties(AdminOtpProperties.class)
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }

        /** 테스트 속도 — 강도만 낮춘 같은 BCrypt 다. */
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        AdminOtpEnrollmentStorePort adminOtpEnrollmentStorePort() {
            return mock(AdminOtpEnrollmentStorePort.class);
        }
    }

    @Autowired AdminOtpProcessor processor;
    @Autowired AdminOtpRepositoryPort port;
    @Autowired AdminOtpCredentialRepository credentialRepository;
    @Autowired AdminOtpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TransactionTemplate transactionTemplate;

    /** 확정 자격 + 복구 코드 8개를 커밋하고 그 원문을 돌려준다. */
    private List<String> enroll(long memberId) {
        IssuedRecoveryCodes issued = processor.issueRecoveryCodes();
        processor.confirm(memberId, SECRET, ENROLLED_STEP, ENROLLED_AT, issued.hashes());
        return issued.codes();
    }

    private long codeRows(long memberId) {
        return recoveryCodeRepository.findAll().stream().filter(code -> code.getMemberId() == memberId).count();
    }

    private long credentialRows(long memberId) {
        return credentialRepository.findAll().stream().filter(credential -> credential.getMemberId() == memberId).count();
    }

    private static MemberErrorCode codeOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(MemberException.class);
        return ((MemberException) thrown).getErrorCode();
    }

    @Test
    @DisplayName("해제가 자격 잠금을 쥔 동안 재발급은 대기 상한 안에 409 AUTH_044 로 끊기고 아무것도 쓰지 않는다. 해제가 끝나면 자격 · 복구 코드가 모두 없다")
    void regenerateWaitsThenBusyWhileDisableHoldsLock() throws Exception {
        long memberId = MEMBER_IDS.incrementAndGet();
        List<String> codes = enroll(memberId);
        AdminOtpProof disableProof = processor.resolveProof(memberId, Totp.code(SECRET, ENROLLED_STEP + 1), NOW);
        AdminOtpProof regenerateProof = processor.resolveProof(memberId, codes.getFirst(), NOW);
        List<String> newHashes = processor.issueRecoveryCodes().hashes();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> disabling = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                assertThat(port.lockByMemberId(memberId)).isTrue();
                locked.countDown();
                await(release);
                processor.disable(memberId, disableProof, NOW);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> processor.replaceRecoveryCodes(memberId, regenerateProof, newHashes, NOW));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            disabling.get(30, TimeUnit.SECONDS);

            assertThat(codeOf(failure)).isEqualTo(MemberErrorCode.OTP_BUSY);
            assertThat(failure.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(waitedMillis).isGreaterThanOrEqualTo(1_500L).isLessThan(4_000L);
            assertThat(port.findConfirmedAt(memberId)).isEmpty();
            assertThat(codeRows(memberId)).isZero();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("해제가 먼저 커밋되면 뒤의 재발급은 잠금을 얻은 뒤 자격이 없어 404 AUTH_036 이다 — 자격 없는 복구 코드가 남지 않는다")
    void regenerateAfterDisableFindsNothing() {
        long memberId = MEMBER_IDS.incrementAndGet();
        List<String> codes = enroll(memberId);
        AdminOtpProof regenerateProof = processor.resolveProof(memberId, codes.get(1), NOW);

        processor.disable(memberId, processor.resolveProof(memberId, codes.getFirst(), NOW), NOW);

        assertThat(codeOf(catchThrowable(() -> processor.replaceRecoveryCodes(memberId, regenerateProof, processor.issueRecoveryCodes().hashes(), NOW))))
            .isEqualTo(MemberErrorCode.OTP_NOT_ENROLLED);
        assertThat(port.findConfirmedAt(memberId)).isEmpty();
        assertThat(codeRows(memberId)).isZero();
    }

    @Test
    @DisplayName("순서와 무관한 불변식 — 해제와 재발급을 동시에 보내 어느 쪽이 먼저 잠금을 얻든, 해제가 성공했으면 자격 · 복구 코드가 0행이다. "
        + "해제가 밀리면(AUTH_044) 재발급만 반영되고, 재발급이 밀리면 AUTH_044 · AUTH_036 이다")
    void disableSuccessLeavesNothingRegardlessOfOrder() throws Exception {
        for (int round = 0; round < 5; round++) {
            long memberId = MEMBER_IDS.incrementAndGet();
            List<String> codes = enroll(memberId);
            AdminOtpProof disableProof = processor.resolveProof(memberId, Totp.code(SECRET, ENROLLED_STEP + 1), NOW);
            AdminOtpProof regenerateProof = processor.resolveProof(memberId, codes.getFirst(), NOW);
            List<String> newHashes = processor.issueRecoveryCodes().hashes();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Throwable> disable = executor.submit(() -> {
                    await(start);
                    return catchThrowable(() -> processor.disable(memberId, disableProof, NOW));
                });
                Future<Throwable> regenerate = executor.submit(() -> {
                    await(start);
                    return catchThrowable(() -> processor.replaceRecoveryCodes(memberId, regenerateProof, newHashes, NOW));
                });
                start.countDown();
                Throwable disableFailure = disable.get(30, TimeUnit.SECONDS);
                Throwable regenerateFailure = regenerate.get(30, TimeUnit.SECONDS);

                if (disableFailure == null) {
                    assertThat(port.findConfirmedAt(memberId)).as("round %d", round).isEmpty();
                    assertThat(codeRows(memberId)).as("round %d", round).isZero();
                    if (regenerateFailure != null) {
                        assertThat(codeOf(regenerateFailure)).isIn(MemberErrorCode.OTP_BUSY, MemberErrorCode.OTP_NOT_ENROLLED);
                    }
                } else {
                    assertThat(codeOf(disableFailure)).as("round %d", round).isEqualTo(MemberErrorCode.OTP_BUSY);
                    assertThat(regenerateFailure).as("한쪽은 끝난다").isNull();
                    assertThat(port.countUnusedRecoveryCodes(memberId)).isEqualTo(newHashes.size());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * 승자는 자격 INSERT · 복구 코드 넣기까지 마친 트랜잭션을 커밋하지 않고 쥔다. 그동안 패자의 INSERT 는 유니크 키({@code uk_admin_otp_credential_member_id})에서
     * 기다려야 한다 — 500ms 안에 끝나지 않는 것으로 본다. 승자가 커밋하면 패자는 유니크 키 위반으로 롤백되고, 패자의 복구 코드는 하나도 남지 않는다.
     */
    @Test
    @DisplayName("같은 관리자의 등록 확인 두 건이 겹치면 뒤쪽은 앞쪽 커밋까지 기다렸다가 409 AUTH_037 이고, 자격 1행 · 복구 코드 8행(16 아님)만 남는다")
    void concurrentConfirmLeavesOneCredential() throws Exception {
        long memberId = MEMBER_IDS.incrementAndGet();
        List<String> winnerHashes = processor.issueRecoveryCodes().hashes();
        List<String> loserHashes = processor.issueRecoveryCodes().hashes();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> winner = executor.submit(() -> catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
                processor.confirm(memberId, SECRET, ENROLLED_STEP, ENROLLED_AT, winnerHashes);
                inserted.countDown();
                await(release);
            })));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> loser = executor.submit(() -> catchThrowable(() -> processor.confirm(memberId, SECRET, ENROLLED_STEP, ENROLLED_AT, loserHashes)));
            assertThatThrownBy(() -> loser.get(500, TimeUnit.MILLISECONDS)).as("승자가 커밋하기 전에는 유니크 키에서 기다린다").isInstanceOf(TimeoutException.class);
            release.countDown();
            Throwable winnerFailure = winner.get(30, TimeUnit.SECONDS);
            Throwable loserFailure = loser.get(30, TimeUnit.SECONDS);

            assertThat(winnerFailure).isNull();
            assertThat(codeOf(loserFailure)).isEqualTo(MemberErrorCode.OTP_ALREADY_ENROLLED);
            assertThat(credentialRows(memberId)).isEqualTo(1L);
            assertThat(codeRows(memberId)).isEqualTo(RecoveryCode.COUNT);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("완료 기준 — 재발급에 쓴 TOTP 를 같은 step 에 다시 쓰면 AUTH_035, 쓴 복구 코드를 다시 쓰면 AUTH_035 이고 늦은 쪽 트랜잭션은 아무것도 바꾸지 않는다")
    void codesAreSingleUse() {
        long memberId = MEMBER_IDS.incrementAndGet();
        List<String> codes = enroll(memberId);
        String totp = Totp.code(SECRET, ENROLLED_STEP + 1);

        processor.replaceRecoveryCodes(memberId, processor.resolveProof(memberId, totp, NOW), processor.issueRecoveryCodes().hashes(), NOW);
        assertThat(codeOf(catchThrowable(() -> processor.resolveProof(memberId, totp, NOW)))).isEqualTo(MemberErrorCode.OTP_CODE_INVALID);
        assertThat(codeOf(catchThrowable(() -> processor.resolveProof(memberId, codes.getFirst(), NOW)))).as("재발급 전 코드는 무효")
            .isEqualTo(MemberErrorCode.OTP_CODE_INVALID);

        long other = MEMBER_IDS.incrementAndGet();
        List<String> otherCodes = enroll(other);
        AdminOtpProof first = processor.resolveProof(other, otherCodes.get(3), NOW);
        AdminOtpProof second = processor.resolveProof(other, otherCodes.get(3), NOW);
        processor.replaceRecoveryCodes(other, first, processor.issueRecoveryCodes().hashes(), NOW);
        List<Long> afterFirst = port.findUnusedRecoveryCodes(other).stream().map(code -> code.id()).toList();

        assertThat(codeOf(catchThrowable(() -> processor.disable(other, second, NOW)))).isEqualTo(MemberErrorCode.OTP_CODE_INVALID);
        assertThat(port.findConfirmedAt(other)).isPresent();
        assertThat(port.findUnusedRecoveryCodes(other).stream().map(code -> code.id()).toList()).isEqualTo(afterFirst);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
