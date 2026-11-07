package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationRegistration;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartGenerationResult;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartGenerationPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.query.SizeChartGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
// Suspend callers as well: remote registration/result I/O must never hold a DB transaction.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class SizeChartDraftRemoteProcessor {
    private final SizeChartGenerationPort generation;

    public SizeChartGenerationRegistration register(SizeChartDraft draft) {
        SizeChartGenerationRegistrationQueryResult registration;
        try {
            registration = generation.register(draft);
            if (registration == null || registration.draftId() != draft.id() || registration.jobId() <= 0
                || !draft.input().inputHash().equals(registration.inputHash())) {
                throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
        } catch (SizeChartDraftTransportException exception) {
            if (exception.getErrorCode() == SizeChartDraftTransportErrorCode.INPUT_CONFLICT) {
                throw exception;
            }
            throw new SizeChartDraftUnavailableException(draft.id(), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw new SizeChartDraftUnavailableException(draft.id(), SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE);
        }
        return new SizeChartGenerationRegistration(registration.draftId(), registration.jobId(), registration.inputHash());
    }

    public SizeChartGenerationResult result(SizeChartDraft draft) {
        try {
            var result = generation.result(draft);
            if (result == null) {
                throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            }
            return new SizeChartGenerationResult(result.draftId(), result.jobId(), result.inputHash(), result.status(), result.failureCode(), result.resultJson());
        } catch (SizeChartDraftTransportException exception) {
            throw exception; // Preserve expiry and contract failures.
        } catch (RuntimeException exception) {
            throw new SizeChartDraftUnavailableException(draft.id(), SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE);
        }
    }
}
