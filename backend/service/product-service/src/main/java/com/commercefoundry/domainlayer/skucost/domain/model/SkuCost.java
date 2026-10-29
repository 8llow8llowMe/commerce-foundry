package com.commercefoundry.domainlayer.skucost.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * SKU 원가 적용 이력 한 행. append-only 다 — 고치는 메서드가 없고, 원가가 바뀌면 새 적용 시작 시각으로 행을 더한다.
 * {@code amount} 0 은 "0원이라고 명시했다" 이고, 행이 없는 것(미입력)과 다르다.
 *
 * @param effectiveFrom 이 원가가 적용되기 시작하는 시각 (포함). 다음 행의 시작 전까지 적용된다
 * @param adminMemberId 기록한 어드민 회원 아이디 (auth-service)
 * @param recordedAt    기록 시각 ({@code created_at}). 아직 저장하지 않은 행이면 null
 */
public record SkuCost(
    long id,
    long skuId,
    SkuCostType costType,
    Money amount,
    Instant effectiveFrom,
    String reason,
    long adminMemberId,
    Instant recordedAt
) {

    public SkuCost {
        Objects.requireNonNull(costType, "costType");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(reason, "reason");
    }
}
