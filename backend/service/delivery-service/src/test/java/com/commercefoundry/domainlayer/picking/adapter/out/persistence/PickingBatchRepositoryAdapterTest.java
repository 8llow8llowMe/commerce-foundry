package com.commercefoundry.domainlayer.picking.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingBatchRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingCheckRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingItemRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingOrderRepository;
import com.commercefoundry.domainlayer.picking.application.mapper.PickingMapperImpl;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheckSubmission;
import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import com.commercefoundry.global.config.DeliveryServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 출고 묶음 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리 · 버전 비교 merge · 제외 주문 왕복 · check 제약은 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5). 스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=delivery",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, DeliveryServiceFeaturesConfig.class})
class PickingBatchRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    @Autowired
    private PickingBatchRepository pickingBatchRepository;

    @Autowired
    private PickingOrderRepository pickingOrderRepository;

    @Autowired
    private PickingItemRepository pickingItemRepository;

    @Autowired
    private PickingCheckRepository pickingCheckRepository;

    @Autowired
    private TestEntityManager entityManager;

    private PickingBatchRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PickingBatchRepositoryAdapter(pickingBatchRepository, pickingOrderRepository, pickingItemRepository, pickingCheckRepository,
            new PickingMapperImpl(), new SnowflakeIdGenerator(1, 1));
    }

    /** 대상 100(옵션 10 을 2 + 3 줄) · 200(옵션 10 · 20), 제외 300(해외) · 400(없음). */
    private PickingBatch created(long batchId) {
        PickingSnapshot first = new PickingSnapshot(100, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 2, false), new PickingSnapshot.Line(10, 3, false)));
        PickingSnapshot second = new PickingSnapshot(200, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 1, false), new PickingSnapshot.Line(20, 4, false)));
        PickingSnapshot overseas = new PickingSnapshot(300, "PREPARING", "US", first.lines());
        PickingSelection selection = PickingSelection.judge(List.of(100L, 200L, 300L, 400L), List.of(first, second, overseas));
        PickingBatch saved = adapter.insert(PickingBatch.create(batchId, selection));
        entityManager.clear();
        return saved;
    }

    @Test
    @DisplayName("묶음 · 대상 · 제외 사유 · 옵션별 수량을 저장하고 그대로 읽는다")
    void roundTrip() {
        PickingBatch saved = created(1);

        PickingBatch loaded = adapter.findById(1).orElseThrow();

        assertThat(saved.version()).isNotNull();
        assertThat(loaded.version()).isEqualTo(saved.version());
        assertThat(loaded.orders()).extracting(PickingSnapshot::orderId).containsExactly(100L, 200L);
        assertThat(loaded.orders().getFirst().quantities()).isEqualTo(Map.of(10L, 5L));
        assertThat(loaded.pickingQuantities()).isEqualTo(Map.of(10L, 6L, 20L, 4L));
        assertThat(loaded.exclusions()).containsExactly(
            new PickingExclusion(300, PickingExclusion.Reason.OVERSEAS_SHIPPING), new PickingExclusion(400, PickingExclusion.Reason.ORDER_NOT_FOUND));
        assertThat(pickingItemRepository.findAllByBatchIdOrderByOrderIdAscSkuIdAsc(1)).hasSize(3);
        assertThat(adapter.findById(999)).isEmpty();
    }

    @Test
    @DisplayName("검수 기록을 순번대로 더하고 버전을 올린다 — 실패 기록도 남는다")
    void addsChecks() {
        PickingBatch saved = created(2);

        PickingBatch failed = adapter.addCheck(adapter.findById(2).orElseThrow().check(100, Map.of(10L, 1L)));
        PickingBatch passed = adapter.addCheck(failed.check(100, Map.of(10L, 5L)));
        entityManager.clear();
        PickingBatch loaded = adapter.findById(2).orElseThrow();

        assertThat(loaded.version()).isEqualTo(passed.version()).isGreaterThan(saved.version());
        assertThat(loaded.checks()).extracting(PickingCheck::failure).containsExactly(PickingCheck.Failure.SHORTAGE, PickingCheck.Failure.NONE);
        assertThat(loaded.checked(100)).isTrue();
        assertThat(loaded.check(100, Map.of(10L, 5L))).as("같은 수량 재요청은 기록을 더하지 않는다").isSameAs(loaded);
        assertThat(pickingCheckRepository.findAllByBatchIdOrderByAttemptNoAsc(2)).extracting(check -> check.getAttemptNo()).containsExactly(1, 2);
        assertThat(pickingBatchRepository.findById(2L).orElseThrow().getCheckCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("미등록 바코드 검수 실패를 PostgreSQL에 저장하고 재조회한다")
    void persistsUnknownBarcodeFailure() {
        PickingBatch saved = created(4);
        var submission = new PickingCheckSubmission(Map.of(10L, 5L), Map.of("unregistered", 1L));
        adapter.addCheck(saved.checkSubmission(100, submission));
        entityManager.clear();

        PickingBatch loaded = adapter.findById(4).orElseThrow();
        assertThat(loaded.checks()).singleElement().satisfies(check -> {
            assertThat(check.failure()).isEqualTo(PickingCheck.Failure.UNKNOWN_BARCODE);
            assertThat(check.submittedSkuKey()).isEqualTo("10:5");
            assertThat(check.quantitiesKey()).doesNotContain("unregistered");
        });
        assertThat(loaded.checked(100)).isFalse();
        assertThat(loaded.checkSubmission(100, submission)).isSameAs(loaded);
    }

    @Test
    @DisplayName("같은 버전에서 갈라진 두 검수 중 뒤의 것은 버전 충돌이다 — 기록이 중복되거나 앞선 갱신이 사라지지 않는다")
    void concurrentChecksConflict() {
        created(3);
        PickingBatch readByA = adapter.findById(3).orElseThrow();
        PickingBatch readByB = adapter.findById(3).orElseThrow();

        adapter.addCheck(readByA.check(100, Map.of(10L, 5L)));
        entityManager.clear();

        assertThatThrownBy(() -> adapter.addCheck(readByB.check(200, Map.of(10L, 1L, 20L, 4L))))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(pickingCheckRepository.findAllByBatchIdOrderByAttemptNoAsc(3)).singleElement()
            .satisfies(check -> assertThat(check.getOrderId()).isEqualTo(100L));
    }
}
