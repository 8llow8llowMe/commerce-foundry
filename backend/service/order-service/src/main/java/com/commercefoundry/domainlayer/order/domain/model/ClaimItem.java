package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 클레임 항목. 사유는 비용 부담이나 환불 정책을 결정하지 않는다. */
public record ClaimItem(long id, long orderItemId, int quantity, Reason reason) {

    public ClaimItem {
        if (id <= 0 || orderItemId <= 0 || quantity < 1 || quantity > 99) {
            throw new IllegalArgumentException("Invalid claim item");
        }
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * 접수 사유. 반품 사유 3종과 결제 후 취소 사유({@link OrderCancelReason} 과 같은 이름)를 함께 둔다 (#16 PR-2).
     * 고객 반품 접수는 {@link #isReturnReason()} 인 값만 받는다 — 취소 사유가 반품 API 로 들어오지 않게.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Reason implements CodeNameDescribable {
        CHANGE_OF_MIND("단순 변심", "단순 변심으로 반품을 요청합니다."),
        DEFECTIVE("상품 불량", "상품 불량으로 반품을 요청합니다."),
        WRONG_ITEM("오배송", "다른 상품을 받아 반품을 요청합니다."),
        ORDER_MISTAKE("주문 내용 잘못 선택", "상품 · 옵션 · 수량 · 배송지를 잘못 골라 취소를 요청합니다."),
        PAYMENT_ISSUE("결제 문제", "결제 수단 변경 · 결제 문제로 취소를 요청합니다."),
        OTHER("기타", "그 밖의 사유로 취소를 요청합니다.");

        private static final Set<Reason> RETURN_REASONS = EnumSet.of(CHANGE_OF_MIND, DEFECTIVE, WRONG_ITEM);

        private final String displayName;
        private final String description;

        /** 고객 반품 접수가 받는 사유인가. */
        public boolean isReturnReason() {
            return RETURN_REASONS.contains(this);
        }

        /** 결제 후 취소 클레임이 싣는 사유인가 — {@link OrderCancelReason} 에 같은 이름이 있는 값만이다. */
        public boolean isCancellationReason() {
            return Arrays.stream(OrderCancelReason.values()).anyMatch(reason -> reason.name().equals(name()));
        }

        /** 결제 후 취소 사유를 이름 그대로 옮긴다 — {@link OrderCancelReason} 에 값이 생기면 여기에도 같은 이름을 더한다. */
        public static Reason of(OrderCancelReason reason) {
            return valueOf(Objects.requireNonNull(reason, "reason").name());
        }
    }
}
