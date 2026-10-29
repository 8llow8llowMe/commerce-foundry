package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.model.OrderExpiryCandidate;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 주문 만료 — 대상 조회와 건별 만료 전이 ({@code order-flow-guide.md} §1-3).
 *
 * <p>만료는 주문마다 새 트랜잭션({@code REQUIRES_NEW}, 5초)이다. 주문 상태 · {@code SYSTEM} 이력 · {@code order-expired} outbox 가 함께 커밋되거나 함께 롤백된다.
 * 건마다 주문 전체를 다시 읽는 것은 배치 조립의 N+1 이 아니라 주문별 원자적 전이 경계다 — 조회는 기존 집계 조회 4문장이다.
 */
@Component
@RequiredArgsConstructor
public class OrderExpiryProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final OrderEventPort orderEventPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 기한이 지난 결제 대기 주문의 한 배치 — {@code after} 다음부터 {@code limit} 건. 커서 값(기한 · 아이디)을 풀어 out-port 에 넘기고,
     * 조회 결과를 Facade 용 커서로 바꿔 돌려준다.
     *
     * @param after 앞 배치의 마지막 건. null 이면 첫 배치다
     */
    @Transactional(readOnly = true, timeout = 5)
    public List<OrderExpiryCandidate> findDue(Instant now, OrderExpiryCandidate after, int limit) {
        List<OrderExpiryCandidateQueryResult> results = after == null
            ? orderRepositoryPort.findDueForExpiry(now, limit)
            : orderRepositoryPort.findDueForExpiryAfter(now, after.paymentDueAt(), after.orderId(), limit);
        return results.stream().map(OrderExpiryCandidate::from).toList();
    }

    /**
     * 주문 하나를 만료한다.
     *
     * @return 만료했으면 true. 없거나 · 이미 결제 대기가 아니거나(만료 · 결제 완료) · 아직 기한 전이면 바꾸지 않고 false
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 결제 완료 · 다른 인스턴스의 만료가 먼저 커밋했을 때 (이 트랜잭션은 롤백)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean expire(long orderId, Instant now) {
        Order order = orderRepositoryPort.findById(orderId).orElse(null);
        if (order == null || order.status() != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        Order expired = order.expire(now);
        if (expired == order) {
            return false;
        }
        orderRepositoryPort.saveExpiry(expired, OrderStatusHistory.expired(snowflakeIdGenerator.generateId(), order));
        orderEventPort.expired(order.id(), order.stockReservationId());
        return true;
    }
}
