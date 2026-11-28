package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftEntity;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductDraftRepository extends JpaRepository<ProductDraftEntity, Long> {

    /**
     * 매장 전체 생성 자리를 차지한 초안 수(#359 B3, D13) — 회차를 시작했고, 상태가 {@code statuses}(생성 대기 · 생성 중)이며, 보관 기간 안이고,
     * 회차를 {@code since} 뒤에 시작한 초안이다. 게이트 행을 잠근 뒤 부른다.
     */
    @Query("""
        select count(draft) from ProductDraftEntity draft
        where draft.status in :statuses
          and draft.generationNo > 0
          and draft.expiresAt > :now
          and draft.generationRequestedAt > :since
        """)
    long countGenerationSlots(Collection<ProductDraftStatus> statuses, Instant now, Instant since);
}
