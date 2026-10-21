package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 저장하는 판매 상태 (상품 · SKU). 품절은 저장하지 않는다 — 재고에서 유도한다 ({@link SaleAvailability}).
 *
 * <p>어드민 응답은 {@code {code, name, description}} 으로 내린다. 고객 응답은 이 값이 아니라 재고를 반영한 {@link SaleAvailability} 를 내린다.
 */
@Getter
@RequiredArgsConstructor
public enum SaleStatus implements CodeNameDescribable {

    ON_SALE("판매중", "판매하도록 설정했습니다. 재고가 없으면 고객 화면에는 품절로 보입니다."),
    STOP_SELLING("판매 종료", "판매를 종료하도록 설정했습니다. 재고와 무관하게 주문할 수 없습니다.");

    private final String displayName;
    private final String description;
}
