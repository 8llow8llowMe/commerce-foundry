package com.commercefoundry.domainlayer.shipment.domain.model;

import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 송장(배송) — 주문당 1건 ({@code order-flow-guide.md} §4). 상태는 도메인 메서드로만 바꾼다.
 *
 * <p>{@code orderSynced} 는 주문 상태 반영(order-service {@code Order#ship}) 여부다. 등록하면 false 로 생기고, order 가 200 으로 답한 뒤에만 true 가 된다.
 * <b>true 가 된 송장은 지우거나 다른 송장 번호로 바꾸지 않는다</b> — order 는 송장 아이디를 저장하지 않아 다른 송장의 재등록을 구분하지 못한다(§4 "주문당 송장 1건").
 * 반영 전(false) 송장만 같은 등록 요청의 재시도가 택배사 · 송장 번호를 이번 값으로 바꿀 수 있다({@link #retarget}).
 *
 * @param memberId 주문한 회원 — order 응답의 스냅숏. 반영 전에는 null
 * @param shippingScope 배송 범위 — order 응답의 배송 국가로 정한다. 반영 전이거나 국가 코드를 읽지 못했으면 null
 * @param shippedAt 출고 시각 — 처음 등록할 때 정해 order 에 보낸 값 그대로다(마이크로초까지, order 의 {@code shipped_at} 과 같다). 재시도해도 바뀌지 않는다
 * @param deliveredAt 배송 완료 시각. {@link ShipmentStatus#DELIVERED} 일 때만 있다
 * @param version 낙관적 락 버전. 아직 저장하지 않은 송장이면 null
 */
public record Shipment(
    long id, long orderId, Long memberId, CarrierCode carrier, String trackingNumber, ShipmentStatus status,
    ShippingScope shippingScope, boolean orderSynced, Instant shippedAt, Instant deliveredAt, Long version
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
        if ((status == ShipmentStatus.DELIVERED) != (deliveredAt != null)) {
            throw new IllegalArgumentException("배송 완료 시각은 배송 완료 상태에만 있습니다: status=%s".formatted(status));
        }
    }

    /**
     * 새 송장 — 출고 완료 · 주문 반영 전. 출고 시각은 마이크로초로 자른다 — order 의 {@code shipped_at}(timestamptz)과 같은 값을 들고 있게.
     */
    public static Shipment register(long id, long orderId, CarrierCode carrier, String trackingNumber, Instant shippedAt) {
        return new Shipment(
            id, orderId, null, carrier, trackingNumber, ShipmentStatus.SHIPPED,
            null, false, shippedAt.truncatedTo(ChronoUnit.MICROS), null, null
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
        return new Shipment(id, orderId, memberId, newCarrier, newTrackingNumber, status, shippingScope, false, shippedAt, deliveredAt, version);
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
        return new Shipment(id, orderId, newMemberId, carrier, trackingNumber, status, newShippingScope, true, shippedAt, deliveredAt, version);
    }
}
