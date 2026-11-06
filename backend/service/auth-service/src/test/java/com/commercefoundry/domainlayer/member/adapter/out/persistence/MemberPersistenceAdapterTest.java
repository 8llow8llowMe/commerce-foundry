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
import java.time.Instant;
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
        assertThat(memberRepositoryPort.findByEmail("customer@example.com")).hasValueSatisfying(member -> assertThat(member.id()).isEqualTo(1L));
        assertThat(memberRepositoryPort.findByEmail("other@example.com")).isEmpty();
        assertThat(memberRepositoryPort.findById(1L)).hasValueSatisfying(member -> assertThat(member)
            .extracting(Member::email, Member::passwordHash, Member::name, Member::phone, Member::role, Member::version)
            .containsExactly("customer@example.com", HASH, "홍길동", "01012345678", MemberRole.CUSTOMER, 0L));
        MemberEntity entity = entityManager.find(MemberEntity.class, 1L);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("잠금 조회는 회원을 읽고 버전을 올리지 않는다 — 없는 회원은 빈 값")
    void findByIdForUpdate() {
        memberRepositoryPort.save(Member.signUp(1L, "customer@example.com", HASH, "홍길동", null));
        entityManager.clear();

        assertThat(memberRepositoryPort.findByIdForUpdate(1L)).hasValueSatisfying(member -> assertThat(member.version()).isZero());
        entityManager.flush();
        entityManager.clear();
        assertThat(memberRepositoryPort.findById(1L)).hasValueSatisfying(member -> assertThat(member.version()).isZero());
        assertThat(memberRepositoryPort.findByIdForUpdate(2L)).isEmpty();
    }

    @Test
    @DisplayName("활성 운영자 전체 잠금 조회는 ID 오름차순이고 운영자 역할 전체(ADMIN · OWNER · MANAGER · STAFF)를 담되 고객·비활성 운영자를 제외한다")
    void activeOperatorLockOrder() {
        memberRepositoryPort.save(Member.admin(30L, "admin30@example.com", HASH, "관리자 30"));
        memberRepositoryPort.save(Member.admin(10L, "admin10@example.com", HASH, "관리자 10"));
        memberRepositoryPort.save(Member.restore(20L, "owner20@example.com", HASH, "대표 20", null, MemberRole.OWNER, null));
        memberRepositoryPort.save(Member.restore(25L, "manager25@example.com", HASH, "매니저 25", null, MemberRole.MANAGER, null));
        memberRepositoryPort.save(Member.restore(35L, "staff35@example.com", HASH, "스태프 35", null, MemberRole.STAFF, null));
        memberRepositoryPort.save(Member.admin(40L, "admin40@example.com", HASH, "관리자 40").deactivate(Instant.now()));
        memberRepositoryPort.save(
            Member.restore(45L, "manager45@example.com", HASH, "매니저 45", null, MemberRole.MANAGER, null).deactivate(Instant.now()));
        memberRepositoryPort.save(Member.signUp(5L, "customer5@example.com", HASH, "고객", null));
        entityManager.clear();

        assertThat(memberRepositoryPort.findActiveOperatorsForUpdate()).extracting(Member::id).containsExactly(10L, 20L, 25L, 30L, 35L);
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
    @DisplayName("어드민 회원 목록은 아이디 내림차순(최신순) 페이지이고 총계는 자르기 전 건수다 — 역할 조건 · 마지막 페이지 너머 (#6)")
    void findPage() {
        memberRepositoryPort.save(Member.signUp(1L, "c1@example.com", HASH, "고객1", "01012345678"));
        memberRepositoryPort.save(Member.admin(5L, "admin@example.com", HASH, "관리자"));
        memberRepositoryPort.save(Member.signUp(3L, "c3@example.com", HASH, "고객3", null));
        memberRepositoryPort.save(Member.signUp(4L, "c4@example.com", HASH, "고객4", null));
        memberRepositoryPort.save(Member.signUp(2L, "c2@example.com", HASH, "고객2", null));
        entityManager.clear();

        var first = memberRepositoryPort.findPage(null, 0, 2);
        assertThat(first.getContent()).extracting(row -> row.memberId()).containsExactly(5L, 4L);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(memberRepositoryPort.findPage(null, 2, 2).getContent()).extracting(row -> row.memberId()).containsExactly(1L);

        var customers = memberRepositoryPort.findPage(MemberRole.CUSTOMER, 0, 3);
        assertThat(customers.getContent()).extracting(row -> row.memberId()).containsExactly(4L, 3L, 2L);
        assertThat(customers.getTotalElements()).isEqualTo(4);
        assertThat(memberRepositoryPort.findPage(MemberRole.ADMIN, 0, 20).getContent()).singleElement().satisfies(row -> {
            assertThat(row.email()).isEqualTo("admin@example.com");
            assertThat(row.role()).isEqualTo(MemberRole.ADMIN);
            assertThat(row.phone()).isNull();
            assertThat(row.createdAt()).isNotNull();
        });

        var beyond = memberRepositoryPort.findPage(null, 9, 2);
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);
    }

    @Test
    @DisplayName("운영자 목록은 운영자 역할 전체를 아이디 내림차순으로 주고 고객을 빼며, active 로 활성 · 비활성을 거른다 (#236)")
    void findOperatorPage() {
        memberRepositoryPort.save(Member.signUp(1L, "c1@example.com", HASH, "고객1", null));
        memberRepositoryPort.save(Member.admin(2L, "admin2@example.com", HASH, "관리자"));
        memberRepositoryPort.save(Member.operator(3L, "owner3@example.com", HASH, "대표", MemberRole.OWNER));
        memberRepositoryPort.save(Member.operator(4L, "manager4@example.com", HASH, "매니저", MemberRole.MANAGER).deactivate(Instant.parse("2026-11-03T00:00:00Z")));
        memberRepositoryPort.save(Member.operator(5L, "staff5@example.com", HASH, "스태프", MemberRole.STAFF));
        memberRepositoryPort.save(Member.signUp(6L, "c6@example.com", HASH, "고객6", null));
        entityManager.clear();

        var all = memberRepositoryPort.findOperatorPage(null, 0, 3);
        assertThat(all.getContent()).extracting(row -> row.memberId()).containsExactly(5L, 4L, 3L);
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(memberRepositoryPort.findOperatorPage(null, 1, 3).getContent()).extracting(row -> row.memberId()).containsExactly(2L);

        assertThat(memberRepositoryPort.findOperatorPage(true, 0, 20).getContent()).extracting(row -> row.memberId()).containsExactly(5L, 3L, 2L);
        assertThat(memberRepositoryPort.findOperatorPage(false, 0, 20).getContent()).singleElement().satisfies(row -> {
            assertThat(row.memberId()).isEqualTo(4L);
            assertThat(row.role()).isEqualTo(MemberRole.MANAGER);
            assertThat(row.deactivatedAt()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
            assertThat(row.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("빈 목록은 쿼리 없이 끝난다")
    void appendNothing() {
        memberAgreementRepositoryPort.appendAll(List.of());

        assertThat(memberAgreementRepositoryPort.findLatest(1L, AgreementType.MARKETING)).isEmpty();
    }
}
