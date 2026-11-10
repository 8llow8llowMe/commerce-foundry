package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 본인 주문의 클레임 조회 (#609). 주문 소유를 먼저 확인한다 — 남의 주문은 클레임이 있어도 없는 주문({@code NOT_FOUND_ORDER})이다.
 * 소유 확인과 클레임 읽기는 한 읽기 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class OrderClaimHistoryProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final ClaimRepositoryPort claimRepositoryPort;

    @Transactional(readOnly = true, timeout = 5)
    public List<Claim> findClaims(long memberId, long orderId) {
        if (orderRepositoryPort.findByIdAndMemberId(orderId, memberId).isEmpty()) {
            throw new OrderException(OrderErrorCode.NOT_FOUND_ORDER);
        }
        return claimRepositoryPort.findAllByOrderId(orderId);
    }
}
