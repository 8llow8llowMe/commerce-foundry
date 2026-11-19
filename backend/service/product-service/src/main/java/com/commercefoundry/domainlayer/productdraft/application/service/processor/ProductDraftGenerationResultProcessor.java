package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.model.ProductDraftGenerationCompletion;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationResultQueryPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원격 결과 조회와 결과 저장의 트랜잭션 경계를 분리한다. */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationResultProcessor {

    private final ProductDraftGenerationResultQueryPort queryPort;
    private final ProductDraftRepositoryPort repositoryPort;
    private final Clock clock;

    public ProductDraftGenerationCompletion query(ProductDraft draft) {
        if (draft.generation().jobId() == null) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
        }
        var result = queryPort.result(draft);
        if (result == null || !result.matches(draft)) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
        }
        String failure = switch (result.status()) {
            case FAILED -> result.failureCode().name();
            case CANCELLED -> ProductDraft.GENERATION_CANCELLED;
            case EXPIRED -> ProductDraft.GENERATION_RESULT_EXPIRED;
            case PENDING, SUCCEEDED -> null;
        };
        return new ProductDraftGenerationCompletion(draft.id(), draft.generation(), result.candidate(), failure);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductDraft complete(ProductDraftGenerationCompletion result) {
        ProductDraft current = repositoryPort.findById(result.draftId())
            .orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));
        Instant now = clock.instant();
        ProductDraftStatus status = current.statusAt(now);
        if (status == ProductDraftStatus.EXPIRED) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
        }
        if (!result.matches(current)) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY);
        }
        // 원격 조회 도중 거절하거나, 다른 요청이 같은 회차를 먼저 확정했으면 현재 값을 반환한다.
        if (status != ProductDraftStatus.GENERATING || result.pending()) {
            return current;
        }
        var candidate = result.candidate() == null ? null : result.candidate().withWarningsFor(current.facts());
        ProductDraft completed = current.finishGeneration(now, candidate, result.failureCode());
        repositoryPort.updateGenerationResult(completed);
        return completed;
    }
}
