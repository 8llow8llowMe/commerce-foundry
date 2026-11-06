package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepositoryPort {
    Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey);

    /** REQUESTED 반품의 항목별 수량을 한 번에 읽는다. */
    Map<Long, Long> sumRequestedReturnQuantities(long orderId);

    /** 호출자의 주문 잠금 트랜잭션에 참여한다. */
    Claim insert(Claim claim);

    /**
     * 저장된 클레임의 상태 · 재시도 칸을 바꾼다 (#16 PR-2). 호출자 트랜잭션에 참여하고, 버전이 다르면 낙관적 락 예외다 — 재시도 스케줄러 · 고객 요청이 같은 클레임을
     * 함께 집어도 한 쪽만 커밋한다. 항목은 바꾸지 않는다.
     *
     * @return 올라간 버전을 실은 클레임
     */
    Claim update(Claim claim);

    /**
     * 다시 보낼 결제 후 취소 클레임 (#16 PR-2) — {@code type = CANCELLATION}, {@code status in (REQUESTED, REFUNDED)}, {@code next_attempt_at <= now} 를
     * 다음 시각 · 아이디 순으로 {@code limit} 건까지, 항목과 함께 읽는다(항목은 한 번의 IN 조회).
     */
    List<Claim> findCancellationRetryTargets(Instant now, int limit);
}
