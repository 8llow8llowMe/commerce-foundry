package com.commercefoundry.domainlayer.product.domain.model;

/**
 * 어드민 재고 조정 사유. 조정 이력({@link StockAdjustmentHistory})에 남는다.
 */
public enum StockAdjustmentReason {

    /** 입고 */
    RESTOCK,
    /** 실사 — 실물 수량에 맞춘다 */
    RECOUNT,
    /** 파손 · 불량 */
    DAMAGE,
    /** 재고 관리 여부 변경 (무제한 ↔ 수량 관리) */
    MANAGEMENT_CHANGE,
    /** 기타 — 메모로 사유를 남긴다 */
    OTHER
}
