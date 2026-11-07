package com.commercefoundry.domainlayer.sizechartgeneration.application.service;

import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationRegistrationResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.dto.response.SizeChartGenerationResultResponse;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.in.internal.presenter.SizeChartGenerationInternalPresenter;
import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.in.SizeChartGenerationInternalUseCase;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationReceiptProcessor;
import com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor.SizeChartGenerationResultProcessor;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** No surrounding transaction: unique losers must fully roll back job/outbox/receipt before replaying in a fresh transaction. */
@Service
public class SizeChartGenerationInternalFacade implements SizeChartGenerationInternalUseCase {
    private final JobRegistrationProcessor jobRegistrationProcessor;
    private final JobContextReadProcessor jobContextReadProcessor;
    private final SizeChartGenerationReceiptProcessor receiptProcessor;
    private final SizeChartGenerationResultProcessor resultProcessor;
    private final SizeChartGenerationInternalPresenter presenter;
    private final boolean enabled;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public SizeChartGenerationInternalFacade(
        JobRegistrationProcessor jobRegistrationProcessor, JobContextReadProcessor jobContextReadProcessor,
        SizeChartGenerationReceiptProcessor receiptProcessor,
        SizeChartGenerationResultProcessor resultProcessor, SizeChartGenerationInternalPresenter presenter,
        PlatformTransactionManager transactionManager, @Value("${operations.size-chart-generation.enabled:false}") boolean enabled
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
    public SizeChartGenerationRegistrationResponse register(SizeChartGenerationRegisterCommand command) {
        try {
            SizeChartGenerationRegistrationInfo info;
            try {
                info = write.execute(status -> receiptProcessor.replay(command).orElseGet(() -> {
                    if (!enabled) {
                        throw new SizeChartGenerationException(SizeChartGenerationErrorCode.UNAVAILABLE);
                    }
                    var registration = jobRegistrationProcessor.register(receiptProcessor.request(command));
                    return receiptProcessor.record(command, registration.job());
                }));
            } catch (SizeChartGenerationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // Includes the job engine's registration conflict and persistence/commit failure. The failed
                // transaction has exited completely. Only a committed receipt can prove safe replay; otherwise 503.
                // This also keeps engine exception types out of this feature's application contract.
                info = replayWinner(command);
            }
            return presenter.registration(info);
        } catch (DataAccessException | TransactionException exception) {
            throw new SizeChartGenerationException(SizeChartGenerationErrorCode.UNAVAILABLE);
        }
    }

    private SizeChartGenerationRegistrationInfo replayWinner(SizeChartGenerationRegisterCommand command) {
        Optional<SizeChartGenerationRegistrationInfo> winner = read.execute(status -> receiptProcessor.replay(command));
        return winner.orElseThrow(() -> new SizeChartGenerationException(SizeChartGenerationErrorCode.UNAVAILABLE));
    }

    @Override
    public SizeChartGenerationResultResponse result(long jobId) {
        try {
            // Both context reads share this bounded read transaction; only the job domain crosses H8.
            return presenter.result(read.execute(status ->
                resultProcessor.result(jobId, jobContextReadProcessor.findById(jobId).orElse(null))));
        } catch (DataAccessException | TransactionException exception) {
            throw new SizeChartGenerationException(SizeChartGenerationErrorCode.RESULT_UNAVAILABLE);
        }
    }
}
