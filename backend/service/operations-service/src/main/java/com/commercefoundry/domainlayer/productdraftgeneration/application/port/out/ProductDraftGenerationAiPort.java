package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import java.util.List;
import java.util.Objects;

/** AI 상품 문구 제안. 실패는 {@code ProductDraftGenerationRemoteFailure} 로만 알린다. */
public interface ProductDraftGenerationAiPort {

    /**
     * @param factsJson  운영자 입력 사실(검증한 JSON — 프롬프트의 데이터 칸)
     * @param imagesJson 이미지 순번 · 유형(검증한 JSON)
     * @param images     고정 값과 맞춘 이미지. 없으면 빈 목록
     */
    ProductDraftGenerationCandidate generate(String factsJson, String imagesJson, List<Attachment> images, long jobId, int attempt);

    /**
     * 요청에 붙일 이미지 한 장. 바이트는 복사하지 않고 받은 결과를 그대로 가리킨다.
     *
     * @param mediaType 검증 Processor 가 고정 메타데이터와 magic bytes 로 확인한 이미지 형식(응답 헤더 값이 아니다)
     * @param image     받은 바이트
     */
    record Attachment(String mediaType, ProductDraftGenerationImageQueryResult image) {

        public Attachment {
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(image, "image");
        }
    }
}
