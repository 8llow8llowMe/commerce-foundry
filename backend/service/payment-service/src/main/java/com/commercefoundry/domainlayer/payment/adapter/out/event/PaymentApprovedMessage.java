package com.commercefoundry.domainlayer.payment.adapter.out.event;

import java.time.Instant;

/**
 * {@code payment-approved} v1 페이로드 — 토픽 {@code <ns>.payment.payment-approved}, 키는 주문 아이디 ({@code payment-guide.md} §6). 필드를 바꾸면 스키마 버전을 올린다.
 *
 * <p>아이디 · 금액 · 통화 · 시각 · 수단 코드만 싣는다 — PG 결제키 · 카드 정보 · 개인정보 · PG 원문은 싣지 않는다(messaging-core 코덱의 금지 필드가 이름으로도 막는다).
 * 봉투에는 진위 확인 수단이 없어 컨슈머는 이 값을 알림으로만 보고 결제 internal API 로 재조회해 확정한다.
 *
 * @param paymentId 결제 시도 아이디 (Snowflake, 문자열 — JS 정밀도)
 * @param orderId 주문 아이디 (문자열) — 봉투 aggregateId · Kafka 키와 같다
 * @param memberId 결제한 회원 아이디 (문자열) — 주문 소유자와 교차 검증한다
 * @param amount 승인 금액 — 통화의 최소 단위 정수 (KRW 는 원, USD 는 센트). 지금은 KRW 만 승인한다(#20 전)
 * @param currency 통화 코드 ({@code KRW} · {@code USD})
 * @param approvedAt PG 승인 시각 (ISO-8601 UTC)
 * @param method 결제수단 코드 ({@code PaymentMethod} 이름, 예: {@code CARD} · {@code EASY_PAY})
 */
public record PaymentApprovedMessage(String paymentId, String orderId, String memberId, long amount, String currency, Instant approvedAt, String method) {

    /** 이벤트 종류 — 토픽 끝 토막. */
    public static final String EVENT_TYPE = "payment-approved";
    public static final int SCHEMA_VERSION = 1;
    /** 봉투 aggregateType. */
    public static final String AGGREGATE_TYPE = "payment";
    /** 이 이벤트를 내는 서비스 — 토픽 이름의 생산 서비스 토막({@code commerce.messaging.service}). */
    public static final String PRODUCER_SERVICE = "payment";
}
