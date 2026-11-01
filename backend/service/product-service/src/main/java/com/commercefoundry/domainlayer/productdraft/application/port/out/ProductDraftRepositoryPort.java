package com.commercefoundry.domainlayer.productdraft.application.port.out;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
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
}
