package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 결제 후 재고 복원 사유 ({@code order-flow-guide.md} §3). 복원 기록({@link StockRestoration})에 남는다.
 *
 * <p>호출자(order-service)가 분기 · 기록에만 쓰는 제어 코드라 표시 문자열을 두지 않는다 ({@code coding-conventions.md} §11 예외).
 */
public enum StockRestorationReason {

    /** 결제 완료 뒤 출고 전 취소 */
    CANCELLATION,
    /**
     * 반품 입고. 검수 뒤 어드민이 "재고로 되돌림" 을 고른 경우에만 호출한다 — 되돌리지 않는 반품(파손 · 불량)은 호출하지 않는다.
     * 복원 여부는 product-service 가 판단하지 않는다
     */
    RETURN
}
