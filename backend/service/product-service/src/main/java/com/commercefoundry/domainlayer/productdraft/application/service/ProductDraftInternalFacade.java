package com.commercefoundry.domainlayer.productdraft.application.service;

import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.dto.response.ProductDraftGenerationInputResponse;
import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.presenter.ProductDraftInternalPresenter;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.in.ProductDraftInternalUseCase;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.global.properties.ProductDraftProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * operations 생성 작업 전용 입력 (#359 PR-2a, 선례: {@code SizeChartDraftInternalFacade}). 이미지 바이트 읽기는 스토리지 I/O 라 Facade 에 트랜잭션을 걸지 않는다 —
 * 초안 판정은 Processor 의 짧은 읽기 트랜잭션, 바이트는 그 밖에서 읽는다(스토리지 I/O 동안 DB 커넥션을 쥐지 않는다).
 * 기능을 닫으면 이미 만든 초안의 입력 · 바이트도 내보내지 않는다(중단 스위치).
 */
@Service
@RequiredArgsConstructor
public class ProductDraftInternalFacade implements ProductDraftInternalUseCase {

    private final ProductDraftProcessor productDraftProcessor;
    private final ProductDraftImageProcessor productDraftImageProcessor;
    private final ProductDraftInternalPresenter productDraftInternalPresenter;
    private final ProductDraftProperties productDraftProperties;

    @Override
    public ProductDraftGenerationInputResponse generationInput(long draftId) {
        requireEnabled();
        return productDraftInternalPresenter.toGenerationInput(productDraftProcessor.generationDraft(draftId));
    }

    @Override
    public byte[] generationImage(long draftId, int index) {
        requireEnabled();
        return productDraftImageProcessor.generationBytes(productDraftProcessor.generationImage(draftId, index).source());
    }

    private void requireEnabled() {
        if (!productDraftProperties.generationEnabled()) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_DISABLED);
        }
    }
}
