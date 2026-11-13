package com.commercefoundry.domainlayer.productdraftgeneration.adapter.out.client.ai;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.prompt.PromptValues;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationAiPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 상품 문구 제안 (기능 {@code product-draft}, 프롬프트 {@code ai/prompts/product-draft/v<버전>}). 사용량 원장 · 월 한도는 ai-core 계량 인터셉터가
 * {@code job:<jobId>:<attempt>} 로 남긴다(사이즈표와 같다). provider 오류 원문 · 출력 원문 · 이미지 바이트는 실패 · 로그에 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationAiAdapter implements ProductDraftGenerationAiPort {

    public static final String FEATURE = "product-draft";

    private final AiClient aiClient;
    private final PromptTemplates templates;

    @Override
    public ProductDraftGenerationCandidate generate(String factsJson, String imagesJson, List<ProductDraftGenerationImageQueryResult> images,
                                                    long jobId, int attempt) {
        try {
            List<AiImage> attached = images.stream().map(image -> new AiImage(image.mediaType(), image.bytes())).toList();
            var request = templates.get(FEATURE).render(PromptValues.create().put("factsJson", factsJson).put("imagesJson", imagesJson))
                .withImages(attached).withCallerRef("job:" + jobId + ":" + attempt);
            return aiClient.complete(request, ProductDraftGenerationCandidate.class).value();
        } catch (AiException exception) {
            // 고정 코드만 낸다. provider errorType · feature 문구도 불신 텍스트일 수 있다
            if (exception.reason() == AiException.Reason.BUDGET_EXCEEDED) {
                throw new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_AI_BUDGET_EXCEEDED, false);
            }
            throw new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_AI_FAILED, exception.reason().retryable());
        } catch (IllegalArgumentException exception) {
            throw new ProductDraftGenerationRemoteFailure(Code.PRODUCT_DRAFT_AI_INVALID, false);
        }
    }
}
