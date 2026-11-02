package com.commercefoundry.domainlayer.shipment.adapter.in.web.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.shipment.adapter.in.web.dto.response.ShipmentResponse;
import com.commercefoundry.domainlayer.shipment.domain.model.CarrierCode;
import com.commercefoundry.domainlayer.shipment.domain.model.Shipment;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 고객 배송 응답 (#18 PR-B2) — 배송 완료 반영 대기는 배송 중 · 배송 완료 시각 없음으로 보인다({@code order-flow-guide.md} §4 "고객 노출"). */
class ShipmentWebPresenterTest {

    private static final Instant SHIPPED_AT = Instant.parse("2026-11-02T09:30:00Z");
    private static final Instant DELIVERED_AT = Instant.parse("2026-11-04T02:10:00Z");

    private final ShipmentWebPresenter presenter = new ShipmentWebPresenter();

    private static Shipment synced() {
        return Shipment.register(100L, 20L, CarrierCode.LOTTE, "L-1", SHIPPED_AT).markOrderSynced(30L, ShippingScope.DOMESTIC);
    }

    @Test
    @DisplayName("배송 완료가 주문에 반영되기 전(반영 대기)이면 배송 중 · 배송 완료 시각 null 이다 — 주문이 아직 배송 중이라 반품 · 구매 확정을 할 수 없다")
    void pendingCompletionLooksInTransit() {
        ShipmentResponse response = presenter.toResponse(synced().deliver(DELIVERED_AT));

        assertThat(response.status().code()).isEqualTo("IN_TRANSIT");
        assertThat(response.deliveredAt()).isNull();
        assertThat(response.shippedAt()).isEqualTo(SHIPPED_AT);
    }

    @Test
    @DisplayName("반영된 배송 완료는 배송 완료 · 배송 완료 시각 그대로이고, 아이디는 문자열이다")
    void syncedCompletionIsDelivered() {
        ShipmentResponse response = presenter.toResponse(synced().deliver(DELIVERED_AT).markCompletionSynced());

        assertThat(response.status().code()).isEqualTo("DELIVERED");
        assertThat(response.deliveredAt()).isEqualTo(DELIVERED_AT);
        assertThat(response.deliveryId()).isEqualTo("100");
        assertThat(response.orderId()).isEqualTo("20");
    }

    @Test
    @DisplayName("출고 완료 · 배송 중은 송장 상태 그대로다")
    void otherStatusesAsIs() {
        assertThat(presenter.toResponse(synced()).status().code()).isEqualTo("SHIPPED");
        assertThat(presenter.toResponse(synced().startTransit()).status().code()).isEqualTo("IN_TRANSIT");
    }
}
