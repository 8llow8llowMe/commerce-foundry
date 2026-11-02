package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 송장(배송) — 주문당 1건 ({@code order-flow-guide.md} §4). 상태는 도메인 메서드로만 바꾼다.
 *
 * <p>{@code orderSynced} 는 <b>출고</b>의 주문 상태 반영(order-service {@code Order#ship}) 여부다. 등록하면 false 로 생기고, order 가 200 으로 답한 뒤에만 true 가 된다.
 * <b>true 가 된 송장은 지우거나 다른 송장 번호로 바꾸지 않는다</b> — order 는 송장 아이디를 저장하지 않아 다른 송장의 재등록을 구분하지 못한다(§4 "주문당 송장 1건").
 * 반영 전(false) 송장만 같은 등록 요청의 재시도가 택배사 · 송장 번호를 이번 값으로 바꿀 수 있다({@link #retarget}). 반영 전 송장은 언제나 출고 완료 상태다 —
 * 배송 중 · 배송 완료로 바꾸지 않는다(먼저 출고 재시도로 반영한다).
 *
 * <p>{@code completionPending} 은 <b>배송 완료</b>의 주문 반영 대기다(#18 PR-B2). 배송 완료로 바꾸면 true 로 저장하고 order 에 배송 완료를 보낸 뒤, 200 이면 false 로 내린다.
 * 결과를 모르면(409 {@code ORDER_025} · 타임아웃 · 5xx) true 로 남고 같은 변경 요청 재시도가 처음 배송 완료 시각을 다시 보내 수렴한다. 출고 반영 플래그와 나눈 이유는
 * {@code order-flow-guide.md} §4 "배송 완료" — 출고 반영 전 송장의 재시도 · 삭제 규칙(B1)이 배송 완료 송장에 걸리지 않게.
 *
 * @param memberId 주문한 회원 — order 응답의 스냅숏. 반영 전에는 null
 * @param shippingScope 배송 범위 — order 응답의 배송 국가로 정한다. 반영 전이거나 국가 코드를 읽지 못했으면 null
 * @param completionPending 배송 완료를 order 에 반영하기를 기다린다. 배송 완료 송장에만 true 일 수 있다
 * @param previousStatus 배송 완료 직전 상태(출고 완료 · 배송 중) — 반영 대기 중에만 있고, order 가 거절하면 이 상태로 되돌린다. 대기가 아니면 null
 * @param shippedAt 출고 시각 — 처음 등록할 때 정해 order 에 보낸 값 그대로다(마이크로초까지, order 의 {@code shipped_at} 과 같다). 재시도해도 바뀌지 않는다
 * @param deliveredAt 배송 완료 시각. {@link ShipmentStatus#DELIVERED} 일 때만 있다 — 처음 배송 완료로 바꾼 시각(마이크로초)이고 재시도해도 바뀌지 않는다
 * @param version 낙관적 락 버전. 아직 저장하지 않은 송장이면 null
 */
public record Shipment(
    long id, long orderId, Long memberId, CarrierCode carrier, String trackingNumber, ShipmentStatus status,
    ShippingScope shippingScope, boolean orderSynced, boolean completionPending, ShipmentStatus previousStatus,
    Instant shippedAt, Instant deliveredAt, Long version
) {

    /** 송장 번호 최대 길이 — 국내 택배 · 국제 특송 번호를 모두 담는다. */
    public static final int TRACKING_NUMBER_MAX_LENGTH = 50;

    /** 송장 번호 형식 — 영문 · 숫자 · 하이픈. 공백 · 제어 문자 · 서식 문자는 받지 않는다(화면 · 택배사 조회 키로 그대로 쓴다). */
    public static final String TRACKING_NUMBER_PATTERN = "[A-Za-z0-9-]{1," + TRACKING_NUMBER_MAX_LENGTH + "}";

    private static final Pattern TRACKING_NUMBER_FORMAT = Pattern.compile(TRACKING_NUMBER_PATTERN);

    public Shipment {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(trackingNumber, "trackingNumber");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(shippedAt, "shippedAt");
        if (id <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("송장 · 주문 아이디는 양수여야 합니다: id=%d, orderId=%d".formatted(id, orderId));
        }
        if (!TRACKING_NUMBER_FORMAT.matcher(trackingNumber).matches()) {
            // 원문은 싣지 않는다 — 형식이 깨진 값이 로그에 그대로 남지 않게
            throw new IllegalArgumentException("송장 번호 형식이 아닙니다: 길이 " + trackingNumber.length());
        }
        if (memberId != null && memberId <= 0) {
            throw new IllegalArgumentException("회원 아이디는 양수여야 합니다: " + memberId);
        }
        if (orderSynced && memberId == null) {
            throw new IllegalArgumentException("주문에 반영된 송장은 회원 아이디가 있어야 합니다: id=" + id);
        }
        if (!orderSynced && status != ShipmentStatus.SHIPPED) {
            throw new IllegalArgumentException("출고가 주문에 반영되지 않은 송장은 출고 완료 상태입니다: status=%s".formatted(status));
        }
        if ((status == ShipmentStatus.DELIVERED) != (deliveredAt != null)) {
            throw new IllegalArgumentException("배송 완료 시각은 배송 완료 상태에만 있습니다: status=%s".formatted(status));
        }
        if (completionPending && status != ShipmentStatus.DELIVERED) {
            throw new IllegalArgumentException("배송 완료 반영 대기는 배송 완료 상태에만 있습니다: status=%s".formatted(status));
        }
        if (completionPending != (previousStatus != null)) {
            throw new IllegalArgumentException("배송 완료 직전 상태는 반영 대기에만 있습니다: previousStatus=%s".formatted(previousStatus));
        }
        if (previousStatus != null && !previousStatus.canTransitTo(ShipmentStatus.DELIVERED)) {
            throw new IllegalArgumentException("배송 완료 직전 상태가 아닙니다: " + previousStatus);
        }
    }

    /**
     * 새 송장 — 출고 완료 · 주문 반영 전. 출고 시각은 마이크로초로 자른다 — order 의 {@code shipped_at}(timestamptz)과 같은 값을 들고 있게.
     */
    public static Shipment register(long id, long orderId, CarrierCode carrier, String trackingNumber, Instant shippedAt) {
        return new Shipment(
            id, orderId, null, carrier, trackingNumber, ShipmentStatus.SHIPPED,
            null, false, false, null, shippedAt.truncatedTo(ChronoUnit.MICROS), null, null
        );
    }

    /** 같은 택배사 · 같은 송장 번호인가 — 반영된 송장의 재등록을 "이미 등록됨" 으로 볼지 가른다. 송장 번호는 대소문자까지 같아야 한다. */
    public boolean hasTracking(CarrierCode otherCarrier, String otherTrackingNumber) {
        return carrier == otherCarrier && trackingNumber.equals(otherTrackingNumber);
    }

    /**
     * 주문 반영 전 송장의 재시도 — 택배사 · 송장 번호만 이번 요청 값으로 바꾼다. 상태 · 아이디 · 버전 · <b>출고 시각</b>은 그대로다.
     *
     * <p>출고 시각을 바꾸지 않는 이유 — 반영 전 송장은 결과 모름(타임아웃 등)으로 남은 것이라 order 가 처음 보낸 값을 이미 커밋했을 수 있다. 그러면 order 의
     * {@code shipped_at} 이 정본이고 재시도의 멱등 200 은 그 값을 바꾸지 않는다 — 처음 값을 그대로 다시 보내야 두 서비스의 출고 시각이 같다.
     * 처음 값이 order 허용 범위(지금 − 30일)를 지난 뒤의 재시도는, order 가 커밋하지 않았다면 400 {@code ORDER_159} 로 거절되고 대기 송장이 지워진다 —
     * 그다음 요청은 새 송장(새 아이디 · 그때 시각)으로 등록된다 ({@code order-flow-guide.md} §4).
     *
     * @throws IllegalStateException 이미 주문에 반영된 송장이다 — 반영된 송장은 바꾸지 않는다(호출 쪽이 먼저 막는다)
     */
    public Shipment retarget(CarrierCode newCarrier, String newTrackingNumber) {
        if (orderSynced) {
            throw new IllegalStateException("주문에 반영된 송장은 바꾸지 않습니다: id=" + id);
        }
        return new Shipment(id, orderId, memberId, newCarrier, newTrackingNumber, status, shippingScope, false, false, null, shippedAt, deliveredAt, version);
    }

    /**
     * 주문 반영 완료 — order 응답의 회원 · 배송 범위를 스냅숏한다. 응답의 주문 상태와 상관없이 반영된 것으로 본다(§4 실패 표 200 줄).
     *
     * @param newShippingScope 배송 국가를 읽지 못했으면 null
     * @throws IllegalStateException 이미 반영된 송장이다
     */
    public Shipment markOrderSynced(long newMemberId, ShippingScope newShippingScope) {
        if (orderSynced) {
            throw new IllegalStateException("이미 주문에 반영된 송장입니다: id=" + id);
        }
        return new Shipment(id, orderId, newMemberId, carrier, trackingNumber, status, newShippingScope, true, false, null, shippedAt, deliveredAt, version);
    }

    /**
     * 출고 완료 → 배송 중 (#18 PR-B2). delivery 안의 전이다 — 주문 상태는 배송 중 그대로라 order 를 부르지 않는다.
     *
     * @throws IllegalStateException 출고가 주문에 반영되지 않았거나 출고 완료가 아니다(호출 쪽이 먼저 막는다)
     */
    public Shipment startTransit() {
        requireTransition(ShipmentStatus.IN_TRANSIT);
        return new Shipment(id, orderId, memberId, carrier, trackingNumber, ShipmentStatus.IN_TRANSIT, shippingScope, true, false, null, shippedAt, null, version);
    }

    /**
     * 출고 완료 · 배송 중 → 배송 완료 (#18 PR-B2). 배송 완료 시각을 정하고(마이크로초 — order 의 {@code delivered_at} 과 같은 값을 들고 있게) order 반영 대기로 둔다.
     * 지금 상태를 직전 상태로 함께 저장한다 — order 가 거절하면 이 값으로 되돌린다(이력 줄의 순서에 기대지 않는다).
     *
     * @throws IllegalStateException 출고가 주문에 반영되지 않았거나 이미 배송 완료다(호출 쪽이 먼저 막는다)
     */
    public Shipment deliver(Instant now) {
        requireTransition(ShipmentStatus.DELIVERED);
        return new Shipment(
            id, orderId, memberId, carrier, trackingNumber, ShipmentStatus.DELIVERED, shippingScope, true, true, status, shippedAt,
            now.truncatedTo(ChronoUnit.MICROS), version
        );
    }

    /**
     * order 가 배송 완료를 받아들였다 — 반영 대기를 내린다. 응답의 주문 상태와 상관없이 반영된 것으로 본다(출고 반영과 같은 원칙).
     *
     * @throws IllegalStateException 반영 대기 중인 배송 완료가 아니다
     */
    public Shipment markCompletionSynced() {
        if (!completionPending) {
            throw new IllegalStateException("배송 완료 반영 대기가 아닙니다: id=" + id);
        }
        return new Shipment(id, orderId, memberId, carrier, trackingNumber, status, shippingScope, true, false, null, shippedAt, deliveredAt, version);
    }

    /**
     * order 가 배송 완료를 거절했다(반영되지 않은 것이 확실하다 — 404 · 409 {@code ORDER_020} · 400 시각) — 배송 완료 직전 상태로 되돌린다. 배송 완료 시각도 지운다 —
     * 다음 배송 완료는 그때 시각으로 새로 정한다. 되돌릴 상태는 배송 완료로 바꿀 때 저장한 {@link #previousStatus} 다.
     *
     * @throws IllegalStateException 반영 대기 중인 배송 완료가 아니다
     */
    public Shipment revertDelivery() {
        if (!completionPending) {
            throw new IllegalStateException("배송 완료 반영 대기가 아닙니다: id=" + id);
        }
        return new Shipment(id, orderId, memberId, carrier, trackingNumber, previousStatus, shippingScope, true, false, null, shippedAt, null, version);
    }

    private void requireTransition(ShipmentStatus target) {
        if (!orderSynced) {
            throw new IllegalStateException("출고가 주문에 반영되지 않은 송장입니다: id=" + id);
        }
        if (!status.canTransitTo(target)) {
            throw new IllegalStateException("바꿀 수 없는 배송 상태입니다: %s -> %s".formatted(status, target));
        }
    }
}
