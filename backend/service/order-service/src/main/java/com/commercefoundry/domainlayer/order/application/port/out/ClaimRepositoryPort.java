package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.Claim;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepositoryPort {
    Optional<Claim> findByOrderIdAndRequestKey(long orderId, UUID requestKey);

    /** 클레임 하나(항목 · 버전 포함). 쓰기 쪽 Processor 가 버전을 대조한 뒤 {@link #update} 로 저장한다 — 잠그지 않는다. */
    Optional<Claim> findById(long claimId);

    /** 한 주문의 클레임(반품 · 취소)을 아이디 내림차순으로, 항목과 함께 읽는다 — 항목은 한 번의 IN 조회다 (#609). 없으면 빈 목록. */
    List<Claim> findAllByOrderId(long orderId);

    /** 호출자의 짧은 쓰기 트랜잭션에서 최신 행을 NOWAIT 잠근다. 잠금 중 외부 I/O를 하지 않는다. */
    Optional<Claim> findByIdForUpdate(long claimId);

    /**
     * 이미 반품에 잡힌 항목별 수량을 한 번에 읽는다 — {@link Claim#reservesReturnQuantity()} 인 반품(거절만 뺀 전부)을 더한다 (#17 B1). 이름은 B1 이전(REQUESTED 만 있던 때) 그대로다.
     * 환불 실패(REFUND_FAILED)는 수동 확정으로 환불될 수 있고, 복원 실패(RESTORATION_FAILED)는 환불이 이미 나갔다 — 빼면 같은 수량을 다시 반품 접수해
     * 이중 환불이 될 수 있다. 취소 클레임은 넣지 않는다.
     */
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
     * 다시 보낼 클레임 (#16 PR-2, 반품 포함 #17 B2a — 이름은 그대로) — 유형별 대기 상태({@link Claim.Type#awaitingAttemptStatuses()}: 취소
     * {@code REQUESTED · REFUNDED}, 반품 {@code APPROVED · REFUNDED})이고 {@code next_attempt_at <= now} 인 클레임을 두 유형 섞어 한 질의로
     * 다음 시각 · 아이디 순으로 {@code limit} 건까지, 항목과 함께 읽는다(항목은 한 번의 IN 조회).
     */
    List<Claim> findCancellationRetryTargets(Instant now, int limit);
}
