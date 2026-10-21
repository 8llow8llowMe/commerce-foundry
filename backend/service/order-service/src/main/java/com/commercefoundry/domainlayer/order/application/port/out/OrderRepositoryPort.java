package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.util.Optional;

/**
 * 주문 — 주문 행 · 배송지 · 항목 · 맞춤 제작 입력 · 상태 이력. 조회는 모두 채워 돌려준다 (주문 1번 + 배송지 1번 + 항목 1번 + 입력 1번 — 주문 아이디 한 개 기준).
 */
public interface OrderRepositoryPort {

    /**
     * 새 주문(배송지 · 항목 · 입력 포함)과 첫 상태 이력을 넣고(persist — 같은 아이디는 PK 위반) 바로 flush 한다. 입력 행 아이디는 저장할 때 발급한다.
     *
     * @return 저장된 주문 — 생성 시각 · 버전(0)이 채워진다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderSaveException 사유는 {@code Reason}
     */
    Order insert(Order order, OrderStatusHistory history);

    /** 회원의 주문 하나. 없거나 다른 회원의 주문이면 빈 값이다. */
    Optional<Order> findByIdAndMemberId(long orderId, long memberId);
}
