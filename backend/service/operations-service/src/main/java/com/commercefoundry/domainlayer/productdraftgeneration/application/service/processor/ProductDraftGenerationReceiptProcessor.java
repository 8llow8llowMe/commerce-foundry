package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRegistration;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationRegistrationRaceException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationReceiptPort;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationReceipt;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * (초안, 회차) 영수증의 재생 · 작업 요청 · 기록 (선례: {@code SizeChartGenerationReceiptProcessor}).
 * payload 는 2b-1 계약 {@code {"draftId": n}} 그대로다. 멱등 키에는 회차를 넣어 같은 초안의 다음 회차가 이전 회차의 활성 작업을 받지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationReceiptProcessor {
    private final ProductDraftGenerationReceiptPort receiptPort;
    private final ProductDraftGenerationResultProcessor resultProcessor;
    private final Clock clock;

    /** 같은 (초안, 회차) 영수증이 있으면 재생한다. 해시 · 요청자 · 만료 시각 중 하나라도 다르면 409 {@code INPUT_CONFLICT}. 만료 · 플래그와 무관하다. */
    public Optional<ProductDraftGenerationRegistrationInfo> replay(ProductDraftGenerationRegisterCommand command) {
        return receiptPort.findByDraftIdAndGenerationNo(command.draftId(), command.generationNo()).map(receipt -> {
            if (!receipt.inputHash().equals(command.inputHash()) || receipt.requestedByMemberId() != command.actorMemberId()
                || !receipt.expiresAt().truncatedTo(ChronoUnit.MICROS).equals(command.expiresAt())) {
                throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.INPUT_CONFLICT);
            }
            return info(receipt);
        });
    }

    public JobRequest request(ProductDraftGenerationRegisterCommand command) {
        if (command.draftId() <= 0 || command.generationNo() < ProductDraftGenerationReceipt.MIN_GENERATION_NO
            || command.generationNo() > ProductDraftGenerationReceipt.MAX_GENERATION_NO || command.actorMemberId() <= 0
            || command.expiresAt() == null || command.inputHash() == null || !command.inputHash().matches("[a-f0-9]{64}")) {
            throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.INVALID_REQUEST);
        }
        if (!clock.instant().isBefore(command.expiresAt())) {
            throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.EXPIRED);
        }
        String payload = "{\"draftId\":%d}".formatted(command.draftId());
        String dedupKey = "product-draft:%d:%d".formatted(command.draftId(), command.generationNo());
        return new JobRequest(ProductDraftGenerationJobProcessor.TYPE, payload, dedupKey, command.actorMemberId(), 0, null);
    }

    /**
     * 작업에 영수증을 붙인다. 바인딩(종류 · 요청자 · payload 초안)은 늘 검증한다 — 영수증 없는 활성 작업에 남의 영수증이 붙으면 안 된다.
     * 엔진이 같은 멱등 키의 기존 활성 작업을 돌려줬는데({@code created=false}) 결합되지 않으면 경합 패배다 —
     * {@link ProductDraftGenerationRegistrationRaceException} 으로 롤백시키고 Facade 가 이긴 영수증을 재생한다.
     */
    public ProductDraftGenerationRegistrationInfo record(ProductDraftGenerationRegisterCommand command, JobRegistration registration) {
        Job job = registration.job();
        var receipt = new ProductDraftGenerationReceipt(command.draftId(), command.generationNo(), job.id(), command.inputHash(),
            command.actorMemberId(), command.expiresAt().truncatedTo(ChronoUnit.MICROS));
        try {
            resultProcessor.validateBinding(receipt, job.type(), job.payloadJson(), job.requestedByMemberId());
        } catch (ProductDraftGenerationException exception) {
            if (!registration.created()) {
                throw new ProductDraftGenerationRegistrationRaceException();
            }
            throw exception;
        }
        receiptPort.insert(receipt);
        return info(receipt);
    }

    private static ProductDraftGenerationRegistrationInfo info(ProductDraftGenerationReceipt receipt) {
        return new ProductDraftGenerationRegistrationInfo(receipt.draftId(), receipt.generationNo(), receipt.jobId(), receipt.inputHash());
    }
}
