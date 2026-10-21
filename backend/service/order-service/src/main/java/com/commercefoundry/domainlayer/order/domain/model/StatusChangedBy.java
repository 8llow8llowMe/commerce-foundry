package com.commercefoundry.domainlayer.order.domain.model;

/**
 * 주문 상태를 바꾼 주체의 유형 ({@code order_status_history.changed_by_type}).
 */
public enum StatusChangedBy {

    /** 고객 — 아이디는 회원 아이디. */
    CUSTOMER,
    /** 어드민 — 아이디는 관리자 회원 아이디. */
    ADMIN,
    /** 스케줄러 등 이 서비스 자신 — 아이디 없음. */
    SYSTEM,
    /** 다른 서비스의 internal 호출 (결제 완료 알림 등) — 아이디 없음. */
    SERVICE
}
