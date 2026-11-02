package com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto;

import com.commercefoundry.common.dto.item.MoneyItem;
import java.time.Instant;

/**
 * payment-service {@code GET /internal/v1/payments/{paymentId}} 응답 (#308 PR-A2). payment-service 의 DTO 를 가져다 쓰지 않는다 — 원격 계약은 이 adapter 안에서만 안다.
 * 쓰는 칸만 둔다 — 결제수단({@code method}) 등 모르는 칸은 무시한다.
 *
 * @param status {@code {code, name, description}} — {@code code} 가 {@code PaymentStatus} 제어 코드다
 * @param amount 최소 단위 정수 + 통화
 * @param approvedAt 승인 시각 (UTC). 승인 전이면 null
 */
public record PaymentInternalResponse(String paymentId, String orderId, String memberId, Status status, MoneyItem amount, Instant approvedAt) {

    public record Status(String code) {
    }
}
