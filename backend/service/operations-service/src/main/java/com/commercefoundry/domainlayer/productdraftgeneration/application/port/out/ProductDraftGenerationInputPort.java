package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import java.util.List;

/** product 가 고정한 AI 상품 초안 생성 입력 · 이미지 바이트. 실패는 {@code ProductDraftGenerationRemoteFailure} 로만 알린다. */
public interface ProductDraftGenerationInputPort {

    ProductDraftGenerationInputQueryResult getGenerationInput(long draftId);

    /**
     * 메타데이터 순서대로 이미지 바이트를 읽는다. 결과 순서는 {@code images} 와 같다.
     * 크기 · 형식이 고정 값과 맞는지는 호출자(검증 Processor)가 본다.
     */
    List<ProductDraftGenerationImageQueryResult> getGenerationImages(long draftId, List<ProductDraftGenerationInputQueryResult.Image> images);
}
