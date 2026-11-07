package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobRequest;
import com.commercefoundry.domainlayer.sizechartgeneration.application.command.SizeChartGenerationRegisterCommand;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationErrorCode;
import com.commercefoundry.domainlayer.sizechartgeneration.application.exception.SizeChartGenerationException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.info.SizeChartGenerationRegistrationInfo;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationReceiptPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationReceipt;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationReceiptProcessor {
    public static final String JOB_TYPE = "SIZE_CHART_GENERATION";
    private final SizeChartGenerationReceiptPort receiptPort;
    private final SizeChartGenerationResultProcessor resultProcessor;
    private final Clock clock;

    public Optional<SizeChartGenerationRegistrationInfo> replay(SizeChartGenerationRegisterCommand command) {
        return receiptPort.findByDraftId(command.draftId()).map(receipt -> {
            if (!receipt.inputHash().equals(command.inputHash()) || receipt.requestedByMemberId() != command.requestedByMemberId()
                || !receipt.expiresAt().truncatedTo(ChronoUnit.MICROS).equals(command.expiresAt())) {
                throw new SizeChartGenerationException(SizeChartGenerationErrorCode.INPUT_CONFLICT);
            }
            return new SizeChartGenerationRegistrationInfo(receipt.draftId(), receipt.jobId(), receipt.inputHash());
        });
    }

    public JobRequest request(SizeChartGenerationRegisterCommand command) {
        if (command.draftId() <= 0 || command.requestedByMemberId() <= 0 || command.expiresAt() == null
            || command.inputHash() == null || !command.inputHash().matches("[a-f0-9]{64}")) {
            throw new SizeChartGenerationException(SizeChartGenerationErrorCode.INVALID_REQUEST);
        }
        if (!clock.instant().isBefore(command.expiresAt())) {
            throw new SizeChartGenerationException(SizeChartGenerationErrorCode.EXPIRED);
        }
        String payload = "{\"draftId\":%d,\"inputHash\":\"%s\"}".formatted(command.draftId(), command.inputHash());
        return new JobRequest(JOB_TYPE, payload, "size-chart:" + command.draftId(), command.requestedByMemberId(), 0, null);
    }

    public SizeChartGenerationRegistrationInfo record(SizeChartGenerationRegisterCommand command, Job job) {
        var receipt = new SizeChartGenerationReceipt(
            command.draftId(), job.id(), command.inputHash(), command.requestedByMemberId(), command.expiresAt().truncatedTo(ChronoUnit.MICROS));
        resultProcessor.validateBinding(receipt, job.type(), job.payloadJson(), job.requestedByMemberId());
        receiptPort.insert(receipt);
        return new SizeChartGenerationRegistrationInfo(receipt.draftId(), receipt.jobId(), receipt.inputHash());
    }
}
