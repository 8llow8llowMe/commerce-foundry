package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MarketingConsentInternalResponse;
import com.commercefoundry.domainlayer.member.application.port.in.MarketingConsentInternalUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.MarketingConsentQueryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.security.principal.MemberRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 같은 계약을 H2와 실제 PostgreSQL에서 실행한다. JDBC는 테스트 이력의 감사 시각을 고정하는 fixture에만 쓴다. */
@Transactional
abstract class MarketingConsentQueryContract {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    EntityManager entityManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MarketingConsentInternalUseCase useCase;

    @Autowired
    MarketingConsentQueryPort queryPort;

    @Test
    void laterTimeWinsEvenWithLowerIdAndOtherAgreementTypesDoNotAffectIt() {
        member(1, MemberRole.CUSTOMER, null);
        agreement(100, 1, true, NOW, AgreementType.MARKETING);
        agreement(90, 1, false, NOW.plusSeconds(1), AgreementType.MARKETING);
        agreement(110, 1, true, NOW.plusSeconds(2), AgreementType.TERMS_OF_SERVICE);
        member(2, MemberRole.CUSTOMER, null);
        agreement(200, 2, true, NOW, AgreementType.MARKETING);
        agreement(201, 2, false, NOW.plusSeconds(1), AgreementType.PRIVACY_POLICY);

        assertThat(query(1L, 2L).members()).extracting(item -> item.marketingAgreed()).containsExactly(false, true);
    }

    @Test
    void equalTimeUsesHighestIdForWithdrawalAndReconsent() {
        member(1, MemberRole.CUSTOMER, null);
        member(2, MemberRole.CUSTOMER, null);
        agreement(100, 1, true, NOW, AgreementType.MARKETING);
        agreement(101, 1, false, NOW, AgreementType.MARKETING);
        agreement(200, 2, false, NOW, AgreementType.MARKETING);
        agreement(201, 2, true, NOW, AgreementType.MARKETING);

        assertThat(query(1L, 2L).members()).extracting(item -> item.marketingAgreed()).containsExactly(false, true);
    }

    @Test
    void oneQueryPreservesOrderDeduplicatesAndFailsClosedWithoutDisclosingMemberDetails() {
        member(1, MemberRole.CUSTOMER, null);
        member(2, MemberRole.ADMIN, null);
        member(3, MemberRole.CUSTOMER, NOW);
        member(5, MemberRole.CUSTOMER, null);
        member(Long.MAX_VALUE, MemberRole.CUSTOMER, null);
        for (long id : List.of(1L, 2L, 3L, 4L, Long.MAX_VALUE)) {
            agreement(id == Long.MAX_VALUE ? 600 : 100 + id, id, true, NOW, AgreementType.MARKETING);
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.clear();

        var response = useCase.query(List.of(4L, 2L, 3L, 5L, 1L, 1L, Long.MAX_VALUE));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(response.members()).extracting(item -> item.memberId()).containsExactly("4", "2", "3", "5", "1", String.valueOf(Long.MAX_VALUE));
        assertThat(response.members()).extracting(item -> item.marketingAgreed()).containsExactly(false, false, false, false, true, true);
        assertThat(jdbc.queryForObject("select version from auth.member where id = 1", Long.class)).isZero();
    }

    @Test
    void oneHundredRequestedIdsStillUseOneSqlStatement() {
        List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
        statistics().clear();
        var response = useCase.query(ids);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(response.members()).hasSize(100).allMatch(item -> !item.marketingAgreed());
    }

    @Test
    void unrequestedCustomersAreNotReturnedByTheQueryPort() {
        member(1, MemberRole.CUSTOMER, null);
        member(9, MemberRole.CUSTOMER, null);
        agreement(100, 1, true, NOW, AgreementType.MARKETING);
        agreement(900, 9, true, NOW, AgreementType.MARKETING);
        entityManager.flush();
        entityManager.clear();

        assertThat(queryPort.findLatestAgreedCustomers(List.of(1L)).agreedMemberIds()).containsExactly(1L);
    }

    @Test
    void emptyAdapterInputDoesNotIssueAnInvalidInQuery() {
        statistics().clear();
        assertThat(queryPort.findLatestAgreedCustomers(List.of()).agreedMemberIds()).isEmpty();
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    private MarketingConsentInternalResponse query(Long... ids) {
        entityManager.flush();
        entityManager.clear();
        return useCase.query(List.of(ids));
    }

    private void member(long id, MemberRole role, Instant deactivatedAt) {
        entityManager.persist(MemberEntity.builder().id(id).email("fixture" + id + "@example.invalid").passwordHash("test-only")
            .name("fixture").role(role).deactivatedAt(deactivatedAt).build());
    }

    private void agreement(long id, long memberId, boolean agreed, Instant time, AgreementType type) {
        entityManager.persist(MemberAgreementEntity.builder().id(id).memberId(memberId).agreementType(type).agreed(agreed).termsVersion("test-v1").build());
        entityManager.flush();
        jdbc.update("update auth.member_agreement set created_at = ? where id = ?", Timestamp.from(time), id);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
