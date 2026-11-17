package com.commercefoundry.domainlayer.productdraft.adapter.out.client;

import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationClient;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto.ProductDraftGenerationRegisterRequest;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationInternalResponseSupport;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link ProductDraftGenerationPort} 의 Feign 구현. 요청 칸은 claim 때 저장한 회차 값 그대로다 — 어드민 · 시각을 다시 읽지 않는다. */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationClientAdapter implements ProductDraftGenerationPort {

    private final ProductDraftGenerationClient client;
    private final ProductDraftGenerationInternalResponseSupport support;

    @Override
    public ProductDraftGenerationRegistrationQueryResult register(ProductDraft draft) {
        ProductDraftGeneration generation = draft.generation();
        if (!draft.awaitingGenerationJob()) {
            throw new IllegalStateException("작업을 기다리는 생성 회차가 아닙니다. draftId=" + draft.id());
        }
        ProductDraftGenerationRegisterRequest request = new ProductDraftGenerationRegisterRequest(
            draft.id(), generation.generationNo(), generation.inputHash(), generation.requestedByMemberId(), draft.expiresAt()
        );
        return support.register(draft, () -> client.register(request));
    }
}
