package com.commercefoundry.domainlayer.shipment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.entity.ShipmentEntity;
import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.ShipmentRepository;
import com.commercefoundry.domainlayer.shipment.application.mapper.ShipmentMapperImpl;
import com.commercefoundry.domainlayer.shipment.application.port.out.ShipmentRepositoryPort;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatusHistory;
import com.commercefoundry.global.config.DeliveryServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.shared.commerce.ShippingScope;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 송장 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 유일 키 경합 판정 · 버전 비교 merge · JPQL 조건부 DELETE · check 제약은 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5). 스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 *
 * <p>PostgreSQL 은 오류가 난 트랜잭션에서 다음 문장을 받지 않는다 — 제약 위반을 보는 테스트는 그 위반을 마지막 문장으로 둔다.
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
@Import({QuerydslConfigurer.class, DeliveryServiceFeaturesConfig.class, ShipmentRepositoryAdapter.class, ShipmentMapperImpl.class})
class ShipmentRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/delivery-schema.sql");

    private static final Instant SHIPPED_AT = Instant.parse("2026-11-02T09:30:00.123456Z");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ShipmentRepositoryPort shipmentRepositoryPort;

    @Autowired
    private ShipmentRepository shipmentRepository;

    private static Shipment newShipment(long id, long orderId) {
        return Shipment.register(id, orderId, CarrierCode.CJ_LOGISTICS, "123456789012", SHIPPED_AT);
    }

    private long historyCount(long shipmentId) {
        return entityManager.getEntityManager()
            .createQuery("select count(h) from ShipmentStatusHistoryEntity h where h.shipmentId = :shipmentId", Long.class)
            .setParameter("shipmentId", shipmentId)
            .getSingleResult();
    }

    @Test
    @DisplayName("INSERT 하면 버전 0 으로 저장되고 주문 아이디로 그대로 읽힌다(마이크로초 시각 포함)")
    void insertsAndReads() {
        Shipment saved = shipmentRepositoryPort.insert(newShipment(1L, 100L));
        entityManager.clear();

        Shipment found = shipmentRepositoryPort.findByOrderId(100L).orElseThrow();

        assertThat(saved.version()).isZero();
        assertThat(found).isEqualTo(saved);
        assertThat(found.shippedAt()).isEqualTo(SHIPPED_AT);
        assertThat(shipmentRepositoryPort.findByOrderId(999L)).isEmpty();
    }

    @Test
    @DisplayName("반영 완료로 바꾸면 버전이 오르고, 옛 버전으로 다시 저장하면 낙관적 락 실패다")
    void updatesWithVersion() {
        Shipment saved = shipmentRepositoryPort.insert(newShipment(1L, 100L));

        Shipment synced = shipmentRepositoryPort.update(saved.markOrderSynced(500L, ShippingScope.DOMESTIC));
        entityManager.clear();

        assertThat(synced.version()).isEqualTo(1L);
        assertThat(shipmentRepositoryPort.findByOrderId(100L).orElseThrow())
            .extracting(Shipment::orderSynced, Shipment::memberId, Shipment::shippingScope)
            .containsExactly(true, 500L, ShippingScope.DOMESTIC);
        assertThatThrownBy(() -> shipmentRepositoryPort.update(saved.retarget(CarrierCode.HANJIN, "999")))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("대기 송장 삭제는 버전 · 반영 여부가 그대로일 때만 송장과 그 이력을 지운다 — 다른 송장의 이력은 남는다")
    void deletesPendingOnlyWhenUnchanged() {
        Shipment pending = shipmentRepositoryPort.insert(newShipment(1L, 100L));
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.registered(11L, pending.id(), 9L));
        Shipment synced = shipmentRepositoryPort.update(shipmentRepositoryPort.insert(newShipment(2L, 200L)).markOrderSynced(500L, null));
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.registered(12L, synced.id(), 9L));

        assertThat(shipmentRepositoryPort.deletePending(pending.id(), pending.version() + 1)).isFalse();
        assertThat(shipmentRepositoryPort.deletePending(synced.id(), synced.version())).isFalse();
        assertThat(shipmentRepositoryPort.deletePending(pending.id(), pending.version())).isTrue();

        assertThat(shipmentRepositoryPort.findByOrderId(100L)).isEmpty();
        assertThat(historyCount(pending.id())).isZero();
        assertThat(shipmentRepositoryPort.findByOrderId(200L)).isPresent();
        assertThat(historyCount(synced.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 주문의 두 번째 INSERT 는 유일 키 위반을 낙관적 락 실패로 알린다")
    void duplicateOrderIsConflict() {
        shipmentRepositoryPort.insert(newShipment(1L, 100L));

        assertThatThrownBy(() -> shipmentRepositoryPort.insert(newShipment(2L, 100L))).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("주문에 반영된 송장에 회원이 없으면 check 제약(ck_shipment_synced_member)이 막고, 충돌로 바꾸지 않는다")
    void syncedWithoutMemberIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(3L).orderId(300L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.SHIPPED)
            .orderSynced(true).shippedAt(SHIPPED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_synced_member"));
    }

    @Test
    @DisplayName("배송 완료 상태인데 배송 완료 시각이 없으면 check 제약(ck_shipment_delivered_at_status)이 막는다")
    void deliveredWithoutDeliveredAtIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(4L).orderId(400L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.DELIVERED)
            .orderSynced(true).memberId(500L).shippedAt(SHIPPED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_delivered_at_status"));
    }

    @Test
    @DisplayName("배송 완료가 아닌데 배송 완료 시각이 있으면 check 제약(ck_shipment_delivered_at_status)이 막는다")
    void shippedWithDeliveredAtIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(5L).orderId(500L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.SHIPPED)
            .orderSynced(true).memberId(500L).shippedAt(SHIPPED_AT).deliveredAt(SHIPPED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_delivered_at_status"));
    }

    @Test
    @DisplayName("대기 송장을 지운 뒤 옛 버전으로 저장(merge)하면 낙관적 락 실패다 — INSERT 로 되살아나지 않는다")
    void updateAfterDeleteIsConflict() {
        Shipment pending = shipmentRepositoryPort.insert(newShipment(1L, 100L));
        assertThat(shipmentRepositoryPort.deletePending(pending.id(), pending.version())).isTrue();

        assertThatThrownBy(() -> shipmentRepositoryPort.update(pending.markOrderSynced(500L, ShippingScope.DOMESTIC)))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        entityManager.clear();
        assertThat(shipmentRepositoryPort.findByOrderId(100L)).isEmpty();
    }

    // #18 PR-B2 — 목록(QueryDSL) · 이력 조회 · 새 check 제약

    private static final Instant DELIVERED_AT = Instant.parse("2026-11-04T02:10:00.654321Z");

    private Shipment synced(long id, long orderId) {
        return shipmentRepositoryPort.update(shipmentRepositoryPort.insert(newShipment(id, orderId)).markOrderSynced(500L, ShippingScope.DOMESTIC));
    }

    @Test
    @DisplayName("목록 — 상태 · 반영 여부(출고 반영 전 OR 배송 완료 반영 대기) · 주문 조건, 최신순 페이지, 건수는 같은 조건이다")
    void searchesWithFilters() {
        shipmentRepositoryPort.insert(newShipment(1L, 100L));
        synced(2L, 200L);
        shipmentRepositoryPort.update(synced(3L, 300L).deliver(DELIVERED_AT));
        Shipment delivered = shipmentRepositoryPort.update(synced(4L, 400L).deliver(DELIVERED_AT));
        shipmentRepositoryPort.update(delivered.markCompletionSynced());
        entityManager.clear();

        Page<Shipment> unsynced = shipmentRepositoryPort.search(null, null, false, 0, 20);
        Page<Shipment> syncedPage = shipmentRepositoryPort.search(null, null, true, 0, 1);
        Page<Shipment> deliveredUnsynced = shipmentRepositoryPort.search(null, ShipmentStatus.DELIVERED, false, 0, 20);

        assertThat(unsynced.getContent()).extracting(Shipment::id).containsExactly(3L, 1L);
        assertThat(unsynced.getTotalElements()).isEqualTo(2);
        assertThat(syncedPage.getContent()).extracting(Shipment::id).containsExactly(4L);
        assertThat(syncedPage.getTotalElements()).isEqualTo(2);
        assertThat(deliveredUnsynced.getContent()).extracting(Shipment::id).containsExactly(3L);
        assertThat(shipmentRepositoryPort.search(200L, null, null, 0, 20).getContent()).extracting(Shipment::id).containsExactly(2L);
        assertThat(shipmentRepositoryPort.search(null, null, null, 3, 20).getContent()).isEmpty();
        assertThat(shipmentRepositoryPort.search(null, null, null, 3, 20).getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("이력은 아이디 오름차순이고 사유 · 바뀐 시각(생성 시각)이 읽힌다. 배송 완료 반영 대기 · 직전 상태 · 시각이 그대로 저장된다")
    void readsHistoriesAndCompletion() {
        Shipment shipment = shipmentRepositoryPort.update(synced(1L, 100L).deliver(DELIVERED_AT));
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.changedByAdmin(12L, 1L, ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED, 9L, "수령 확인"));
        shipmentRepositoryPort.insertHistory(ShipmentStatusHistory.registered(11L, 1L, 9L));
        entityManager.clear();

        assertThat(shipmentRepositoryPort.findById(1L).orElseThrow())
            .extracting(Shipment::completionPending, Shipment::previousStatus, Shipment::deliveredAt, Shipment::version)
            .containsExactly(true, ShipmentStatus.SHIPPED, DELIVERED_AT, shipment.version());
        assertThat(shipmentRepositoryPort.findHistories(1L)).extracting(ShipmentStatusHistory::id, ShipmentStatusHistory::reason)
            .containsExactly(tuple(11L, null), tuple(12L, "수령 확인"));
        assertThat(shipmentRepositoryPort.findHistories(1L)).allSatisfy(history -> assertThat(history.changedAt()).isNotNull());
    }

    @Test
    @DisplayName("출고 반영 전인데 배송 중이면 check 제약(ck_shipment_unsynced_shipped)이 막는다")
    void unsyncedInTransitIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(6L).orderId(600L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.IN_TRANSIT).shippedAt(SHIPPED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_unsynced_shipped"));
    }

    @Test
    @DisplayName("배송 완료가 아닌데 반영 대기면 check 제약(ck_shipment_completion_pending_status)이 막는다")
    void pendingWithoutDeliveredIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(7L).orderId(700L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.SHIPPED)
            .orderSynced(true).memberId(500L).completionPending(true).previousStatus(ShipmentStatus.SHIPPED).shippedAt(SHIPPED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_completion_pending_status"));
    }

    @Test
    @DisplayName("반영 대기인데 직전 상태가 없으면 check 제약(ck_shipment_previous_status_pending)이 막는다")
    void pendingWithoutPreviousStatusIsRejected() {
        ShipmentEntity invalid = ShipmentEntity.builder()
            .id(8L).orderId(800L).carrier(CarrierCode.DHL).trackingNumber("1").status(ShipmentStatus.DELIVERED)
            .orderSynced(true).memberId(500L).completionPending(true).shippedAt(SHIPPED_AT).deliveredAt(DELIVERED_AT)
            .build();

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(invalid))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("ck_shipment_previous_status_pending"));
    }
}
