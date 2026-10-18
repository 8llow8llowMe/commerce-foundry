package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.mapper.MemberAgreementMapperImpl;
import com.commercefoundry.domainlayer.member.application.mapper.MemberMapperImpl;
import com.commercefoundry.domainlayer.member.application.port.out.MemberAgreementRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.MemberAgreement;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 컨테이너에 DDL 스냅숏을 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한 뒤 실제로 질의한다
 * ({@code bootstrap-conventions.md} "리포지토리 테스트"). Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다.
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=auth",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({
    AuthServiceFeaturesConfig.class,
    MemberAgreementMapperImpl.class,
    MemberAgreementRepositoryAdapter.class,
    MemberMapperImpl.class,
    MemberRepositoryAdapter.class,
    QuerydslConfigurer.class
})
class MemberPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/auth-schema.sql");

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012345";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private MemberAgreementRepositoryPort memberAgreementRepositoryPort;

    @Test
    @DisplayName("새 회원을 넣으면 버전 0 · 감사 시각이 채워지고, 아이디 · 정규화한 이메일로 다시 읽힌다")
    void insertAndFind() {
        Member saved = memberRepositoryPort.save(Member.signUp(1L, "Customer@Example.com", HASH, "홍길동", "010-1234-5678"));
        entityManager.clear();

        assertThat(saved.version()).isZero();
        assertThat(memberRepositoryPort.existsByEmail("customer@example.com")).isTrue();
        assertThat(memberRepositoryPort.existsByEmail("other@example.com")).isFalse();
        assertThat(memberRepositoryPort.findById(1L)).hasValueSatisfying(member -> assertThat(member)
            .extracting(Member::email, Member::passwordHash, Member::name, Member::phone, Member::role, Member::version)
            .containsExactly("customer@example.com", HASH, "홍길동", "01012345678", MemberRole.CUSTOMER, 0L));
        MemberEntity entity = entityManager.find(MemberEntity.class, 1L);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 이메일을 다시 넣으면 유니크 키(uk_member_email) 위반이 AUTH_002 가 된다 — 사전 조회를 함께 지난 동시 가입")
    void duplicateEmailBecomesConflict() {
        memberRepositoryPort.save(Member.signUp(1L, "customer@example.com", HASH, "홍길동", null));
        entityManager.clear();

        assertThatThrownBy(() -> memberRepositoryPort.save(Member.signUp(2L, "CUSTOMER@example.com", HASH, "김철수", null)))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("수정은 버전을 올리고 생성 시각을 지우지 않는다")
    void updateBumpsVersion() {
        memberRepositoryPort.save(Member.signUp(1L, "customer@example.com", HASH, "홍길동", "01012345678"));
        entityManager.clear();

        Member loaded = memberRepositoryPort.findById(1L).orElseThrow();
        Member updated = memberRepositoryPort.save(loaded.changeProfile("김철수", ""));
        entityManager.clear();

        assertThat(updated.version()).isEqualTo(1L);
        MemberEntity entity = entityManager.find(MemberEntity.class, 1L);
        assertThat(entity.getName()).isEqualTo("김철수");
        assertThat(entity.getPhone()).isNull();
        assertThat(entity.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 요청이 먼저 고친 옛 버전으로 저장하면 AUTH_004 다 (낙관적 락)")
    void staleVersionBecomesConflict() {
        memberRepositoryPort.save(Member.signUp(1L, "customer@example.com", HASH, "홍길동", null));
        entityManager.clear();
        Member stale = memberRepositoryPort.findById(1L).orElseThrow();
        memberRepositoryPort.save(stale.changeProfile("먼저 고침", null));
        entityManager.clear();

        assertThatThrownBy(() -> memberRepositoryPort.save(stale.changeProfile("나중에 고침", null)))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("버전 올리기는 읽은 버전일 때만 된다 — 같은 버전을 읽은 두 수정 중 뒤의 것은 AUTH_004 다")
    void incrementVersion() {
        memberRepositoryPort.save(Member.signUp(1L, "customer@example.com", HASH, "홍길동", null));
        entityManager.clear();
        Member read = memberRepositoryPort.findById(1L).orElseThrow();

        memberRepositoryPort.incrementVersion(read);

        assertThatThrownBy(() -> memberRepositoryPort.incrementVersion(read))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
        assertThat(memberRepositoryPort.findById(1L)).hasValueSatisfying(member -> assertThat(member.version()).isEqualTo(1L));
    }

    @Test
    @DisplayName("규칙 밖으로 저장된 행(이관 데이터)도 읽힌다 — 복원은 검증하지 않는다")
    void readsOutOfRuleRow() {
        entityManager.persist(MemberEntity.builder()
            .id(9L).email("legacy@example.com").passwordHash(HASH).name("가".repeat(50)).phone("02-123-4567").role(MemberRole.CUSTOMER).build());
        entityManager.flush();
        entityManager.clear();

        assertThat(memberRepositoryPort.findById(9L)).hasValueSatisfying(member -> assertThat(member.phone()).isEqualTo("02-123-4567"));
    }

    @Test
    @DisplayName("동의 이력은 추가만 하고, 최근 행은 그 회원 · 유형에서 가장 나중 것이다")
    void agreementHistory() {
        memberAgreementRepositoryPort.appendAll(List.of(
            new MemberAgreement(10L, 1L, AgreementType.TERMS_OF_SERVICE, true, "2026-10-01"),
            new MemberAgreement(11L, 1L, AgreementType.MARKETING, false, "2026-10-01"),
            new MemberAgreement(12L, 2L, AgreementType.MARKETING, true, "2026-10-01")
        ));
        entityManager.flush();
        memberAgreementRepositoryPort.appendAll(List.of(new MemberAgreement(13L, 1L, AgreementType.MARKETING, true, "2026-11-01")));
        entityManager.flush();
        entityManager.clear();

        assertThat(memberAgreementRepositoryPort.findLatest(1L, AgreementType.MARKETING))
            .contains(new MemberAgreement(13L, 1L, AgreementType.MARKETING, true, "2026-11-01"));
        assertThat(memberAgreementRepositoryPort.findLatest(1L, AgreementType.PRIVACY_POLICY)).isEmpty();
        assertThat(memberAgreementRepositoryPort.findLatest(3L, AgreementType.MARKETING)).isEmpty();
        assertThat(entityManager.find(MemberAgreementEntity.class, 11L).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("빈 목록은 쿼리 없이 끝난다")
    void appendNothing() {
        memberAgreementRepositoryPort.appendAll(List.of());

        assertThat(memberAgreementRepositoryPort.findLatest(1L, AgreementType.MARKETING)).isEmpty();
    }
}
