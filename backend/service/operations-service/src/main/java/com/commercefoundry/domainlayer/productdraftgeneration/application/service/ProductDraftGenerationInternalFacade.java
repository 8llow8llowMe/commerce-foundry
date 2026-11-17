package com.commercefoundry.domainlayer.productdraftgeneration.application.service;

import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.dto.response.ProductDraftGenerationResultResponse;
import com.commercefoundry.domainlayer.productdraftgeneration.adapter.in.internal.presenter.ProductDraftGenerationInternalPresenter;
import com.commercefoundry.domainlayer.productdraftgeneration.application.command.ProductDraftGenerationRegisterCommand;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationErrorCode;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.exception.ProductDraftGenerationRegistrationRaceException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.info.ProductDraftGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.in.ProductDraftGenerationInternalUseCase;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationResultProcessor;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * product 전용 상품 초안 생성 등록 · 결과 조회 (#359 PR-2b-2, 선례: {@code SizeChartGenerationInternalFacade}).
 * 감싸는 트랜잭션을 두지 않는다 — 동시 등록에서 진 쪽은 작업 · outbox · 영수증을 모두 롤백한 뒤 새 트랜잭션에서 이긴 영수증을 재생해야 한다.
 * 플래그 {@code operations.product-draft-generation.enabled} 가 닫혀 있으면 신규 등록만 막고, 기존 영수증 재생과 결과 조회는 유지한다.
 */
@Service
public class ProductDraftGenerationInternalFacade implements ProductDraftGenerationInternalUseCase {
    private final JobRegistrationProcessor jobRegistrationProcessor;
    private final JobContextReadProcessor jobContextReadProcessor;
    private final ProductDraftGenerationReceiptProcessor receiptProcessor;
    private final ProductDraftGenerationResultProcessor resultProcessor;
    private final ProductDraftGenerationInternalPresenter presenter;
    private final boolean enabled;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public ProductDraftGenerationInternalFacade(
        JobRegistrationProcessor jobRegistrationProcessor, JobContextReadProcessor jobContextReadProcessor,
        ProductDraftGenerationReceiptProcessor receiptProcessor,
        ProductDraftGenerationResultProcessor resultProcessor, ProductDraftGenerationInternalPresenter presenter,
        PlatformTransactionManager transactionManager, @Value("${operations.product-draft-generation.enabled:false}") boolean enabled
    ) {
        this.jobRegistrationProcessor = jobRegistrationProcessor;
        this.jobContextReadProcessor = jobContextReadProcessor;
        this.receiptProcessor = receiptProcessor;
        this.resultProcessor = resultProcessor;
        this.presenter = presenter;
        this.enabled = enabled;
        write = template(transactionManager, false);
        read = template(transactionManager, true);
    }

    private static TransactionTemplate template(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(5);
        template.setReadOnly(readOnly);
        return template;
    }

    @Override
    public ProductDraftGenerationRegistrationResponse register(ProductDraftGenerationRegisterCommand command) {
        try {
            ProductDraftGenerationRegistrationInfo info;
            try {
                info = write.execute(status -> receiptProcessor.replay(command).orElseGet(() -> {
                    if (!enabled) {
                        throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.UNAVAILABLE);
                    }
                    var registration = jobRegistrationProcessor.register(receiptProcessor.request(command));
                    return receiptProcessor.record(command, registration);
                }));
            } catch (ProductDraftGenerationException exception) {
                throw exception;
            } catch (ProductDraftGenerationRegistrationRaceException exception) {
                // 먼저 커밋된 다른 요청의 활성 작업을 받았다(경합 패배). 롤백된 뒤 이긴 영수증을 재생한다 — 입력이 다르면 409, 없으면 503.
                info = replayWinner(command);
            } catch (RuntimeException exception) {
                // 작업 엔진의 등록 충돌 · 영속성 · 커밋 실패를 모두 포함한다. 실패한 트랜잭션은 이미 끝났다.
                // 커밋된 영수증만 안전한 재생을 증명한다 — 없으면 503. 엔진 예외 타입을 이 기능의 계약 밖에 둔다.
                info = replayWinner(command);
            }
            return presenter.registration(info);
        } catch (DataAccessException | TransactionException exception) {
            throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.UNAVAILABLE);
        }
    }

    private ProductDraftGenerationRegistrationInfo replayWinner(ProductDraftGenerationRegisterCommand command) {
        Optional<ProductDraftGenerationRegistrationInfo> winner = read.execute(status -> receiptProcessor.replay(command));
        return winner.orElseThrow(() -> new ProductDraftGenerationException(ProductDraftGenerationErrorCode.UNAVAILABLE));
    }

    @Override
    public ProductDraftGenerationResultResponse result(long jobId) {
        try {
            // 두 컨텍스트 읽기가 같은 상한 읽기 트랜잭션을 쓴다. 컨텍스트를 넘는 것은 작업 domain 뿐이다(H8).
            return presenter.result(read.execute(status ->
                resultProcessor.result(jobId, jobContextReadProcessor.findById(jobId).orElse(null))));
        } catch (DataAccessException | TransactionException exception) {
            throw new ProductDraftGenerationException(ProductDraftGenerationErrorCode.RESULT_UNAVAILABLE);
        }
    }
}
