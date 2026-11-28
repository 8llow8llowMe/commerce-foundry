package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PG 정산 수집 · 대사 (#219, {@code payment-guide.md} §10).
 *
 * @param enabled 기본 false. 꺼져 있으면 대사 요청을 DB · PG 전에 409 {@code SETTLEMENT_001} 로 거절한다. 정산 조회 응답 계약(미확인 가정)을 고객사 · 토스와
 *     확인하고 운영 DDL 을 적용한 뒤에만 켠다. 켜면 기동 때 정산 테이블을 확인한다({@code PaymentSettlementSchemaGuard})
 */
@ConfigurationProperties(prefix = "payment.settlement")
public record PaymentSettlementProperties(boolean enabled) {
}
