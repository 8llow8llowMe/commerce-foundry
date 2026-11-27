package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.exception.LegalDraftException;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.model.LegalDraftRemoteFailure;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftAiPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out.LegalDraftReceiptPort;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import com.commercefoundry.security.principal.AdminPermission;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class LegalDraftJobProcessor implements JobHandler {
    public static final String TYPE = "LEGAL_DRAFT_GENERATION";
    private final LegalDraftReceiptPort receipts;
    private final LegalDraftTemplateProcessor templates;
    private final LegalDraftAiPort ai;
    private final LegalDraftGenerationGate gate;
    private final ObjectMapper mapper;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.SETTINGS_READ;
    }

    @Override
    public String displayName() {
        return "법률 문서 검토용 초안 생성";
    }

    @Override
    public JobHandlerOptions options() {
        // 원격 호출 결과가 불명확한 경우 작업 엔진에서 재호출하지 않는다. ai-core 자체 재시도/계량 정책은 적용된다.
        return new JobHandlerOptions(null, 1, Duration.ofMinutes(15), 1);
    }

    @Override
    public String failureNotice(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "LEGAL_DRAFT_DISABLED" -> "법률 문서 AI 생성이 꺼져 있습니다.";
            case "LEGAL_DRAFT_BUDGET_EXCEEDED" -> "이번 달 AI 사용 한도를 확인해 주세요.";
            case "LEGAL_DRAFT_AI_INVALID" -> "생성 결과가 검토용 형식과 맞지 않습니다.";
            case "LEGAL_DRAFT_AI_FAILED" -> "AI 응답을 받지 못했습니다. 사용량을 확인한 후 다시 요청해 주세요.";
            case "LEGAL_DRAFT_INPUT_UNAVAILABLE" -> "입력 또는 시작 템플릿을 확인해 주세요.";
            default -> null;
        };
    }

    @Override
    public JobOutcome execute(JobExecution execution) {
        if (!gate.enabled()) {
            throw new JobExecutionException("LEGAL_DRAFT_DISABLED", false);
        }
        var input = receipts.findByJobId(execution.jobId())
            .orElseThrow(() -> new JobExecutionException("LEGAL_DRAFT_INPUT_UNAVAILABLE", false)).input();
        LegalDraftTemplateProcessor.Outline outline;
        try {
            outline = templates.prepare(input);
        } catch (LegalDraftException exception) {
            throw new JobExecutionException("LEGAL_DRAFT_INPUT_UNAVAILABLE", false);
        }
        execution.checkpoint(10, "검토용 초안을 구성하고 있습니다.");
        var confirmed = outline.sectionIds().stream().filter(input.confirmedFacts()::containsKey).toList();
        LegalDraftPlan plan;
        try {
            plan = ai.generate(input.type(), input.locale(), input.templateVersion(),
                outline.sectionIds(), confirmed, execution.jobId(), execution.attempt());
        } catch (LegalDraftRemoteFailure failure) {
            // 원격 실패 분류를 작업 엔진의 고정 실패 코드로 옮긴다. 원격 호출이 불명확할 수 있어 재시도하지 않는다.
            throw new JobExecutionException(failure.getErrorCode(), false);
        }
        execution.checkpoint(90, "검토용 형식을 확인하고 있습니다.");
        try {
            templates.assemble(input, outline, plan);
        } catch (IllegalArgumentException exception) {
            throw new JobExecutionException("LEGAL_DRAFT_AI_INVALID", false);
        }
        return new JobOutcome(mapper.writeValueAsString(plan));
    }
}
