package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftBatchItemInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftBatchLimits;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사이즈표 초안 일괄 제출의 배치 구조 검사와 항목 결과 판정 (#737). 단건 입력 · 템플릿 검사는 항목마다 단건 경로가 한다.
 *
 * <p>항목 오류 코드는 단건 API 와 같은 체계다 — 사이즈표 · 상품 업무 예외는 {@link SizeChartDraftTransportErrorCode#ofBusinessFailure} 로 바꾸고,
 * 재시도 가능 여부는 바꾸기 전 원래 코드로 판단한다.
 */
@Slf4j
@Component
public class SizeChartDraftBatchProcessor {

    public void validate(List<SizeChartDraftCreateCommand> commands) {
        if (commands == null || commands.isEmpty() || commands.size() > SizeChartDraftBatchLimits.MAX_ITEMS) {
            throw invalid();
        }
        Set<Long> products = new HashSet<>();
        Set<UUID> keys = new HashSet<>();
        Long actor = null;
        for (var command : commands) {
            if (command == null || command.productId() <= 0 || command.actorId() <= 0 || command.idempotencyKey() == null
                || !products.add(command.productId()) || !keys.add(command.idempotencyKey())
                || actor != null && actor.longValue() != command.actorId()) {
                throw invalid();
            }
            actor = command.actorId();
        }
    }

    /** 작업 연결까지 확인된 항목. */
    public SizeChartDraftBatchItemInfo accepted(SizeChartDraftCreateCommand command, SizeChartDraftInfo info) {
        return new SizeChartDraftBatchItemInfo(command.productId(), command.idempotencyKey(), info.draftId(), info.jobId(), null, false);
    }

    /**
     * 실패한 항목. 원문 메시지 · 원격 진단은 결과에 싣지 않는다.
     * 등록 여부가 불명확한 실패({@link SizeChartDraftUnavailableException})만 복구용 draftId 를 남긴다.
     */
    public SizeChartDraftBatchItemInfo failed(SizeChartDraftCreateCommand command, RuntimeException exception) {
        if (exception instanceof SizeChartDraftTransportException transport) {
            var code = transport.getErrorCode();
            Long draftId = transport instanceof SizeChartDraftUnavailableException unavailable ? unavailable.getDraftId() : null;
            return failure(command, draftId, code, code.getHttpStatus().is5xxServerError());
        }
        if (exception instanceof SizeChartException chart) {
            var original = chart.getErrorCode();
            // 다른 사이즈표 변경과 겹친 것(072 · 073)은 잠시 뒤 같은 키로 다시 보내면 성공할 수 있다.
            boolean retryable = original.getHttpStatus().is5xxServerError()
                || original == SizeChartErrorCode.SIZE_CHART_BUSY || original == SizeChartErrorCode.CHART_CONFLICT;
            return failure(command, null, SizeChartDraftTransportErrorCode.ofBusinessFailure(original.getHttpStatus()), retryable);
        }
        // 다른 컨텍스트(상품)의 예외는 ExceptionHandler 에서만 다룬다(H8). 여기서는 예상하지 못한 오류와 같이 본다.
        // 예상하지 못한 항목 오류는 격리하고 재시도 가능으로 둔다. 로그에는 상품 아이디와 예외 타입 · 프레임만 남긴다.
        log.warn("size chart draft batch item failed. productId={}", command.productId(), RedactedThrowable.of(exception));
        return failure(command, null, SizeChartDraftTransportErrorCode.BATCH_ITEM_UNAVAILABLE, true);
    }

    private static SizeChartDraftBatchItemInfo failure(SizeChartDraftCreateCommand command, Long draftId,
        SizeChartDraftTransportErrorCode code, boolean retryable) {
        return new SizeChartDraftBatchItemInfo(command.productId(), command.idempotencyKey(), draftId, null, code.getCode(), retryable);
    }

    private SizeChartDraftTransportException invalid() {
        return new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_BATCH);
    }
}
