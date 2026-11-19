package com.commercefoundry.domainlayer.productdraft.application.model;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;

/** 작업 조회 시점에 고정한 초안 회차와 반영할 결과다. 진행 중이면 두 결과 칸이 비어 있다. */
public record ProductDraftGenerationCompletion(long draftId, ProductDraftGeneration generation, ProductDraftCandidate candidate, String failureCode) {

    public boolean matches(ProductDraft draft) {
        return draftId == draft.id() && generation.equals(draft.generation());
    }

    public boolean pending() {
        return candidate == null && failureCode == null;
    }
}
