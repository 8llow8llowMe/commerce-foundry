package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepositoryPort {
    Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey);

    /** REQUESTED 반품의 항목별 수량을 한 번에 읽는다. */
    Map<Long, Long> sumRequestedReturnQuantities(long orderId);

    /** 호출자의 주문 잠금 트랜잭션에 참여한다. */
    Claim insert(Claim claim);
}
