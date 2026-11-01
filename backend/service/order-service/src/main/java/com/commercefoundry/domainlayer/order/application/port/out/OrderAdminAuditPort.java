package com.commercefoundry.domainlayer.order.application.port.out;

/**
 * 어드민 주문 행위 감사 ({@code security-guide.md} §11-4, #17). 목록 · 상세는 마스킹했어도 개인정보(기본 주소 · 맞춤 입력 · 회원 아이디)를 읽는 행위라 남긴다.
 * 상태 변경은 주문 상태 이력이 정본이고, 거절된 시도까지 보려고 여기도 남긴다. 사유 · 주소 · 상태 이름은 싣지 않는다.
 */
public interface OrderAdminAuditPort {

    /** 주문 목록을 봤다. {@code condition} 은 조건 문자열(공백 없음) — 개인정보가 없다. */
    void orderSearched(long adminMemberId, String condition);

    /** 주문 상세를 봤다. */
    void orderViewed(long adminMemberId, long orderId);

    /** 주문 상태를 바꿨다(이미 그 상태였던 경우 포함). */
    void orderStatusChangeSucceeded(long adminMemberId, long orderId);

    /** 주문 상태를 바꾸지 못했다 — 이유는 나누지 않는다. */
    void orderStatusChangeFailed(long adminMemberId, long orderId);
}
