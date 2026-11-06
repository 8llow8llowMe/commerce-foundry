package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ClaimRepositoryPort {
    Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey);

    /** 클레임 하나(항목 포함, #17 어드민 수습). 호출자 트랜잭션에서 읽는다. */
    Optional<Claim> findById(long claimId);

    /**
     * 주어진 주문 중 결제 후 취소 클레임이 거절(REJECTED)된 주문 아이디 (#17 PR-A) — 고객 응답의 {@code cancelable} 을 끄는 데 쓴다. 한 번의 IN 조회다.
     * 빈 입력이면 조회하지 않는다.
     */
    Set<Long> findRejectedCancellationOrderIds(Collection<Long> orderIds);

    /** REQUESTED 반품의 항목별 수량을 한 번에 읽는다. */
    Map<Long, Long> sumRequestedReturnQuantities(long orderId);

    /**
     * 호출자의 주문 잠금 트랜잭션에 참여한다.
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.ClaimRequestKeyConflictException 같은 주문 · 같은 요청 키의 클레임이 이미 있을 때(PostgreSQL).
     *     그 밖의 제약 위반은 {@code DataIntegrityViolationException} 그대로다
     */
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
