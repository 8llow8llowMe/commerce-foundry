package com.commercefoundry.domainlayer.picking.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 출고 묶음을 만들 때 order 에서 읽은 주문 한 건의 스냅숏 — 주문 상태 · 배송 국가 · 항목(상품 옵션 · 수량 · 제작 여부)만 갖는다. 받는 사람 · 주소 · 연락처 같은
 * 개인정보는 들고 다니지 않는다(order 응답에도 없다).
 *
 * <p>항목이 없는 주문(order 가 {@code lines=[]} 로 준다)도 스냅숏으로 받는다 — 예외가 아니라 {@link PickingExclusion.Reason#NO_ORDER_ITEMS} 제외로 판정한다.
 * 같은 상품 옵션이 여러 줄이면 {@link #quantities()} 가 주문 안에서 합친다.
 *
 * @param status      order 의 주문 상태 코드({@code OrderStatus} 이름). 모르는 값이면 "상품 준비 중 아님" 으로 제외된다
 * @param countryCode 배송 국가(ISO 3166-1 alpha-2). 배송지가 없으면 null 이고 해외 배송과 같이 제외된다
 */
public record PickingSnapshot(long orderId, String status, String countryCode, List<Line> lines) {

    /** 국내 배송 국가 — 피킹 · 포장 검수는 국내 일반 출고만 다룬다. */
    static final String DOMESTIC_COUNTRY = "KR";

    public PickingSnapshot {
        if (orderId <= 0) {
            throw new IllegalArgumentException("주문 아이디는 양수여야 합니다.");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("주문 상태가 필요합니다.");
        }
        if (lines == null || lines.stream().anyMatch(line -> line == null)) {
            throw new IllegalArgumentException("주문 항목 목록이 필요합니다.");
        }
        lines = List.copyOf(lines);
    }

    /**
     * 출고 묶음에 담을 수 없는 이유. 비어 있으면 대상이다 — 상품 준비 중 · 국내(KR) 배송 · 항목 1개 이상 · 제작 상품 없음.
     * 판정 순서는 주문 상태 → 배송 국가 → 항목 유무 → 제작 상품이다(앞선 사유 하나만 남긴다).
     */
    public Optional<PickingExclusion.Reason> exclusionReason() {
        Optional<PickingExclusion.Reason> byStatus = PickingExclusion.Reason.ofOrderStatus(status);
        if (byStatus.isPresent()) {
            return byStatus;
        }
        if (!DOMESTIC_COUNTRY.equals(countryCode)) {
            return Optional.of(PickingExclusion.Reason.OVERSEAS_SHIPPING);
        }
        if (lines.isEmpty()) {
            return Optional.of(PickingExclusion.Reason.NO_ORDER_ITEMS);
        }
        if (lines.stream().anyMatch(Line::customOrder)) {
            return Optional.of(PickingExclusion.Reason.CUSTOM_ORDER_INCLUDED);
        }
        return Optional.empty();
    }

    public boolean eligible() {
        return exclusionReason().isEmpty();
    }

    /** 상품 옵션 아이디 → 이 주문에서 꺼낼 수량(같은 옵션 줄은 합친다). 합이 long 을 넘으면 {@link ArithmeticException}. */
    public Map<Long, Long> quantities() {
        Map<Long, Long> result = new TreeMap<>();
        lines.forEach(line -> result.merge(line.skuId(), line.quantity(), Math::addExact));
        return Map.copyOf(result);
    }

    /**
     * 주문 항목 한 줄.
     *
     * @param skuId       상품 옵션(SKU) 아이디
     * @param customOrder 주문 시점 제작 상품 여부 — 제작 상품은 제작 흐름을 따로 타므로 피킹 대상이 아니다
     */
    public record Line(long skuId, long quantity, boolean customOrder) {

        public Line {
            if (skuId <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("상품 옵션과 수량은 양수여야 합니다.");
            }
        }
    }
}
