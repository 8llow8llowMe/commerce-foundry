package com.commercefoundry.domainlayer.product.application.model;

/** 기존 상품 감사 행위와 분리한 품절 임박 기준 관리의 고정 행위 이름. */
public enum StockAlertAuditAction {
    STOCK_ALERT_POLICY_SET,
    STOCK_ALERT_POLICY_CLEAR
}
