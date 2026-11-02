package com.commercefoundry.domainlayer.aiusage.adapter.in.metering;

import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiUsage;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiBudgetAlert;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ai-core 계량 SPI({@link AiCallInterceptor})를 AI 사용량 원장 · 월 한도에 잇는 진입 어댑터 (#215 B3 · #435, {@code bootstrap-conventions.md} AI 계량).
 * ai-core 의 {@code AiClient} 가 호출 앞뒤에 이 빈을 부르고, 이 빈은 {@link AiUsageMeterUseCase} 만 부른다 — 스케줄러 · 이벤트 리스너처럼 HTTP 가 아닌 진입이다.
 * 프로세스에 하나뿐이어야 한다 — {@code ai.enabled=true} 면 ai-core 기동 가드가 이 빈 정의가 정확히 하나인지 본다(그래서 {@code allow-unmetered} 없이 운영에서 켤 수 있다).
 *
 * <ul>
 *   <li>{@link #before}: {@code AiCallContext} 를 예약 커맨드로 옮겨 예상치만큼 월 한도를 예약한다. 한도 초과는 {@code BUDGET_EXCEEDED}(재시도 없음),
 *       그 밖의 실패(원장 장애 · {@code RESERVE_CONTENDED} · 설정 누락)는 {@code METERING_FAILED}(재시도)로 번역해 HTTP 요청 전에 막는다.</li>
 *   <li>{@link #after}: 결과 다섯 가지(성공 · 못 씀 · 오류 응답 · 타임아웃 · 보내지 못함)를 모두 정산한다. 실패는 여기서 타입만 남기고 삼킨다 —
 *       놓친 예약은 {@code AiUsageMaintenanceScheduler} 의 회수가 예상치로 닫는다.</li>
 * </ul>
 *
 * <p>원장에는 기능 이름 · {@code callerRef} · 수치만 간다 — {@code AiCallContext} · {@code AiCallOutcome} 에 원문이 없다. 로그도 기능 이름 · 원장 호출 아이디 ·
 * 금액(micro-USD) · 오류 코드 · 예외 타입만 남기고 예외 메시지를 싣지 않는다(원인 사슬에 SQL · 값이 실릴 수 있다).
 *
 * <p>정산은 인터럽트 플래그가 켜진 스레드에서 불릴 수 있다(자리 대기 중 인터럽트 → {@code NOT_SENT}). 이 어댑터는 플래그를 읽거나 지우지 않고,
 * 인터럽트에 반응하는 대기 · 락도 두지 않는다 — 플래그를 내렸다 되살리는 일은 {@link AiUsageMeterUseCase#settle} 가 트랜잭션을 열기 전후에 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageMeteringInterceptor implements AiCallInterceptor {

    private final AiUsageMeterUseCase aiUsageMeterUseCase;

    @Override
    public AiCallHandle before(AiCallContext context) {
        AiUsageReservation reservation;
        try {
            reservation = aiUsageMeterUseCase.reserve(new AiUsageReserveCommand(
                context.feature(), context.promptVersion(), context.callerRef(), context.requestedModel(),
                context.maxTokens(), context.inputChars(), context.imageCount(), context.structured(), context.provider()
            ));
        } catch (AiBudgetExceededException exception) {
            logBudgetRejected(context.feature(), exception);
            throw new AiException(AiException.Reason.BUDGET_EXCEEDED, context.feature());
        } catch (AiUsageException exception) {
            log.warn("ai usage reserve failed. feature={}, errorCode={}", context.feature(), exception.getErrorCode().getCode());
            throw new AiException(AiException.Reason.METERING_FAILED, context.feature());
        } catch (RuntimeException exception) {
            // 원장 장애(DB) · 설정 누락. 메시지 · 원인은 남기지 않는다 — SQL · 값이 실릴 수 있다
            log.warn("ai usage reserve failed. feature={}, error={}", context.feature(), exception.getClass().getName());
            throw new AiException(AiException.Reason.METERING_FAILED, context.feature());
        }
        return new LedgerHandle(reservation.callId(), context.feature());
    }

    @Override
    public void after(AiCallHandle handle, AiCallOutcome outcome) {
        if (!(handle instanceof LedgerHandle ledger)) {
            // before 가 돌려준 핸들만 온다(ai-core 계약). 다른 핸들이면 정산할 호출을 모른다 — 회수가 닫는다
            log.warn("ai usage settle skipped. unknown handle type={}", handle == null ? "null" : handle.getClass().getName());
            return;
        }
        try {
            AiUsageSettleResult result = aiUsageMeterUseCase.settle(toCommand(ledger.callId(), outcome));
            if (!result.applied()) {
                log.debug("ai usage settle not applied (already closed). callId={}, feature={}", ledger.callId(), ledger.feature());
                return;
            }
            if (result.alert() != null) {
                logBudgetCrossed(result.alert());
            }
        } catch (RuntimeException exception) {
            log.warn("ai usage settle failed, reservation will be swept. callId={}, feature={}, status={}, error={}",
                ledger.callId(), ledger.feature(), outcome == null ? null : outcome.status(), exception.getClass().getName());
        }
    }

    /** ai-core 결과 → 정산 커맨드. 상태 이름은 1:1 이다. 사용량은 응답을 받은 결과(COMPLETED · UNUSABLE)에만 있다. */
    static AiUsageSettleCommand toCommand(long callId, AiCallOutcome outcome) {
        AiUsageStatus status = AiUsageStatus.valueOf(outcome.status().name());
        AiUsage usage = outcome.usage();
        AiTokenUsage tokens = usage == null ? null
            : new AiTokenUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheCreationInputTokens(), usage.cacheReadInputTokens());
        return new AiUsageSettleCommand(
            callId, status, tokens, outcome.model(), outcome.reason() == null ? null : outcome.reason().name(),
            outcome.statusCode(), outcome.messageId(), outcome.elapsedMs()
        );
    }

    /**
     * 거절에 누계 · 한도를 더하는 로그. 거절마다의 WARN({@code ai call rejected. reason=BUDGET_EXCEEDED})은 ai-core 클라이언트가 이미 남긴다 — 여기서는
     * 그 위에 덧붙일 정보만 낸다. 예상치 하나가 한도보다 크면 설정 실수(최대 출력 토큰 · 한도)라 매번 WARN, 이 달 첫 거절이면 누계를 실은 WARN 한 번,
     * 그 밖의 거절은 이 어댑터에서는 DEBUG 다(ai-core 의 WARN 은 그대로 남는다).
     */
    private static void logBudgetRejected(String feature, AiBudgetExceededException exception) {
        AiBudgetAlert alert = exception.getAlert();
        if (exception.isSingleCallOverLimit()) {
            log.warn("ai call estimate exceeds monthly budget — check max-tokens or budget. feature={}, estimateMicros={}, limitMicros={}",
                feature, exception.getEstimateCostMicros(), alert.limitMicros());
        } else if (alert.exceedCrossed()) {
            log.warn("ai monthly budget exceeded — ai calls are rejected until next month. period={}, reservedMicros={}, settledMicros={}, limitMicros={}",
                alert.period(), alert.reservedMicros(), alert.settledMicros(), alert.limitMicros());
        } else {
            log.debug("ai call rejected by monthly budget. feature={}, period={}, estimateMicros={}", feature, alert.period(), exception.getEstimateCostMicros());
        }
    }

    /** 정산으로 처음 넘은 임계 — 기간당 한 번만 온다. */
    private static void logBudgetCrossed(AiBudgetAlert alert) {
        if (alert.exceedCrossed()) {
            log.warn("ai monthly budget reached. period={}, reservedMicros={}, settledMicros={}, limitMicros={}",
                alert.period(), alert.reservedMicros(), alert.settledMicros(), alert.limitMicros());
        } else if (alert.warnCrossed()) {
            log.warn("ai monthly budget warning threshold crossed. period={}, reservedMicros={}, settledMicros={}, limitMicros={}",
                alert.period(), alert.reservedMicros(), alert.settledMicros(), alert.limitMicros());
        }
    }

    /**
     * {@link #before} 가 돌려주고 {@link #after} 가 받는 핸들.
     *
     * @param callId  원장 호출 아이디
     * @param feature 기능 이름 — 로그용
     */
    record LedgerHandle(long callId, String feature) implements AiCallHandle {

    }
}
