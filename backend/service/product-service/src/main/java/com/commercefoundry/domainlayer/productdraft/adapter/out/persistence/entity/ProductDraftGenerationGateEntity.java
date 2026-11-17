package com.commercefoundry.domainlayer.productdraft.adapter.out.persistence.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 매장 전체 생성 동시성 상한(#359 B3)을 직렬화할 단일 행. B1은 스키마만 준비한다. */
@Entity
@Table(name = "product_draft_generation_gate", comment = "AI 상품 초안 매장 전체 생성 게이트",
    check = @CheckConstraint(name = "ck_product_draft_generation_gate_singleton", constraint = "id = 1"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDraftGenerationGateEntity {

    @Id
    @Column(name = "id", nullable = false, comment = "단일 게이트 행 (항상 1)")
    private Integer id;
}
