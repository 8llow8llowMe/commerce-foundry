package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.Job;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 작업은 Facade 가 작업 엔진 Processor 로 읽어 domain 으로 넘긴다. 트랜잭션도 Facade 가 연다. */
@Component
@RequiredArgsConstructor
public class LegalDraftResultProcessor {
    static final String TEMPLATE_CHANGED_NOTICE = "시작 템플릿이 바뀌어 이전 결과를 다시 만들 수 없습니다.";
    private final LegalDraftReceiptPort receipts;
    private final LegalDraftTemplateProcessor templates;
    private final LegalDraftJobProcessor handler;
    private final ObjectMapper mapper;
    private final Clock clock;

    public LegalDraftReceipt requireOwner(long memberId, long jobId) {
        return receipts.findByJobId(jobId).filter(receipt -> receipt.memberId() == memberId)
            .orElseThrow(() -> new LegalDraftException(LegalDraftErrorCode.NOT_FOUND));
    }

    public LegalDraftInfo result(LegalDraftReceipt receipt, Job job) {
        if (job == null || !LegalDraftJobProcessor.TYPE.equals(job.type()) || !Objects.equals(job.requestedByMemberId(), receipt.memberId())) {
            throw new LegalDraftException(LegalDraftErrorCode.NOT_FOUND);
        }
        Candidate candidate = null;
        String notice = job.cancelRequested() && job.status().isActive() ? "취소를 요청했습니다." : null;
        if (job.status() == JobStatus.SUCCEEDED) {
            candidate = candidate(receipt, job.resultJson());
            if (candidate == null) {
                notice = TEMPLATE_CHANGED_NOTICE;
            }
        }
        if (job.status() == JobStatus.FAILED) {
            notice = handler.failureNotice(job.errorCode());
            if (notice == null && "RUN_TIMEOUT".equals(job.errorCode())) {
                notice = "작업 실행 시간이 초과되었습니다.";
            }
            if (notice == null) {
                notice = "생성 작업을 끝내지 못했습니다.";
            }
        }
        return new LegalDraftInfo(job.id(), job.status().toMetadata(), notice, candidate, receipt.reviewedAt());
    }

    /** 검토 확인은 최초 시각만 남긴다. 후보가 없으면(실패 · 진행 중 · 템플릿 변경) 거절한다. */
    public LegalDraftInfo review(LegalDraftReceipt receipt, Job job) {
        LegalDraftInfo result = result(receipt, job);
        if (result.candidate() == null) {
            throw new LegalDraftException(LegalDraftErrorCode.REVIEW_UNAVAILABLE);
        }
        if (result.reviewedAt() != null) {
            return result;
        }
        receipts.markReviewed(receipt.jobId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        return result(requireOwner(receipt.memberId(), receipt.jobId()), job);
    }

    private Candidate candidate(LegalDraftReceipt receipt, String json) {
        if (json == null || json.length() > 2048) {
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        }
        LegalDraftTemplateProcessor.Outline outline;
        try {
            outline = templates.prepare(receipt.input());
        } catch (LegalDraftException exception) {
            if (exception.getErrorCode() == LegalDraftErrorCode.TEMPLATE_CONFLICT) {
                // 배포로 시작 템플릿 버전이 올라가면 이전 버전 결과를 다시 조립할 수 없다. 사실값을 더 저장하지 않고 후보 없음으로 안내한다.
                return null;
            }
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        } catch (IllegalStateException exception) {
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        }
        try {
            var plan = mapper.readerFor(LegalDraftPlan.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<LegalDraftPlan>readValue(json);
            return templates.assemble(receipt.input(), outline, plan);
        } catch (JacksonException | IllegalArgumentException | LegalDraftException exception) {
            throw new LegalDraftException(LegalDraftErrorCode.RESULT_UNAVAILABLE);
        }
    }
}
