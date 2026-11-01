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

    /**
     * 회원의 요청 기록 중 마지막 활동(삽입 · 인수 · lease 종료 · 완료 — {@code updated_at})이 {@code since} 이후(포함)인 것의 수, {@code excludedRequestId} 는 뺀다 (#231 활성 시도 상한).
     * 상태와 무관하다. 만든 시각이 아니라 활동 시각으로 세야 창 밖 휴면 기록을 이어받은 시도도 창에 다시 들어온다. 뺄 기록이 없으면 0.
     */
    long countActiveSince(long memberId, Instant since, long excludedRequestId);
    Optional<OrderRequest> takeOver(OrderRequest expected, Instant now, Duration lease);
    boolean endLease(OrderRequest expected, Instant now);

    /**
     * 기존 쓰기 트랜잭션 필수. 기한·버전·회원·키·지문·주문 ID가 일치하는 주인만 완료한다.
     * 후속 C3는 이를 먼저 호출하고 C1 주문 저장을 같은 트랜잭션에서 수행한다. 실패 시 전체 롤백한다.
     */
    void completeOwned(OrderRequest expected, Instant now);
}
