package com.commercefoundry.domainlayer.shipment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentRegisterResultAdminItem;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 송장 등록이 실제 빈 구성(Facade {@code NEVER} · Processor {@code REQUIRES_NEW} 프록시 · 어댑터 · JPQL)에서 단계마다 커밋되는지 본다. order 호출만 mock 이다.
 * DB 는 test 프로파일의 H2(PostgreSQL 모드)다 — 엔진에 기대는 유일 키 경합 판정은 {@code ShipmentRepositoryAdapterTest}(PostgreSQL)가 본다.
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — Facade 가 {@code NEVER} 라 트랜잭션 안에서 부르면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentRegisterFlowTest {

    private static final long ADMIN_ID = 9L;
    private static final long ORDER_ID = 4611686018427387904L;
    private static final long MEMBER_ID = 4611686018427387905L;

    @Autowired
    private ShipmentAdminUseCase shipmentAdminUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OrderShipmentCommandPort orderShipmentCommandPort;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery.shipment_status_history");
        jdbcTemplate.update("delete from delivery.shipment");
    }

    private ShipmentRegisterResultAdminItem register(String trackingNumber) {
        return shipmentAdminUseCase.register(new ShipmentRegisterCommand(
            List.of(new ShipmentRegisterCommand.Item(ORDER_ID, CarrierCode.LOTTE, trackingNumber)), ADMIN_ID)).results().get(0);
    }

    private void orderAccepts() {
        willReturn(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "SHIPPING", ShippingScope.INTERNATIONAL))
            .given(orderShipmentCommandPort).ship(eq(ORDER_ID), anyLong(), any());
    }

    private void orderRejects(Failure failure) {
        willThrow(new OrderShipmentException(failure, null)).given(orderShipmentCommandPort).ship(eq(ORDER_ID), anyLong(), any());
    }

    private Map<String, Object> shipmentRow() {
        return jdbcTemplate.queryForMap(
            "select id, tracking_number, order_synced, member_id, shipping_scope, shipped_at, version from delivery.shipment where order_id = ?", ORDER_ID);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from delivery." + table, Integer.class);
    }

    @Test
    @DisplayName("order 200 이면 송장 · 이력이 커밋되고 반영 완료 · 회원 · 범위가 저장된다(버전 1)")
    void accepted() {
        orderAccepts();

        ShipmentRegisterResultAdminItem result = register("L-1");

        Map<String, Object> row = shipmentRow();
        assertThat(result.result()).isEqualTo("REGISTERED");
        assertThat(result.deliveryId()).isEqualTo(String.valueOf(row.get("id")));
        assertThat(row).containsEntry("order_synced", true).containsEntry("member_id", MEMBER_ID)
            .containsEntry("shipping_scope", "INTERNATIONAL").containsEntry("version", 1L);
        assertThat(count("shipment_status_history")).isEqualTo(1);
    }

    @Test
    @DisplayName("order 404 면 커밋됐던 대기 송장 · 이력을 지운다")
    void notFoundDiscards() {
        orderRejects(Failure.NOT_FOUND);

        assertThat(register("L-1").errorCode()).isEqualTo("DELIVERY_014");
        assertThat(count("shipment")).isZero();
        assertThat(count("shipment_status_history")).isZero();
    }

    @Test
    @DisplayName("결과 모름이면 대기 송장이 남고, 같은 주문 재시도가 같은 송장 아이디로 번호를 바꿔 반영한다 — shipped_at 은 첫 값 = order 에 보낸 값")
    void unknownThenRetry() {
        orderRejects(Failure.UNKNOWN);
        ShipmentRegisterResultAdminItem first = register("L-1");
        Map<String, Object> pending = shipmentRow();
        orderAccepts();

        ShipmentRegisterResultAdminItem retried = register("L-2");

        Map<String, Object> synced = shipmentRow();
        assertThat(first.errorCode()).isEqualTo("DELIVERY_018");
        assertThat(pending).containsEntry("order_synced", false).containsEntry("version", 0L);
        assertThat(retried.result()).isEqualTo("REGISTERED");
        assertThat(synced).containsEntry("id", pending.get("id")).containsEntry("tracking_number", "L-2").containsEntry("order_synced", true)
            .containsEntry("shipped_at", pending.get("shipped_at"));
        assertThat(count("shipment_status_history")).isEqualTo(1);
        ArgumentCaptor<Instant> sentShippedAt = ArgumentCaptor.forClass(Instant.class);
        verify(orderShipmentCommandPort, times(2)).ship(eq(ORDER_ID), eq((Long) pending.get("id")), sentShippedAt.capture());
        assertThat(sentShippedAt.getAllValues()).as("재시도는 처음 출고 시각을 다시 보낸다").containsOnly(sentShippedAt.getAllValues().getFirst());
    }

    @Test
    @DisplayName("order 200 사이 다른 요청이 대기 송장을 지웠으면(반영 완료 저장이 행 없음 충돌) 같은 송장 아이디로 반영 완료 행 · 이력을 다시 만든다")
    void confirmAfterRowDeletedRestores() {
        willAnswer(invocation -> {
            jdbcTemplate.update("delete from delivery.shipment_status_history");
            jdbcTemplate.update("delete from delivery.shipment");
            return new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "SHIPPING", ShippingScope.INTERNATIONAL);
        }).given(orderShipmentCommandPort).ship(eq(ORDER_ID), anyLong(), any());

        ShipmentRegisterResultAdminItem result = register("L-1");

        Map<String, Object> row = shipmentRow();
        assertThat(result.result()).isEqualTo("REGISTERED");
        assertThat(result.deliveryId()).isEqualTo(String.valueOf(row.get("id")));
        assertThat(row).containsEntry("order_synced", true).containsEntry("member_id", MEMBER_ID).containsEntry("tracking_number", "L-1").containsEntry("version", 0L);
        assertThat(count("shipment_status_history")).isEqualTo(1);
    }

    @Test
    @DisplayName("반영된 송장은 같은 번호면 ALREADY_REGISTERED, 다른 번호면 DELIVERY_019 이고 행이 바뀌지 않는다")
    void syncedIsProtected() {
        orderAccepts();
        register("L-1");
        Map<String, Object> before = shipmentRow();

        assertThat(register("L-1").result()).isEqualTo("ALREADY_REGISTERED");
        assertThat(register("L-9").errorCode()).isEqualTo("DELIVERY_019");
        assertThat(shipmentRow()).isEqualTo(before);
    }
}
