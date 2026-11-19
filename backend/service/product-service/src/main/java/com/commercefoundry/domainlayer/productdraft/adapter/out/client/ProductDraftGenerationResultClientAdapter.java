package com.commercefoundry.domainlayer.productdraft.adapter.out.client;

import com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.ProductDraftGenerationClient;
import com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationResultResponseSupport;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationResultQueryPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 이미 등록한 작업의 결과만 조회한다. 등록이나 AI 호출을 일으키지 않는다. */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationResultClientAdapter implements ProductDraftGenerationResultQueryPort {

    private final ProductDraftGenerationClient client;
    private final ProductDraftGenerationResultResponseSupport support;

    @Override
    public ProductDraftGenerationResultQueryResult result(ProductDraft draft) {
        Long jobId = draft.generation().jobId();
        if (jobId == null || jobId <= 0) {
            throw new IllegalArgumentException("생성 작업이 연결된 초안만 결과를 조회할 수 있습니다.");
        }
        return support.result(draft, () -> client.result(jobId));
    }
}
