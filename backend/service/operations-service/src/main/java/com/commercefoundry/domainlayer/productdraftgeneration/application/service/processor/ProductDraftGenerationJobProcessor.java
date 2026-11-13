package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationAiPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.security.principal.AdminPermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 상품 초안 생성 작업 {@code PRODUCT_DRAFT_GENERATE} (#359 PR-2b, 선례: {@code SizeChartGenerationJobProcessor}).
 * product 가 고정한 입력 · 이미지 바이트를 internal 로 읽고 AI 제안을 검증해 결과 JSON 으로 낸다. DB 에 쓰지 않는다 — 결과 반영은 product(PR-3)가 한다.
 *
 * <p>등록은 아직 없다(2b-2 의 product 전용 internal 등록). 이 PR 만으로는 작업이 생기지 않는다.
 * <b>시도 상한 2(D9)</b> — AI 타임아웃은 이미 과금됐을 수 있어(ai-core {@code AiException.Reason#UNAVAILABLE}) 상한을 낮게 둔다. 사용량 원장은 시도마다
 * {@code job:<jobId>:<attempt>} 로 따로 남는다. 핸들러는 부수 효과가 없어 같은 시도를 다시 실행해도 결과가 하나다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationJobProcessor implements JobHandler {

    public static final String TYPE = "PRODUCT_DRAFT_GENERATE";

    private final ProductDraftGenerationInputPort inputPort;
    private final ProductDraftGenerationAiPort aiPort;
    private final ProductDraftGenerationValidationProcessor validation;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public AdminPermission permission() {
        return AdminPermission.CATALOG_READ;
    }

    @Override
    public String displayName() {
        return "AI 상품 초안 만들기";
    }

    @Override
    public String failureNotice(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "PRODUCT_DRAFT_IMAGE_CHANGED" -> "초안 이미지가 바뀌어 만들지 못했습니다. 새 초안을 만들어 주세요.";
            case "PRODUCT_DRAFT_IMAGES_TOO_LARGE" -> "이미지 용량 합계가 너무 커서 만들지 못했습니다. 이미지 수나 용량을 줄여 새 초안을 만들어 주세요.";
            case "PRODUCT_DRAFT_INPUT_EXPIRED" -> "보관 기간이 지난 초안이라 만들지 못했습니다. 새 초안을 만들어 주세요.";
            case "PRODUCT_DRAFT_AI_BUDGET_EXCEEDED" -> "이번 달 AI 사용 한도를 넘어 만들지 못했습니다. 한도를 확인한 뒤 다시 시도해 주세요.";
            case "PRODUCT_DRAFT_INPUT_UNAVAILABLE", "PRODUCT_DRAFT_AI_FAILED" -> "AI 상품 초안을 만들지 못했습니다. 잠시 뒤 다시 시도해 주세요.";
            default -> "AI 상품 초안을 만들지 못했습니다. 입력한 내용과 이미지를 확인한 뒤 새 초안을 만들어 주세요.";
        };
    }

    @Override
    public JobHandlerOptions options() {
        return new JobHandlerOptions(null, 2, null, 1);
    }

    @Override
    public JobOutcome execute(JobExecution execution) {
        try {
            // product · AI 호출은 DB 트랜잭션 밖이다. checkpoint 가 취소 · lease 상실 · 실행 시간 초과를 알린다(JobCancelledException 은 잡지 않는다)
            long draftId = validation.payload(execution.payloadJson());
            execution.checkpoint(5, "상품 초안 입력을 확인하는 중");
            var input = inputPort.getGenerationInput(draftId);
            execution.checkpoint(15, "상품 초안 입력을 읽었습니다");
            var prepared = validation.input(draftId, input);
            List<ProductDraftGenerationImageQueryResult> images = List.of();
            if (!prepared.images().isEmpty()) {
                execution.checkpoint(20, "상품 이미지를 읽는 중");
                images = validation.images(prepared, inputPort.getGenerationImages(draftId, prepared.images()));
            }
            execution.checkpoint(35,"AI 상품 초안을 만드는 중");
            var candidate = aiPort.generate(prepared.factsJson(), prepared.imagesJson(), images, execution.jobId(), execution.attempt());
            execution.checkpoint(85, "AI 상품 초안을 확인하는 중");
            String json = validation.outcome(candidate, prepared);
            execution.checkpoint(100, "AI 상품 초안을 만들었습니다");
            return new JobOutcome(json);
        } catch (ProductDraftGenerationRemoteFailure failure) {
            // 허용된 경계에서 코드만 옮긴다 — 원인 · 원격 본문을 워커 로그로 넘기지 않는다
            throw new JobExecutionException(failure.getErrorCode(), failure.isRetryable());
        }
    }
}
