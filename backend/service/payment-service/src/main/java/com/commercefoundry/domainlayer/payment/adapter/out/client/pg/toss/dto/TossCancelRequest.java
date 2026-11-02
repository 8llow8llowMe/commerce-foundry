package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto;

import java.math.BigDecimal;

/** 취소 금액을 항상 명시해 미확정 재시도에서 전액 취소로 바뀌지 않게 한다. */
public record TossCancelRequest(String cancelReason, BigDecimal cancelAmount, String cancelRequestId) {
}
