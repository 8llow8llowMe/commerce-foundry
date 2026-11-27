package com.commercefoundry.domainlayer.legaldraftgeneration.application.service;

import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in.LegalDraftGenerationUseCase;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftAuditProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftGenerationGate;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftRegistrationProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftResultProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftTemplateProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftInput;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LegalDraftGenerationFacade implements LegalDraftGenerationUseCase {
    private final LegalDraftGenerationGate gate;
    private final LegalDraftTemplateProcessor templates;
    private final LegalDraftRegistrationProcessor registration;
    private final LegalDraftResultProcessor results;
    private final JobRunProcessor jobs;
    private final LegalDraftAuditProcessor audit;

    @Override
    public long register(long memberId, String requestKey, String type, String locale, int templateVersion, Map<String, String> facts) {
        return audit.record(LegalDraftAuditProcessor.REGISTER, memberId, null,
            () -> registerDraft(memberId, requestKey, toInput(type, locale, templateVersion, facts)), Long::longValue);
    }

    private long registerDraft(long memberId, String requestKey, LegalDraftInput input) {
        String hash = registration.keyHash(memberId, requestKey);
        var existing = registration.replay(hash, input);
        if (existing.isPresent()) {
            return existing.get().jobId();
        }
        if (!gate.enabled()) {
            throw new LegalDraftException(LegalDraftErrorCode.DISABLED);
        }
        templates.prepare(input);
        try {
            return registration.insert(memberId, hash, input).jobId();
        } catch (DataIntegrityViolationException exception) {
            // PostgreSQL 제약 위반은 쓰기 트랜잭션이 끝난 뒤 새 트랜잭션에서 승자의 입력과 비교한다.
            return registration.replay(hash, input).orElseThrow(() -> exception).jobId();
        }
    }

    @Override
    public LegalDraftInfo get(long memberId, long jobId) {
        return results.get(memberId, jobId);
    }

    @Override
    public LegalDraftInfo review(long memberId, long jobId) {
        return audit.record(LegalDraftAuditProcessor.REVIEW, memberId, jobId, () -> results.review(memberId, jobId), LegalDraftInfo::jobId);
    }

    @Override
    public LegalDraftInfo cancel(long memberId, long jobId) {
        return audit.record(LegalDraftAuditProcessor.CANCEL, memberId, jobId, () -> cancelDraft(memberId, jobId), LegalDraftInfo::jobId);
    }

    private LegalDraftInfo cancelDraft(long memberId, long jobId) {
        var receipt = results.requireOwner(memberId, jobId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                jobs.requestCancel(receipt.jobId(), memberId);
                break;
            } catch (JobException exception) {
                if (exception.getErrorCode() == JobErrorCode.JOB_ALREADY_FINISHED) {
                    break;
                }
                if (exception.getErrorCode() != JobErrorCode.JOB_CONFLICT || attempt == 3) {
                    throw exception;
                }
            }
        }
        return results.get(memberId, jobId);
    }

    private LegalDraftInput toInput(String type, String locale, int version, Map<String, String> facts) {
        try {
            if (type == null) {
                throw new IllegalArgumentException("Type is required");
            }
            return new LegalDraftInput(new LegalDocumentKey(LegalDocumentKey.Type.valueOf(type), locale), version, facts);
        } catch (IllegalArgumentException exception) {
            throw new LegalDraftException(LegalDraftErrorCode.INVALID_REQUEST);
        }
    }
}
