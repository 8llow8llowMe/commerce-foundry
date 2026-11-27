package com.commercefoundry.domainlayer.legaldraftgeneration.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.OperationsAdminAuditProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobContextReadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response.LegalDraftGenerationResponse;
import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.presenter.LegalDraftGenerationAdminPresenter;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.command.LegalDraftRegisterCommand;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftErrorCode;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in.LegalDraftGenerationAdminUseCase;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftGenerationGate;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftRegistrationProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftResultProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftTemplateProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftInput;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션을 Facade 메서드에 걸지 않는다. 작업 등록과 영수증 INSERT 를 한 쓰기 트랜잭션으로 묶고, PostgreSQL 유니크 경합에서 진 쪽은 그 트랜잭션이
 * 완전히 롤백된 뒤 새 읽기 트랜잭션에서 승자의 입력과 비교해야 해서다(관례: SizeChartGenerationInternalFacade).
 * 작업 엔진은 그 컨텍스트의 Processor 로만 부르고, 돌려받은 domain(Job)만 자기 Processor 에 넘긴다.
 */
@Service
public class LegalDraftGenerationAdminFacade implements LegalDraftGenerationAdminUseCase {
    private static final int CANCEL_ATTEMPTS = 3;
    private final LegalDraftGenerationGate gate;
    private final LegalDraftTemplateProcessor templates;
    private final LegalDraftRegistrationProcessor registration;
    private final LegalDraftResultProcessor results;
    private final JobRegistrationProcessor jobRegistration;
    private final JobContextReadProcessor jobReads;
    private final JobRunProcessor jobRuns;
    private final OperationsAdminAuditProcessor audit;
    private final LegalDraftGenerationAdminPresenter presenter;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public LegalDraftGenerationAdminFacade(
        LegalDraftGenerationGate gate, LegalDraftTemplateProcessor templates, LegalDraftRegistrationProcessor registration,
        LegalDraftResultProcessor results, JobRegistrationProcessor jobRegistration, JobContextReadProcessor jobReads, JobRunProcessor jobRuns,
        OperationsAdminAuditProcessor audit, LegalDraftGenerationAdminPresenter presenter, PlatformTransactionManager transactionManager
    ) {
        this.gate = gate;
        this.templates = templates;
        this.registration = registration;
        this.results = results;
        this.jobRegistration = jobRegistration;
        this.jobReads = jobReads;
        this.jobRuns = jobRuns;
        this.audit = audit;
        this.presenter = presenter;
        write = template(transactionManager, false);
        read = template(transactionManager, true);
    }

    private static TransactionTemplate template(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(readOnly);
        return template;
    }

    @Override
    public LegalDraftGenerationResponse.Accepted register(LegalDraftRegisterCommand command) {
        Long jobId = null;
        try {
            jobId = registerDraft(command);
            return presenter.accepted(jobId);
        } finally {
            audit.legalDraftGenerationRegistered(command.memberId(), jobId, jobId != null);
        }
    }

    private long registerDraft(LegalDraftRegisterCommand command) {
        long memberId = command.memberId();
        LegalDraftInput input = registration.input(command);
        String hash = registration.keyHash(memberId, command.requestKey());
        var existing = read.execute(status -> registration.replay(hash, input));
        if (existing.isPresent()) {
            return existing.get().jobId();
        }
        if (!gate.enabled()) {
            throw new LegalDraftException(LegalDraftErrorCode.DISABLED);
        }
        templates.prepare(input);
        try {
            return write.execute(status -> {
                Job job = jobRegistration.register(registration.jobRequest(memberId)).job();
                return registration.record(memberId, hash, input, job);
            }).jobId();
        } catch (DataIntegrityViolationException exception) {
            // PostgreSQL 제약 위반은 쓰기 트랜잭션(작업 · 발행 신호 · 영수증)이 모두 롤백된 뒤 새 트랜잭션에서 승자의 입력과 비교한다.
            return read.execute(status -> registration.replay(hash, input)).orElseThrow(() -> exception).jobId();
        }
    }

    @Override
    public LegalDraftGenerationResponse get(long memberId, long jobId) {
        return presenter.toResponse(read.execute(status -> {
            var receipt = results.requireOwner(memberId, jobId);
            return results.result(receipt, jobReads.findById(receipt.jobId()).orElse(null));
        }));
    }

    @Override
    public LegalDraftGenerationResponse review(long memberId, long jobId) {
        boolean succeeded = false;
        try {
            LegalDraftInfo info = write.execute(status -> {
                var receipt = results.requireOwner(memberId, jobId);
                return results.review(receipt, jobReads.findById(receipt.jobId()).orElse(null));
            });
            succeeded = true;
            return presenter.toResponse(info);
        } finally {
            audit.legalDraftGenerationReviewed(memberId, jobId, succeeded);
        }
    }

    @Override
    public LegalDraftGenerationResponse cancel(long memberId, long jobId) {
        boolean succeeded = false;
        try {
            var response = cancelDraft(memberId, jobId);
            succeeded = true;
            return response;
        } finally {
            audit.legalDraftGenerationCancelled(memberId, jobId, succeeded);
        }
    }

    private LegalDraftGenerationResponse cancelDraft(long memberId, long jobId) {
        var receipt = read.execute(status -> results.requireOwner(memberId, jobId));
        for (int attempt = 1; attempt <= CANCEL_ATTEMPTS; attempt++) {
            try {
                jobRuns.requestCancel(receipt.jobId(), memberId);
                break;
            } catch (RuntimeException exception) {
                // 작업 엔진 예외 타입은 ExceptionHandler 에서만 번역한다(H8). 대신 작업을 다시 읽어 판정한다. 이미 끝났으면 현재 상태를 돌려주고,
                // 아직 진행 중이면 상태 경합으로 보고 다시 시도한다. 작업이 없거나 마지막 시도면 원래 예외를 그대로 던진다.
                Job job = read.execute(status -> jobReads.findById(receipt.jobId()).orElse(null));
                if (job == null || attempt == CANCEL_ATTEMPTS && job.status().isActive()) {
                    throw exception;
                }
                if (job.status().isFinished()) {
                    break;
                }
            }
        }
        return get(memberId, jobId);
    }
}
