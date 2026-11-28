package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 토스 Settlement 객체 중 저장하는 칸 (#219). 나머지 칸(mId · interestFee · supplyAmount · vat 등)은 읽지 않는다.
 * 모든 칸이 빠질 수 있다고 보고 {@code TossSettlementGatewayAdapter#parsePage} 가 필수 칸을 확인한다. 금액은 통화의 주 단위 숫자다.
 *
 * @param cancel 취소 거래면 취소 정보 객체. 승인 거래면 없거나 null
 * @param fees 수수료 항목 — 공급가({@code supplyAmount})를 더해 수수료 합으로 저장한다
 * @param approvedAt ISO-8601 오프셋 시각
 * @param soldDate 매출일 (yyyy-MM-dd)
 * @param paidOutDate 지급일 (yyyy-MM-dd)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossSettlementResponse(
    String paymentKey, String transactionKey, String orderId, String currency, String method, BigDecimal amount, List<Fee> fees,
    BigDecimal payOutAmount, String approvedAt, String soldDate, String paidOutDate, JsonNode cancel
) {

    /** 결제키 · 주문번호를 로그에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "TossSettlementResponse[soldDate=%s, currency=%s, amount=%s]".formatted(soldDate, currency, amount);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fee(String type, BigDecimal supplyAmount) {
    }
}
