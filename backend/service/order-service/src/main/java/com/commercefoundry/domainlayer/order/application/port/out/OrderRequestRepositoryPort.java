package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 요청 원문 없이 회원·키·지문을 저장한다. 획득/인수/기한 종료는 각각 독립된 짧은 트랜잭션이다. */
public interface OrderRequestRepositoryPort {
    Optional<OrderRequest> insertIfAbsent(OrderRequest request);
    Optional<OrderRequest> findByMemberIdAndIdempotencyKey(long memberId, UUID key);
    Optional<OrderRequest> takeOver(OrderRequest expected, Instant now, Duration lease);
    boolean endLease(OrderRequest expected, Instant now);

    /**
     * 기존 쓰기 트랜잭션 필수. 기한·버전·회원·키·지문·주문 ID가 일치하는 주인만 완료한다.
     * 후속 C3는 이를 먼저 호출하고 C1 주문 저장을 같은 트랜잭션에서 수행한다. 실패 시 전체 롤백한다.
     */
    void completeOwned(OrderRequest expected, Instant now);
}
