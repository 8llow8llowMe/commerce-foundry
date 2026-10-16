package com.commercefoundry.common.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

/**
 * 응답의 금액 표현 — 최소 화폐 단위 정수 + 통화 ({@code api-design-guide.md} §2-3). 표시 포맷은 프론트가 {@code currency} 로 정한다.
 *
 * <p>common-core 는 shared-commerce 를 의존하지 않는다. {@code Money → MoneyItem} 변환은 서비스의 Presenter 쪽 헬퍼가 한다.
 */
@Schema(description = "금액 — 최소 화폐 단위 정수와 통화")
public record MoneyItem(

    @Schema(description = "최소 화폐 단위 금액 (KRW 는 원, USD 는 센트)", example = "129000")
    long amount,

    @Schema(description = "통화 코드 (ISO 4217)", example = "KRW")
    String currency
) {

    public MoneyItem {
        Objects.requireNonNull(currency, "currency");
    }

    public static MoneyItem of(long amount, String currency) {
        return new MoneyItem(amount, currency);
    }
}
