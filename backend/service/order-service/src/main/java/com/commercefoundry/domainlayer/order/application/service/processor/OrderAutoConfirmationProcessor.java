package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.model.OrderConfirmationCandidate;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 자동 구매 확정 조회 · 건별 원자적 전이. 집계 조회 4문장은 건별 전이 경계이며 배치 조립의 N+1 이 아니다. */
@Component
@RequiredArgsConstructor
public class OrderAutoConfirmationProcessor {
    private final OrderRepositoryPort orders;
    private final SnowflakeIdGenerator ids;

    @Transactional(readOnly = true, timeout = 5)
    public List<OrderConfirmationCandidate> findDue(Instant cutoff, OrderConfirmationCandidate after, int limit) {
        var rows = after == null ? orders.findDueForConfirmation(cutoff, limit)
            : orders.findDueForConfirmationAfter(cutoff, after.deliveredAt(), after.orderId(), limit);
        return rows.stream().map(row -> new OrderConfirmationCandidate(row.orderId(), row.deliveredAt())).toList();
    }

    /** 헤더 @Version flush 와 SYSTEM 이력이 함께 커밋된다. 별도 이벤트 · 결제 · 재고 호출은 없다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public boolean confirm(long orderId, Instant cutoff) {
        Order before = orders.findById(orderId).orElse(null);
        if (before == null) {
            return false;
        }
        Order after = before.autoConfirmPurchase(cutoff);
        if (before == after) {
            return false;
        }
        orders.saveStatusChange(after, OrderStatusHistory.autoConfirmed(ids.generateId(), before));
        return true;
    }
}
