package com.commercefoundry.domainlayer.productdraft.application.port.out;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import java.time.Instant;
import java.util.Optional;

public interface ProductDraftRepositoryPort {

    Optional<ProductDraft> findById(long draftId);

    /** 새 초안 INSERT. 아이디는 호출한 쪽이 발급했다. 저장한 초안(생성 시각 포함)을 돌려준다. */
    ProductDraft insert(ProductDraft draft);

    /**
     * 상태 · 거절 칸을 바꾸고 바로 내보낸다(flush) — 다른 트랜잭션이 먼저 바꿨으면 낙관적 락 예외
     * ({@code ObjectOptimisticLockingFailureException})가 이 호출 안에서 난다. 초안이 없으면 {@link IllegalStateException} 이다.
     */
    void updateDecision(ProductDraft draft);

    /**
     * 상태 · 생성 회차 칸(회차 · 시작한 어드민 · 시각 · 입력 hash · 작업)을 바꾸고 바로 내보낸다(flush). 낙관적 락 · 없는 초안은 {@link #updateDecision} 과 같다.
     * 같은 트랜잭션에서 먼저 읽은 버전으로 판정한다 — 그 사이 다른 트랜잭션이 커밋했으면 이 호출이 낙관적 락 예외를 낸다.
     */
    void updateGeneration(ProductDraft draft);

    /** 같은 트랜잭션에서 읽은 버전으로 상태와 결과 칸만 바꾸고 flush 한다. */
    void updateGenerationResult(ProductDraft draft);

    /**
     * 매장 전체 생성 게이트 행을 쓰기 잠근다(#359 B3, D13) — 트랜잭션이 끝날 때까지 다른 새 회차 시작이 기다린다. 잠금 대기가 길면 잠금 예외
     * ({@code PessimisticLockingFailureException})다. 게이트 행이 없으면(운영 DDL 의 insert 누락) {@code false} 다.
     */
    boolean lockGenerationGate();

    /**
     * 생성 자리를 차지한 초안 수 — 회차를 시작한 생성 대기 · 생성 중 초안 중 보관 기간 안이고 회차 시작이 {@code since} 뒤인 것. {@link #lockGenerationGate} 뒤에 부른다.
     */
    long countGenerationSlots(Instant now, Instant since);
}
