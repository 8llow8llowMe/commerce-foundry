package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminOtpCredentialEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminOtpCredentialRepository;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpCredential;
import com.commercefoundry.domainlayer.member.domain.model.AdminOtpRecoveryCode;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.global.properties.AdminOtpProperties;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너에서 관리자 OTP 저장의 유니크 키 · 조건부 UPDATE · 복구 코드 1회 사용 · 자격 잠금을 실제로 돌린다 (#238). 스키마는 DDL 스냅숏으로 만들고
 * {@code validate} 로 엔티티와 대조한다. Docker 가 없으면 건너뛴다(CI 는 돈다).
 *
 * <p>두 트랜잭션이 같은 자격 잠금을 다투는 동시성(대기 상한 → {@code AUTH_044})은 PR-A2 에서 Processor 트랜잭션과 함께 본다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "auth.admin-otp.secret-key=" + AdminOtpSecretCipherTest.KEY, "auth.admin-otp.allow-test-only-key=true"})
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, AdminOtpSecretCipher.class, AdminOtpRepositoryAdapter.class,
    AdminOtpPersistenceTest.Beans.class})
class AdminOtpPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z").truncatedTo(ChronoUnit.MICROS);
    /** 감사 시각(실제 시계)과 겹치지 않는 기록 시각 — 수정 시각이 바뀌었는지 본다. */
    private static final Instant LATER = Instant.parse("2030-01-01T00:00:00Z");

    @TestConfiguration
    @EnableConfigurationProperties(AdminOtpProperties.class)
    static class Beans {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }
    }

    @Autowired AdminOtpRepositoryPort port;
    @Autowired AdminOtpCredentialRepository credentialRepository;

    private static AdminOtpCredential confirmed(long id, long memberId, long step) {
        return AdminOtpCredential.confirm(id, memberId, SECRET, step, NOW);
    }

    @Test
    @DisplayName("저장한 비밀은 암호문으로만 남고, 읽으면 원문으로 돌아온다. 아이디는 호출자가 정한 값이다")
    void saveAndFind() {
        port.save(confirmed(5001L, 1001L, 100L));

        AdminOtpCredential found = port.findByMemberId(1001L).orElseThrow();

        assertThat(found.id()).isEqualTo(5001L);
        assertThat(found.secret()).isEqualTo(SECRET);
        assertThat(found.lastUsedTimeStep()).isEqualTo(100L);
        assertThat(found.confirmedAt()).isEqualTo(NOW);
        assertThat(credentialRepository.findByMemberId(1001L).orElseThrow().getSecretCiphertext())
            .isNotEqualTo(new String(SECRET, StandardCharsets.US_ASCII)).doesNotContain("1234567890");
        assertThat(port.findByMemberId(9999L)).isEmpty();
    }

    @Test
    @DisplayName("회원당 하나 — 같은 회원으로 두 번 저장하면 uk_admin_otp_credential_member_id 가 막고 AUTH_037 이다")
    void memberIdUnique() {
        port.save(confirmed(5002L, 1002L, 1L));

        assertThatThrownBy(() -> port.save(confirmed(5003L, 1002L, 2L))).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.OTP_ALREADY_ENROLLED);
    }

    @Test
    @DisplayName("조건부 UPDATE — 마지막 step 보다 늦으면 1행(버전 +1 · 수정 시각 갱신), 같거나 이르면 0행이다(같은 코드 재사용 · 동시 요청)")
    void recordTimeStepIsConditional() {
        AdminOtpCredential credential = port.save(confirmed(5004L, 1003L, 100L));
        AdminOtpCredentialEntity before = credentialRepository.findById(5004L).orElseThrow();
        long versionBefore = before.getVersion();
        Instant updatedBefore = before.getUpdatedAt();

        assertThat(port.recordTimeStep(credential.accept(101L).orElseThrow(), LATER)).isTrue();

        AdminOtpCredentialEntity after = credentialRepository.findById(5004L).orElseThrow();
        assertThat(after.getLastUsedTimeStep()).isEqualTo(101L);
        assertThat(after.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(after.getUpdatedAt()).isEqualTo(LATER).isNotEqualTo(updatedBefore);

        assertThat(port.recordTimeStep(credential.accept(101L).orElseThrow(), NOW)).isFalse();
        assertThat(port.recordTimeStep(new AdminOtpCredential(5004L, 1003L, SECRET, 100L, NOW), NOW)).isFalse();
        assertThat(credentialRepository.findById(5004L).orElseThrow().getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("조건부 UPDATE 는 자격 아이디까지 맞아야 한다 — 같은 회원이어도 다른 아이디(해제 후 재등록 전의 옛 자격)면 0행이다")
    void recordTimeStepRequiresSameId() {
        port.save(confirmed(5005L, 1004L, 10L));

        assertThat(credentialRepository.advanceTimeStep(9999L, 1004L, 11L, NOW)).isZero();
        assertThat(credentialRepository.advanceTimeStepAndReseal(9999L, 1004L, credentialRepository.findByMemberId(1004L).orElseThrow().getKeyId(),
            11L, "x", "y", NOW)).isZero();
        assertThat(port.recordTimeStep(new AdminOtpCredential(9999L, 1004L, SECRET, 11L, NOW), NOW)).isFalse();
        assertThat(port.findByMemberId(1004L).orElseThrow().lastUsedTimeStep()).isEqualTo(10L);
    }

    @Test
    @DisplayName("이전 키로 만든 행은 기록하면서 현재 키로 다시 암호화한다 — key_id 가 현재 키로 바뀌고 원문은 같다. 옛 키 조건이 다르면 재암호화하지 않는다")
    void recordTimeStepResealsOldKey() {
        AdminOtpSecretCipher oldCipher = AdminOtpSecretCipherTest.cipher(AdminOtpSecretCipherTest.OTHER_KEY, null);
        AdminOtpSecretCipher.Sealed old = oldCipher.sealCredential(1005L, 5006L, SECRET);
        port.save(confirmed(5006L, 1005L, 10L));
        String currentKeyId = credentialRepository.findById(5006L).orElseThrow().getKeyId();
        // 옛 키 조건이 맞지 않으면(다른 회전 결과가 이미 들어옴) 0행 — 덮지 않는다
        assertThat(credentialRepository.advanceTimeStepAndReseal(5006L, 1005L, "not-the-key", 11L, old.ciphertext(), old.keyId(), NOW)).isZero();
        // 회전 전 상태를 만든다 — 같은 조건부 UPDATE 로 옛 키 암호문을 넣는다
        assertThat(credentialRepository.advanceTimeStepAndReseal(5006L, 1005L, currentKeyId, 11L, old.ciphertext(), old.keyId(), NOW)).isOne();

        // 지금 port 의 cipher 에는 previous 가 없어 옛 행을 못 읽는다 — 기록은 accept 결과(원문)로 하므로 읽지 않고 재암호화한다
        assertThat(port.recordTimeStep(new AdminOtpCredential(5006L, 1005L, SECRET, 12L, NOW), NOW)).isTrue();

        assertThat(credentialRepository.findByMemberId(1005L).orElseThrow().getKeyId()).isEqualTo(currentKeyId).isNotEqualTo(old.keyId());
        assertThat(port.findByMemberId(1005L).orElseThrow().secret()).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("복구 코드 — 교체하면 새 코드만 남고, 사용 표시는 한 번만 성공한다(다른 회원 코드는 0행)")
    void recoveryCodeUsedOnce() {
        port.save(confirmed(5007L, 1006L, 1L));
        port.replaceRecoveryCodes(1006L, List.of("hash-a", "hash-b"));
        port.replaceRecoveryCodes(1006L, List.of("hash-c", "hash-d", "hash-e"));

        List<AdminOtpRecoveryCode> unused = port.findUnusedRecoveryCodes(1006L);
        assertThat(unused).extracting(AdminOtpRecoveryCode::codeHash).containsExactly("hash-c", "hash-d", "hash-e");

        long first = unused.getFirst().id();
        assertThat(port.markRecoveryCodeUsed(first, 9999L, NOW)).isFalse();
        assertThat(port.markRecoveryCodeUsed(first, 1006L, NOW)).isTrue();
        assertThat(port.markRecoveryCodeUsed(first, 1006L, NOW)).isFalse();
        assertThat(port.findUnusedRecoveryCodes(1006L)).extracting(AdminOtpRecoveryCode::codeHash).containsExactly("hash-d", "hash-e");
    }

    @Test
    @DisplayName("복구 코드 교체는 자격이 있어야 한다 — 없으면 AUTH_036 이고 코드가 생기지 않는다(잠금 쿼리가 실제 PostgreSQL 에서 돈다)")
    void replaceWithoutCredentialRejected() {
        assertThatThrownBy(() -> port.replaceRecoveryCodes(1007L, List.of("hash-a"))).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.OTP_NOT_ENROLLED);
        assertThat(port.findUnusedRecoveryCodes(1007L)).isEmpty();
    }

    @Test
    @DisplayName("해제 — 자격과 복구 코드를 함께 지운다. 다른 회원 행은 그대로다. 없는 회원은 아무 일도 없다")
    void deleteByMemberId() {
        port.save(confirmed(5008L, 1008L, 1L));
        port.replaceRecoveryCodes(1008L, List.of("hash-a"));
        port.save(confirmed(5009L, 1009L, 1L));
        port.replaceRecoveryCodes(1009L, List.of("hash-b"));

        port.deleteByMemberId(1008L);
        port.deleteByMemberId(9999L);

        assertThat(port.findByMemberId(1008L)).isEmpty();
        assertThat(port.findUnusedRecoveryCodes(1008L)).isEmpty();
        assertThat(port.findByMemberId(1009L)).isPresent();
        assertThat(port.findUnusedRecoveryCodes(1009L)).hasSize(1);
    }
}
