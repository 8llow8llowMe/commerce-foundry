package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 반품 접수 항목. 사유는 비용 부담이나 환불 정책을 결정하지 않는다. */
public record ClaimItem(long id, long orderItemId, int quantity, Reason reason) {

    public ClaimItem {
        if (id <= 0 || orderItemId <= 0 || quantity < 1 || quantity > 99) {
            throw new IllegalArgumentException("Invalid return item");
        }
        Objects.requireNonNull(reason, "reason");
    }

    @Getter
    @RequiredArgsConstructor
    public enum Reason implements CodeNameDescribable {
        CHANGE_OF_MIND("단순 변심", "단순 변심으로 반품을 요청합니다."),
        DEFECTIVE("상품 불량", "상품 불량으로 반품을 요청합니다."),
        WRONG_ITEM("오배송", "다른 상품을 받아 반품을 요청합니다.");

        private final String displayName;
        private final String description;
    }
}
