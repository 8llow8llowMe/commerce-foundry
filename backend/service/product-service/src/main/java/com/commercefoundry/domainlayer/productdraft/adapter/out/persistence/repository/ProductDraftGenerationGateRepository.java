package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity.ProductDraftGenerationGateEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/** 매장 전체 생성 게이트(#359 B3, D13) — 새 회차를 시작하는 쓰기가 단일 행에 줄을 선다. */
public interface ProductDraftGenerationGateRepository extends JpaRepository<ProductDraftGenerationGateEntity, Integer> {

    /** 게이트 잠금 대기 상한. 넘으면 잠금 예외다 — 호출한 쪽이 409 {@code PRODUCT_DRAFT_025} 로 바꾼다. */
    String LOCK_TIMEOUT_MILLIS = "2000";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select gate from ProductDraftGenerationGateEntity gate where gate.id = :id")
    Optional<ProductDraftGenerationGateEntity> findByIdForUpdate(int id);
}
