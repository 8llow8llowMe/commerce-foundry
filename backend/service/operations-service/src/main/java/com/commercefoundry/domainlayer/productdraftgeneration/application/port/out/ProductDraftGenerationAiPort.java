package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import java.util.List;

/** AI 상품 문구 제안. 실패는 {@code ProductDraftGenerationRemoteFailure} 로만 알린다. */
public interface ProductDraftGenerationAiPort {

    /**
     * @param factsJson  운영자 입력 사실(검증한 JSON — 프롬프트의 데이터 칸)
     * @param imagesJson 이미지 순번 · 유형(검증한 JSON)
     * @param images     고정 값과 맞춘 이미지 바이트. 없으면 빈 목록
     */
    ProductDraftGenerationCandidate generate(String factsJson, String imagesJson, List<ProductDraftGenerationImageQueryResult> images, long jobId, int attempt);
}
