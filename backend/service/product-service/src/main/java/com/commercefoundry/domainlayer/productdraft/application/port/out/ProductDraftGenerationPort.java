package com.commercefoundry.domainlayer.productdraft.application.port.out;

import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;

/**
 * operations AI 상품 초안 생성 작업 등록 (#359 PR-B1, {@code POST /internal/v1/product-draft-generation-jobs}).
 *
 * <p><b>DB 트랜잭션 밖에서 부른다.</b> 늘 DB 에서 다시 읽은 claim 된 초안으로 부른다 — 회차 · 입력 hash · 시작한 어드민 · 만료 시각을 저장 값 그대로 보내야
 * operations 가 같은 (초안, 회차) 영수증을 재생한다. 응답을 모르면 같은 초안으로 다시 부르면 된다.
 */
public interface ProductDraftGenerationPort {

    /**
     * @throws com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException
     *     {@code PRODUCT_DRAFT_022}(operations 가 만료로 거절) · {@code 502}(등록 여부 모름 — 재시도) · {@code 504}(계약 밖 응답)
     */
    ProductDraftGenerationRegistrationQueryResult register(ProductDraft draft);
}
