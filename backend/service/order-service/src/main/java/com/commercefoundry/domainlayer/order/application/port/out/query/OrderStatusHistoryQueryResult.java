package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import java.time.Instant;

/**
 * 주문 상태 이력 한 줄 (#17 어드민 상세). 언제는 행의 생성 시각(감사 칸)이다 — 도메인 {@code OrderStatusHistory} 에는 없다.
 *
 * @param fromStatus 주문 생성이면 null
 * @param changedById 회원 · 관리자 아이디. SYSTEM · SERVICE 면 null
 */
public record OrderStatusHistoryQueryResult(
    OrderStatus fromStatus, OrderStatus toStatus, StatusChangedBy changedByType, Long changedById, String reason, Instant changedAt
) {
}
