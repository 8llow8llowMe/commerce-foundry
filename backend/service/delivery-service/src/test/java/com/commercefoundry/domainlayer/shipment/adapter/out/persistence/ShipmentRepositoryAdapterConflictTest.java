package com.commercefoundry.domainlayer.shipment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.ShipmentRepository;
import com.commercefoundry.domainlayer.shipment.adapter.out.persistence.repository.ShipmentStatusHistoryRepository;
import com.commercefoundry.domainlayer.shipment.application.mapper.ShipmentMapperImpl;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 새 송장 저장의 무결성 위반 중 "같은 주문의 송장이 이미 있다" 만 낙관적 락 실패(→ DELIVERY_020)로 바꾸는지 본다. 실제 PostgreSQL 위반은 {@link ShipmentRepositoryAdapterTest} 가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentRepositoryAdapterConflictTest {

    private static final Shipment NEW_SHIPMENT = Shipment.register(10L, 20L, CarrierCode.CJ_LOGISTICS, "123", Instant.parse("2026-11-02T00:00:00Z"));

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private ShipmentStatusHistoryRepository shipmentStatusHistoryRepository;

    private ShipmentRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ShipmentRepositoryAdapter(shipmentRepository, shipmentStatusHistoryRepository, new ShipmentMapperImpl());
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("violation", new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_shipment_order_id", "shipment_pkey", "delivery.\"UK_SHIPMENT_ORDER_ID\""})
    @DisplayName("주문 유일 키 · 기본 키 위반은 낙관적 락 실패로 바꾼다 — 대소문자 · 스키마 접두사 · 따옴표는 무시한다")
    void duplicateIsConflict(String constraintName) {
        given(shipmentRepository.saveAndFlush(any())).willThrow(violation(constraintName));

        assertThatThrownBy(() -> adapter.insert(NEW_SHIPMENT)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /** delivery 는 JDBC 배치를 켠다(#255) — 한 건 저장도 executeBatch 로 나가 서버 예외가 BatchUpdateException 의 원인으로 이어진다. 로케일과 무관하게 서버 필드의 이름을 읽는다. */
    @Test
    @DisplayName("배치 예외 안의 한국어 서버 오류도 서버 필드의 제약 이름으로 충돌을 가린다")
    void batchLocalizedStillConflict() {
        PSQLException server = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0M중복된 키 값이 고유 제약 조건을 위반함\0nuk_shipment_order_id\0"));
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "23505", 0, new long[0], server);
        given(shipmentRepository.saveAndFlush(any()))
            .willThrow(new DataIntegrityViolationException("could not execute batch", new ConstraintViolationException("could not execute batch", batch, null)));

        assertThatThrownBy(() -> adapter.insert(NEW_SHIPMENT)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ck_shipment_synced_member", "shipment_status_history_pkey", "uk_shipment_order_id_old", "UK_SHIPMENT_ORDER_ID_INDEX_1"})
    @DisplayName("check 위반 · 이름이 다른 제약은 그대로 던진다 — 409 로 숨기지 않는다")
    void otherViolationIsRethrown(String constraintName) {
        DataIntegrityViolationException other = violation(constraintName);
        given(shipmentRepository.saveAndFlush(any())).willThrow(other);

        assertThatThrownBy(() -> adapter.insert(NEW_SHIPMENT)).isSameAs(other);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 위반(NOT NULL 등)도 그대로 던진다")
    void unnamedViolationIsRethrown() {
        DataIntegrityViolationException notNull = new DataIntegrityViolationException("not null", new SQLException("null value in column"));
        given(shipmentRepository.saveAndFlush(any())).willThrow(notNull);

        assertThatThrownBy(() -> adapter.insert(NEW_SHIPMENT)).isSameAs(notNull);
    }
}
