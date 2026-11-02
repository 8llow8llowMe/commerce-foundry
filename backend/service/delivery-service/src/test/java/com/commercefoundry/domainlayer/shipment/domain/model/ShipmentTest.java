package com.commercefoundry.domainlayer.shipment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShipmentTest {

    private static final Instant NOW = Instant.parse("2026-11-02T09:30:00.123456789Z");
    private static final Instant TRUNCATED = Instant.parse("2026-11-02T09:30:00.123456Z");

    private static Shipment pending() {
        return Shipment.register(10L, 20L, CarrierCode.CJ_LOGISTICS, "123456789012", NOW);
    }

    private static Shipment saved(Shipment shipment, long version) {
        return new Shipment(
            shipment.id(), shipment.orderId(), shipment.memberId(), shipment.carrier(), shipment.trackingNumber(), shipment.status(),
            shipment.shippingScope(), shipment.orderSynced(), shipment.shippedAt(), shipment.deliveredAt(), version
        );
    }

    @Nested
    @DisplayName("등록")
    class Register {

        @Test
        @DisplayName("출고 완료 · 주문 반영 전 · 회원 · 범위 없음 · 버전 없음으로 생기고, 출고 시각은 마이크로초로 자른다")
        void registers() {
            Shipment shipment = pending();

            assertThat(shipment.status()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(shipment.orderSynced()).isFalse();
            assertThat(shipment.memberId()).isNull();
            assertThat(shipment.shippingScope()).isNull();
            assertThat(shipment.version()).isNull();
            assertThat(shipment.deliveredAt()).isNull();
            assertThat(shipment.shippedAt()).isEqualTo(TRUNCATED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "12 34", "123_456", "한글123", "1234567890123456789012345678901234567890123456789012"})
        @DisplayName("송장 번호가 영문 · 숫자 · 하이픈 1~50자가 아니면 거절한다 — 원문을 메시지에 싣지 않는다")
        void rejectsTrackingNumber(String trackingNumber) {
            assertThatThrownBy(() -> Shipment.register(10L, 20L, CarrierCode.CJ_LOGISTICS, trackingNumber, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(exception -> {
                    if (!trackingNumber.isEmpty()) {
                        assertThat(exception.getMessage()).doesNotContain(trackingNumber);
                    }
                });
        }

        @Test
        @DisplayName("송장 번호는 50자 · 하이픈 · 영문 대소문자를 받는다")
        void acceptsTrackingNumber() {
            assertThat(Shipment.register(10L, 20L, CarrierCode.EMS, "EE123456789KR", NOW).trackingNumber()).isEqualTo("EE123456789KR");
            assertThat(Shipment.register(10L, 20L, CarrierCode.OTHER, "a-B-1", NOW).trackingNumber()).isEqualTo("a-B-1");
            assertThat(Shipment.register(10L, 20L, CarrierCode.OTHER, "1".repeat(50), NOW).trackingNumber()).hasSize(50);
        }

        @Test
        @DisplayName("주문에 반영된 송장은 회원 아이디가 있어야 하고, 배송 완료 시각은 배송 완료 상태에만 있다")
        void invariants() {
            assertThatThrownBy(() -> new Shipment(1L, 2L, null, CarrierCode.DHL, "1", ShipmentStatus.SHIPPED, null, true, NOW, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Shipment(1L, 2L, 3L, CarrierCode.DHL, "1", ShipmentStatus.SHIPPED, null, true, NOW, NOW, 0L))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Shipment(1L, 2L, 3L, CarrierCode.DHL, "1", ShipmentStatus.DELIVERED, null, true, NOW, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(new Shipment(1L, 2L, 3L, CarrierCode.DHL, "1", ShipmentStatus.DELIVERED, null, true, NOW, NOW, 0L).deliveredAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("전이")
    class Transitions {

        @Test
        @DisplayName("반영 전 송장의 재시도는 택배사 · 송장 번호만 바꾸고 아이디 · 버전 · 상태 · 출고 시각(order 에 처음 보낸 값)은 그대로다")
        void retargetsPending() {
            Shipment current = saved(pending(), 3L);

            Shipment retargeted = current.retarget(CarrierCode.HANJIN, "999");

            assertThat(retargeted.id()).isEqualTo(current.id());
            assertThat(retargeted.version()).isEqualTo(3L);
            assertThat(retargeted.status()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(retargeted.carrier()).isEqualTo(CarrierCode.HANJIN);
            assertThat(retargeted.trackingNumber()).isEqualTo("999");
            assertThat(retargeted.shippedAt()).as("재시도해도 출고 시각은 첫 값 = order 에 보낸 값").isEqualTo(TRUNCATED).isEqualTo(current.shippedAt());
            assertThat(retargeted.orderSynced()).isFalse();
        }

        @Test
        @DisplayName("반영 완료는 회원 · 범위를 스냅숏하고 나머지는 그대로다")
        void marksSynced() {
            Shipment current = saved(pending(), 1L);

            Shipment synced = current.markOrderSynced(30L, ShippingScope.INTERNATIONAL);

            assertThat(synced.orderSynced()).isTrue();
            assertThat(synced.memberId()).isEqualTo(30L);
            assertThat(synced.shippingScope()).isEqualTo(ShippingScope.INTERNATIONAL);
            assertThat(synced.trackingNumber()).isEqualTo(current.trackingNumber());
            assertThat(synced.version()).isEqualTo(1L);
            assertThat(current.markOrderSynced(30L, null).shippingScope()).isNull();
        }

        @Test
        @DisplayName("반영된 송장은 다시 반영하거나 다른 송장으로 바꾸지 않는다")
        void syncedIsFrozen() {
            Shipment synced = saved(pending(), 1L).markOrderSynced(30L, ShippingScope.DOMESTIC);

            assertThatThrownBy(() -> synced.retarget(CarrierCode.HANJIN, "999")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> synced.markOrderSynced(30L, ShippingScope.DOMESTIC)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("같은 송장인지는 택배사와 송장 번호(대소문자 포함)가 모두 같아야 한다")
        void sameTracking() {
            Shipment shipment = Shipment.register(10L, 20L, CarrierCode.EMS, "EE1KR", NOW);

            assertThat(shipment.hasTracking(CarrierCode.EMS, "EE1KR")).isTrue();
            assertThat(shipment.hasTracking(CarrierCode.EMS, "ee1kr")).isFalse();
            assertThat(shipment.hasTracking(CarrierCode.DHL, "EE1KR")).isFalse();
        }
    }

    @Nested
    @DisplayName("상태 이력 · 주문 반영 결과")
    class HistoryAndConfirmation {

        @Test
        @DisplayName("등록 줄은 없음 → 출고 완료, 관리자다. 관리자 줄은 관리자 아이디가 있어야 한다")
        void registeredHistory() {
            ShipmentStatusHistory history = ShipmentStatusHistory.registered(1L, 2L, 9L);

            assertThat(history.fromStatus()).isNull();
            assertThat(history.toStatus()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(history.changedBy()).isEqualTo(ShipmentChangedBy.ADMIN);
            assertThat(history.changedById()).isEqualTo(9L);
            assertThatThrownBy(() -> new ShipmentStatusHistory(1L, 2L, null, ShipmentStatus.SHIPPED, ShipmentChangedBy.ADMIN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(new ShipmentStatusHistory(1L, 2L, ShipmentStatus.SHIPPED, ShipmentStatus.IN_TRANSIT, ShipmentChangedBy.SYSTEM, null, null).changedById()).isNull();
        }

        @Test
        @DisplayName("주문 상태가 배송 중이 아니면(배송 완료 · 구매 확정 등) 이미 지나간 주문의 멱등 응답이다")
        void pastShipping() {
            assertThat(new OrderShipmentConfirmation(1L, 2L, "SHIPPING", ShippingScope.DOMESTIC).isPastShipping()).isFalse();
            assertThat(new OrderShipmentConfirmation(1L, 2L, "PURCHASE_CONFIRMED", null).isPastShipping()).isTrue();
        }
    }
}
