package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo.Candidate;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftReceiptPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftReceipt;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class LegalDraftResultProcessor {
    private final LegalDraftReceiptPort receipts;
    private final JobContextReadProcessor jobs;
    private final LegalDraftTemplateProcessor templates;
    private final LegalDraftJobProcessor handler;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public LegalDraftInfo get(long memberId, long jobId) {
        return toInfo(requireOwner(memberId, jobId));
    }

    @Transactional
    public LegalDraftInfo review(long memberId, long jobId) {
        LegalDraftInfo result = toInfo(requireOwner(memberId, jobId));
        if (result.candidate() == null) {
            throw new LegalDraftException(LegalDraftErrorCode.REVIEW_UNAVAILABLE);
        }
        if (result.reviewedAt() != null) {
            return result;
        }
        receipts.markReviewed(jobId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return toInfo(requireOwner(memberId, jobId));
    }

    @Transactional(readOnly = true)
    public LegalDraftReceipt requireOwner(long memberId, long jobId) {
        return receipts.findByJobId(jobId).filter(receipt -> receipt.memberId() == memberId)
            .orElseThrow(() -> new LegalDraftException(LegalDraftErrorCode.NOT_FOUND));
    }

    private LegalDraftInfo toInfo(LegalDraftReceipt receipt) {
        var job = jobs.findById(receipt.jobId())
            .filter(entry -> LegalDraftJobProcessor.TYPE.equals(entry.type()) && Objects.equals(entry.requestedByMemberId(), receipt.memberId()))
            .orElseThrow(() -> new LegalDraftException(LegalDraftErrorCode.NOT_FOUND));
        Candidate candidate = job.status() == JobStatus.SUCCEEDED ? candidate(receipt, job.resultJson()) : null;
        String notice = job.cancelRequested() && job.status().isActive() ? "취소를 요청했습니다." : null;
        if (job.status() == JobStatus.FAILED) {
            notice = handler.failureNotice(job.errorCode());
            if (notice == null && "RUN_TIMEOUT".equals(job.errorCode())) {
                notice = "작업 실행 시간이 초과되었습니다.";
            }
            if (notice == null) {
                notice = "생성 작업을 끝내지 못했습니다.";
            }
        }
        return new LegalDraftInfo(job.id(), job.status(), notice, candidate, receipt.reviewedAt());
    }

    private Candidate candidate(LegalDraftReceipt receipt, String json) {
        if (json == null || json.length() > 2048) {
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        }
        try {
            var plan = mapper.readerFor(LegalDraftPlan.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<LegalDraftPlan>readValue(json);
            return templates.assemble(receipt.input(), templates.prepare(receipt.input()), plan);
        } catch (JacksonException | IllegalArgumentException | LegalDraftException exception) {
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        }
    }
}
