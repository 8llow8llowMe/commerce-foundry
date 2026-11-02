package com.commercefoundry.domainlayer.shipment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.item.ShipmentSummaryAdminItem;
import com.commercefoundry.domainlayer.shipment.adapter.in.admin.dto.response.ShipmentAdminResponse;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentRegisterCommand;
import com.commercefoundry.domainlayer.shipment.application.command.ShipmentStatusChangeCommand;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException;
import com.commercefoundry.domainlayer.shipment.application.exception.OrderShipmentException.Failure;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentException;
import com.commercefoundry.domainlayer.shipment.application.model.ShipmentStatusFilter;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentAdminUseCase;
import com.commercefoundry.domainlayer.shipment.application.port.in.ShipmentWebUseCase;
import com.commercefoundry.domainlayer.shipment.application.port.out.OrderShipmentCommandPort;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.OrderShipmentConfirmation;
import com.commercefoundry.domainlayer.shipment.domain.model.ShipmentStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
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
 * 배송 상태 변경 · 조회(#18 PR-B2)가 실제 빈 구성(Facade {@code NEVER} · Processor {@code REQUIRES_NEW} · 읽기 전용 프록시 · 어댑터 · QueryDSL)에서 단계마다
 * 커밋되는지 본다. order 호출만 mock 이다. DB 는 test 프로파일의 H2(PostgreSQL 모드)다 — 엔진에 기대는 판정은 {@code ShipmentRepositoryAdapterTest}(PostgreSQL)가 본다.
 * 테스트 메서드는 트랜잭션 밖이다 — Facade 가 {@code NEVER} 라 트랜잭션 안에서 부르면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentStatusFlowTest {

    private static final long ADMIN_ID = 9L;
    private static final long ORDER_ID = 4611686018427387904L;
    private static final long MEMBER_ID = 4611686018427387905L;

    @Autowired
    private ShipmentAdminUseCase shipmentAdminUseCase;

    @Autowired
    private ShipmentWebUseCase shipmentWebUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OrderShipmentCommandPort orderShipmentCommandPort;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery.shipment_status_history");
        jdbcTemplate.update("delete from delivery.shipment");
    }

    /** 송장 등록(B1)으로 출고 반영된 송장을 만든다 — 아이디를 돌려준다. */
    private long registerSynced(long orderId) {
        willReturn(new OrderShipmentConfirmation(orderId, MEMBER_ID, "SHIPPING", ShippingScope.DOMESTIC))
            .given(orderShipmentCommandPort).ship(eq(orderId), anyLong(), any());
        String deliveryId = shipmentAdminUseCase.register(new ShipmentRegisterCommand(
            List.of(new ShipmentRegisterCommand.Item(orderId, CarrierCode.LOTTE, "L-" + orderId)), ADMIN_ID)).results().getFirst().deliveryId();
        return Long.parseLong(deliveryId);
    }

    private ShipmentAdminResponse change(long shipmentId, ShipmentStatus status) {
        return shipmentAdminUseCase.changeStatus(new ShipmentStatusChangeCommand(shipmentId, status, "수령 확인", ADMIN_ID));
    }

    /** H2 의 timestamptz 는 드라이버에 따라 {@link OffsetDateTime} · {@link Timestamp} 로 온다. */
    private static Instant instantOf(Object value) {
        return value instanceof OffsetDateTime offsetDateTime ? offsetDateTime.toInstant() : ((Timestamp) value).toInstant();
    }

    private Map<String, Object> shipmentRow(long shipmentId) {
        return jdbcTemplate.queryForMap("select status, completion_pending, previous_status, delivered_at, version from delivery.shipment where id = ?", shipmentId);
    }

    private List<Map<String, Object>> historyRows(long shipmentId) {
        return jdbcTemplate.queryForList(
            "select from_status, to_status, changed_by, changed_by_id, reason from delivery.shipment_status_history where shipment_id = ? order by id", shipmentId);
    }

    @Test
    @DisplayName("배송 완료 200 — 반영 대기 저장(tx1)과 반영 완료(tx2)가 커밋되고, 응답은 다시 읽은 상세(이력 포함)다")
    void deliveredCommits() {
        long shipmentId = registerSynced(ORDER_ID);
        willReturn(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "DELIVERED", ShippingScope.DOMESTIC))
            .given(orderShipmentCommandPort).completeDelivery(eq(ORDER_ID), eq(shipmentId), any());

        ShipmentAdminResponse response = change(shipmentId, ShipmentStatus.DELIVERED);

        Map<String, Object> row = shipmentRow(shipmentId);
        assertThat(row).containsEntry("status", "DELIVERED").containsEntry("completion_pending", false).containsEntry("version", 3L);
        ArgumentCaptor<Instant> sent = ArgumentCaptor.forClass(Instant.class);
        verify(orderShipmentCommandPort).completeDelivery(eq(ORDER_ID), eq(shipmentId), sent.capture());
        assertThat(instantOf(row.get("delivered_at"))).isEqualTo(sent.getValue());
        assertThat(response.histories()).hasSize(2);
        assertThat(response.histories().getLast().reason()).isEqualTo("수령 확인");
        assertThat(historyRows(shipmentId).getLast()).containsEntry("from_status", "SHIPPED").containsEntry("to_status", "DELIVERED")
            .containsEntry("changed_by", "ADMIN").containsEntry("changed_by_id", ADMIN_ID);
    }

    @Test
    @DisplayName("결과 모름이면 반영 대기가 커밋돼 남고, 재시도가 같은 배송 완료 시각을 다시 보내 반영한다")
    void unknownThenRetry() {
        long shipmentId = registerSynced(ORDER_ID);
        willThrow(new OrderShipmentException(Failure.UNKNOWN, null)).given(orderShipmentCommandPort).completeDelivery(eq(ORDER_ID), eq(shipmentId), any());

        assertThatThrownBy(() -> change(shipmentId, ShipmentStatus.DELIVERED)).isInstanceOfSatisfying(ShipmentException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ShipmentErrorCode.ORDER_SERVICE_UNAVAILABLE_ON_COMPLETION));
        Map<String, Object> pending = shipmentRow(shipmentId);
        willReturn(new OrderShipmentConfirmation(ORDER_ID, MEMBER_ID, "DELIVERED", null))
            .given(orderShipmentCommandPort).completeDelivery(eq(ORDER_ID), eq(shipmentId), any());

        change(shipmentId, ShipmentStatus.DELIVERED);

        assertThat(pending).containsEntry("completion_pending", true).containsEntry("previous_status", "SHIPPED");
        assertThat(shipmentRow(shipmentId)).containsEntry("completion_pending", false).containsEntry("previous_status", null)
            .containsEntry("delivered_at", pending.get("delivered_at"));
        ArgumentCaptor<Instant> sent = ArgumentCaptor.forClass(Instant.class);
        verify(orderShipmentCommandPort, times(2)).completeDelivery(eq(ORDER_ID), eq(shipmentId), sent.capture());
        assertThat(sent.getAllValues()).containsOnly(instantOf(pending.get("delivered_at")));
        assertThat(historyRows(shipmentId)).hasSize(2);
    }

    @Test
    @DisplayName("order 404 면 직전 상태로 되돌린 것이 커밋되고 되돌림 줄(SYSTEM)이 남는다")
    void notFoundReverts() {
        long shipmentId = registerSynced(ORDER_ID);
        change(shipmentId, ShipmentStatus.IN_TRANSIT);
        willThrow(new OrderShipmentException(Failure.NOT_FOUND, null)).given(orderShipmentCommandPort).completeDelivery(eq(ORDER_ID), eq(shipmentId), any());

        assertThatThrownBy(() -> change(shipmentId, ShipmentStatus.DELIVERED)).isInstanceOf(ShipmentException.class);

        assertThat(shipmentRow(shipmentId)).containsEntry("status", "IN_TRANSIT").containsEntry("completion_pending", false).containsEntry("previous_status", null)
            .containsEntry("delivered_at", null);
        assertThat(historyRows(shipmentId)).extracting(row -> row.get("to_status") + "/" + row.get("changed_by"))
            .containsExactly("SHIPPED/ADMIN", "IN_TRANSIT/ADMIN", "DELIVERED/ADMIN", "IN_TRANSIT/SYSTEM");
    }

    @Test
    @DisplayName("목록(QueryDSL) — 상태 · 반영 여부 · 주문 조건과 같은 조건의 건수, 최신순 페이지")
    void searchFilters() {
        long first = registerSynced(ORDER_ID);
        long second = registerSynced(ORDER_ID + 1);
        long third = registerSynced(ORDER_ID + 2);
        change(second, ShipmentStatus.IN_TRANSIT);
        willThrow(new OrderShipmentException(Failure.UNKNOWN, null)).given(orderShipmentCommandPort).completeDelivery(anyLong(), eq(third), any());
        assertThatThrownBy(() -> change(third, ShipmentStatus.DELIVERED)).isInstanceOf(ShipmentException.class);

        PageResponse<ShipmentSummaryAdminItem> all = shipmentAdminUseCase.search(null, null, null, 0, 2);
        PageResponse<ShipmentSummaryAdminItem> unsynced = shipmentAdminUseCase.search(null, null, false, 0, 20);
        PageResponse<ShipmentSummaryAdminItem> syncedShipped = shipmentAdminUseCase.search(null, ShipmentStatusFilter.SHIPPED, true, 0, 20);
        PageResponse<ShipmentSummaryAdminItem> byOrder = shipmentAdminUseCase.search(ORDER_ID + 1, null, null, 0, 20);

        assertThat(all.contents()).extracting(ShipmentSummaryAdminItem::deliveryId).containsExactly(String.valueOf(third), String.valueOf(second));
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.totalPages()).isEqualTo(2);
        assertThat(unsynced.contents()).extracting(ShipmentSummaryAdminItem::deliveryId).containsExactly(String.valueOf(third));
        assertThat(unsynced.contents().getFirst().completionPending()).isTrue();
        assertThat(syncedShipped.contents()).extracting(ShipmentSummaryAdminItem::deliveryId).containsExactly(String.valueOf(first));
        assertThat(byOrder.contents()).singleElement().extracting(item -> item.status().code()).isEqualTo("IN_TRANSIT");
        assertThat(shipmentAdminUseCase.search(null, null, null, 5, 20).contents()).isEmpty();
    }

    @Test
    @DisplayName("고객 조회 — 송장의 회원과 같으면 송장, 남의 회원 · 없는 주문 · 출고 반영 전이면 null")
    void customerView() {
        registerSynced(ORDER_ID);
        willThrow(new OrderShipmentException(Failure.UNKNOWN, null)).given(orderShipmentCommandPort).ship(eq(ORDER_ID + 1), anyLong(), any());
        shipmentAdminUseCase.register(new ShipmentRegisterCommand(List.of(new ShipmentRegisterCommand.Item(ORDER_ID + 1, CarrierCode.LOTTE, "L-2")), ADMIN_ID));

        assertThat(shipmentWebUseCase.getOrderShipment(ORDER_ID, MEMBER_ID).trackingNumber()).isEqualTo("L-" + ORDER_ID);
        assertThat(shipmentWebUseCase.getOrderShipment(ORDER_ID, MEMBER_ID + 1)).isNull();
        assertThat(shipmentWebUseCase.getOrderShipment(ORDER_ID + 9, MEMBER_ID)).isNull();
        assertThat(shipmentWebUseCase.getOrderShipment(ORDER_ID + 1, MEMBER_ID)).isNull();
    }
}
