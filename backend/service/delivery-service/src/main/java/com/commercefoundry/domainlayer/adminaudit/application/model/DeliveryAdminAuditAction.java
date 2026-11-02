package com.commercefoundry.domainlayer.adminaudit.application.model;

/**
 * delivery-service 어드민 감사 행위 이름의 정본 (#163, {@code security-guide.md} §11-4). 로그 검색 키라 한번 정한 이름을 바꾸지 않는다.
 * 컨트롤러의 {@code @AdminAudited(action)} 집합과 이 enum 값 집합이 같아야 한다 — {@code DeliveryAdminAuditActionTest} 가 본다.
 *
 * <p>배송비 정책 교체({@code SHIPPING_POLICY_*_REPLACE})는 #163 PR-D 가 더한다.
 */
public enum DeliveryAdminAuditAction {

    /** 송장 등록 (#18) — 대상 {@code order:<orderId>}, 일괄 등록은 주문마다 한 줄. 이미 등록된 송장(그대로 둠)도 {@code SUCCESS} 다. */
    SHIPMENT_REGISTER
}
