package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 결제 게이트 ({@code payment-guide.md} §3-4 · §6).
 *
 * @param enabled 결제 준비 · 승인을 받는가. 기본 false(닫힌 기본값) — 결제 완료를 주문에 반영하는 경로(#308)와 UNKNOWN 재조회 · IN_PROGRESS 정체 복구 ·
 *     되돌린 READY 정리(#15)가 들어오기 전에는 돈은 빠졌는데 주문은 결제 대기로 남는다. 토스 테스트 키 환경(local · dev)만 켠다. 상태 조회는 막지 않는다
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentGateProperties(boolean enabled) {
}
