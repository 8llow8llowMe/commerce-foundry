package com.commercefoundry.domainlayer.productdraft.application.port.in;

import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response.ProductDraftGenerationInputResponse;

/**
 * operations 생성 작업 전용 AI 상품 초안 입력 (#359 PR-2a). 호출자는 {@code SERVICE_operations-service} 뿐이다.
 * 기능 플래그({@code product-draft.generation-enabled})가 닫혀 있으면 둘 다 {@code PRODUCT_DRAFT_040}(400)이다.
 */
public interface ProductDraftInternalUseCase {

    /** 사실 입력 · 이미지 메타데이터(순번 · 유형 · 형식 · 크기). 스토리지 키 · ETag · 공개 주소는 싣지 않는다. */
    ProductDraftGenerationInputResponse generationInput(long draftId);

    /** 고정한 이미지 한 장의 바이트. 바뀌었으면 {@code 044}(410), 순번 밖은 {@code 045}(404)다. */
    byte[] generationImage(long draftId, int index);
}
