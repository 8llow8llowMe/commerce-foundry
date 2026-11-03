package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 주문에 속한 반품 접수. 예상 환불액 null은 미산정이며 0원과 다르다. */
public record Claim(
    long id, long orderId, UUID requestKey, Type type, Status status, Money expectedRefund, List<ClaimItem> items, Instant requestedAt, Long version
) {
    public Claim {
        if (id <= 0 || orderId <= 0 || (version != null && version < 0)) {
            throw new IllegalArgumentException("Invalid claim identifiers");
        }
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        items = items.stream().sorted(Comparator.comparingLong(ClaimItem::orderItemId)).toList();
        if (items.isEmpty() || items.size() > 50 || items.stream().map(ClaimItem::orderItemId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Invalid claim items");
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type implements CodeNameDescribable {
        RETURN("반품", "배송된 상품의 반품 요청입니다.");

        private final String displayName;
        private final String description;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status implements CodeNameDescribable {
        REQUESTED("접수", "반품 요청이 접수되었습니다. 환불은 아직 실행되지 않았습니다.");

        private final String displayName;
        private final String description;
    }
}
