package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.info.OrderPaymentSummaryInfo;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 결제 준비용 주문 요약을 만든다 (#14) — PG 에 보낼 주문명 규칙이 이 한 곳에 있다. DB · 원격에 닿지 않아 트랜잭션이 없다.
 *
 * <p>주문명 규칙 ({@code order-flow-guide.md} §2-4):
 * <ul>
 *   <li>줄 1개 → 그 줄의 주문 시점 상품명. 줄 N개 → {@code "{첫 줄 상품명} 외 {N-1}건"}. 첫 줄은 저장 순서(lineNo 오름차순)의 첫 줄이다</li>
 *   <li>상한 {@value #MAX_ORDER_NAME_LENGTH}자는 <b>코드포인트</b>로 센다. 넘으면 첫 줄 상품명을 먼저 잘라 접미사 {@code " 외 N건"} 을 지킨다 —
 *       서로게이트 쌍(이모지 등) 가운데서 자르지 않는다</li>
 *   <li>첫 줄 상품명이 비었거나 공백뿐이면 {@code "주문 {orderId}"}</li>
 * </ul>
 */
@Component
public class OrderPaymentSummaryProcessor {

    /** PG 주문명 상한 (코드포인트). 토스페이먼츠 {@code orderName} 최대 길이와 같다. */
    static final int MAX_ORDER_NAME_LENGTH = 100;

    private static final String FALLBACK_PREFIX = "주문 ";

    public OrderPaymentSummaryInfo summarize(Order order) {
        return new OrderPaymentSummaryInfo(order.id(), order.memberId(), order.status(), order.quote().totalAmount(),
            orderName(order.id(), order.quote().lines()), order.paymentDueAt());
    }

    static String orderName(long orderId, List<OrderQuoteLine> lines) {
        String firstName = lines.getFirst().productName().strip();
        if (firstName.isEmpty()) {
            return FALLBACK_PREFIX + orderId;
        }
        String suffix = lines.size() > 1 ? " 외 " + (lines.size() - 1) + "건" : "";
        int budget = MAX_ORDER_NAME_LENGTH - suffix.codePointCount(0, suffix.length());
        if (firstName.codePointCount(0, firstName.length()) > budget) {
            firstName = firstName.substring(0, firstName.offsetByCodePoints(0, budget)).stripTrailing();
        }
        return firstName + suffix;
    }
}
