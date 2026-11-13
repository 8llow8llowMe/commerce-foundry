package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** product 가 고정한 AI 상품 초안 생성 입력 · 이미지 바이트. 실패는 {@code ProductDraftGenerationRemoteFailure} 로만 알린다. */
public interface ProductDraftGenerationInputPort {

    ProductDraftGenerationInputQueryResult getGenerationInput(long draftId);

    /**
     * {@code images} 순서대로 이미지 바이트를 한 장씩 읽고, 한 장을 받을 때마다 다음 장을 읽기 전에 {@code onImage} 에 넘긴다 — 호출자가 장마다 검증 ·
     * checkpoint 를 둔다. {@code onImage} 가 던지면 거기서 멈추고 그대로 내보낸다.
     *
     * <p>응답 {@code Content-Length} 가 없거나 {@code byteLength} 와 다르거나, {@code Content-Type} 이 계약 밖이거나, 본문이 {@code byteLength} 와 다르면
     * 본문을 더 읽지 않고 {@code PRODUCT_DRAFT_IMAGE_CHANGED}(재시도 불가)다. 받는 바이트는 장마다 {@code byteLength + 1} 을 넘지 않는다.
     */
    void readGenerationImages(long draftId, List<ImageRequest> images, Consumer<ProductDraftGenerationImageQueryResult> onImage);

    /**
     * 고정한 이미지 한 장의 기대 값.
     *
     * @param index       이미지 읽기 순번
     * @param byteLength  고정한 바이트 길이(1 이상). 이보다 길게 받지 않는다
     * @param contentType 고정한 이미지 형식(예: {@code image/jpeg})
     */
    record ImageRequest(int index, int byteLength, String contentType) {

        public ImageRequest {
            Objects.requireNonNull(contentType, "contentType");
            if (index < 0 || byteLength < 1) {
                throw new IllegalArgumentException("index 는 0 이상, byteLength 는 1 이상이어야 합니다");
            }
        }
    }
}
