package com.commercefoundry.domainlayer.onboarding.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.repository.OnboardingTourProgressRepository;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 투어 진행 저장소를 실제 PostgreSQL 에 질의해 본다 — 운영자 · 투어 유니크 키의 충돌 변환, 운영자 간 분리, 재실행 삭제, 버전 비교.
 * 스키마는 DDL 스냅숏 + {@code ddl-auto=validate} 다. Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 *
 * <p>유니크 키 위반은 PostgreSQL 이 트랜잭션을 중단시키므로 테스트 트랜잭션을 쓰지 않는다(각 저장이 자기 트랜잭션에서 커밋된다).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({OperationsServiceFeaturesConfig.class, OnboardingTourProgressRepositoryAdapter.class})
class OnboardingTourProgressRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    private static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");

    @Autowired
    private OnboardingTourProgressRepositoryAdapter adapter;

    @Autowired
    private OnboardingTourProgressRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("같은 운영자 · 투어의 첫 행을 또 만들면 유니크 키 위반이 ONBOARDING_021 로 바뀐다 — 다른 운영자는 같은 투어를 따로 가진다")
    void uniquePerMemberAndGuide() {
        repository.deleteAll();
        adapter.save(TourProgress.start(1L, 7L, "console", 1, TourStatus.IN_PROGRESS, 1, NOW));
        adapter.save(TourProgress.start(2L, 8L, "console", 1, TourStatus.COMPLETED, 5, NOW));

        assertThatThrownBy(() -> adapter.save(TourProgress.start(3L, 7L, "console", 1, TourStatus.SKIPPED, null, NOW)))
            .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.CONCURRENT_UPDATE));

        assertThat(adapter.findAllByMember(7L)).singleElement().satisfies(progress -> {
            assertThat(progress.status()).isEqualTo(TourStatus.IN_PROGRESS);
            assertThat(progress.currentStep()).isEqualTo(1);
        });
        assertThat(adapter.find(8L, "console").orElseThrow().status()).isEqualTo(TourStatus.COMPLETED);
    }

    @Test
    @DisplayName("읽은 뒤 다른 탭이 먼저 고친 옛 사본을 저장하면 ONBOARDING_021 이다")
    void staleCopyConflicts() {
        repository.deleteAll();
        TourProgress stored = adapter.save(TourProgress.start(11L, 7L, "stocks", 1, TourStatus.IN_PROGRESS, 0, NOW));
        adapter.save(stored.merge(1, TourStatus.IN_PROGRESS, 3, NOW));

        assertThatThrownBy(() -> adapter.save(stored.merge(1, TourStatus.COMPLETED, 2, NOW)))
            .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.CONCURRENT_UPDATE));
        assertThat(adapter.find(7L, "stocks").orElseThrow().currentStep()).isEqualTo(3);
    }

    @Test
    @DisplayName("재실행 삭제는 그 운영자의 그 투어 한 행만 지운다")
    void deleteTouchesOnlyThatRow() {
        repository.deleteAll();
        adapter.save(TourProgress.start(21L, 7L, "console", 1, TourStatus.COMPLETED, 5, NOW));
        adapter.save(TourProgress.start(22L, 7L, "stocks", 1, TourStatus.SKIPPED, null, NOW));
        adapter.save(TourProgress.start(23L, 8L, "console", 1, TourStatus.COMPLETED, 5, NOW));

        assertThat(delete(7L, "console")).isEqualTo(1);
        assertThat(delete(7L, "console")).isZero();

        assertThat(adapter.find(7L, "console")).isEmpty();
        assertThat(adapter.find(7L, "stocks")).isPresent();
        assertThat(adapter.find(8L, "console")).isPresent();
    }

    @Test
    @DisplayName("읽은 뒤 다른 탭이 재실행으로 지운 행의 옛 사본을 합쳐 저장하면 ONBOARDING_021 이고 행이 되살아나지 않는다")
    void staleCopyAfterDeleteConflicts() {
        repository.deleteAll();
        TourProgress stored = adapter.save(TourProgress.start(31L, 7L, "console", 1, TourStatus.IN_PROGRESS, 0, NOW));
        assertThat(delete(7L, "console")).isEqualTo(1);

        assertThatThrownBy(() -> adapter.save(stored.merge(1, TourStatus.COMPLETED, 4, NOW)))
            .isInstanceOfSatisfying(OnboardingException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.CONCURRENT_UPDATE));
        assertThat(adapter.find(7L, "console")).isEmpty();
    }

    /** 삭제 JPQL 은 트랜잭션이 필요하다 — 운영에서는 Processor 트랜잭션 안이다. */
    private int delete(long memberId, String guideKey) {
        Integer deleted = new TransactionTemplate(transactionManager).execute(status -> adapter.delete(memberId, guideKey));
        return deleted == null ? 0 : deleted;
    }
}
