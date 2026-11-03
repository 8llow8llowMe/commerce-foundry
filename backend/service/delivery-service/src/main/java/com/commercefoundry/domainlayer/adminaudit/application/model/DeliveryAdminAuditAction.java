package com.commercefoundry.domainlayer.adminaudit.application.model;

/**
 * delivery-service 어드민 감사 행위 이름의 정본 (#163, {@code security-guide.md} §11-4). 로그 검색 키라 한번 정한 이름을 바꾸지 않는다.
 * 컨트롤러의 {@code @AdminAudited(action)} 집합과 이 enum 값 집합이 같아야 한다 — {@code DeliveryAdminAuditActionTest} 가 본다.
 *
 * <p>송장 상세 · 목록 조회는 남기지 않는다 — 개인정보(받는 분 · 주소 · 회원)를 싣지 않는다.
 */
public enum DeliveryAdminAuditAction {

    /** 송장 등록 (#18) — 대상 {@code order:<orderId>}, 일괄 등록은 주문마다 한 줄. 이미 등록된 송장(그대로 둠)도 {@code SUCCESS} 다. */
    SHIPMENT_REGISTER,

    /**
     * 배송 상태 변경 (#18 PR-B2) — 대상 {@code delivery:<deliveryId>}. 같은 상태라 그대로 둔 것도 {@code SUCCESS}, 404 · 409 · 5xx 업무 오류는 {@code FAILURE} 다.
     *
     * <p>{@code FAILURE} 는 "요청이 오류로 끝났다" 는 뜻이지 "상태가 바뀌지 않았다" 가 아니다 — 결과 모름(029 · 030)은 배송 완료 · 반영 대기로 남고,
     * 응답용 다시 읽기 실패(031)는 상태를 바꾼 뒤일 수 있다. 상태 변경 여부의 정본은 배송 상태 이력이다.
     */
    SHIPMENT_STATUS_CHANGE,

    /**
     * 국내 배송비 정책 전체 교체 (#69) — 대상 {@code shipping-policy:domestic}(스토어에 하나). 처음 만들기도 같은 행위다.
     * 바꾼 금액 · 도서산간 구간은 싣지 않는다 — 교체 전 · 후 값의 정본은 교체 이력이다.
     */
    SHIPPING_POLICY_DOMESTIC_REPLACE,

    /**
     * 해외 배송비 정책 전체 교체 — 대상 {@code shipping-policy:international}(스토어에 하나). 처음 만들기도 같은 행위다.
     * 바꾼 금액 · 국가 그룹 · 관세 안내 문구는 싣지 않는다.
     */
    SHIPPING_POLICY_INTERNATIONAL_REPLACE
}
