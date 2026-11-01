package com.commercefoundry.domainlayer.aiusage.adapter.in.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiException.Reason;
import com.commercefoundry.ai.client.AiUsage;
import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallOutcome;
import com.commercefoundry.ai.metering.AiCallOutcome.Status;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageErrorCode;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiBudgetAlert;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 계량 어댑터의 번역 규칙을 가짜 UseCase 로 본다 — 예약 커맨드 옮기기, 한도 초과 → {@code BUDGET_EXCEEDED}, 원장 장애 → {@code METERING_FAILED},
 * 다섯 결과의 정산 커맨드, 정산 실패 삼키기, 인터럽트 플래그 보존, 로그에 예외 메시지가 없음. SDK 를 거친 흐름은 {@code AiUsageMeteringFlowTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AiUsageMeteringInterceptorTest {

    private static final String LEAK_PROBE = "LedgerLeakProbe-7f3a";
    private static final AiCallContext CONTEXT = new AiCallContext("call-uuid", "size-chart", 2, "job:42", "claude-opus-5-5", 300, 1_234, 1, true);

    private final FakeMeter meter = new FakeMeter();
    private final AiUsageMeteringInterceptor interceptor = new AiUsageMeteringInterceptor(meter);

    @Test
    @DisplayName("before 는 컨텍스트의 기능 · 버전 · callerRef · 수치를 예약 커맨드로 옮기고, 원장 호출 아이디를 품은 핸들을 돌려준다")
    void beforeReservesAndReturnsHandle() {
        meter.onReserve = command -> new AiUsageReservation(901L, "2026-11", 6_000L);

        AiCallHandle handle = interceptor.before(CONTEXT);

        assertThat(meter.reserves).containsExactly(new AiUsageReserveCommand("size-chart", 2, "job:42", "claude-opus-5-5", 300, 1_234, 1, true));
        assertThat(handle).isEqualTo(new AiUsageMeteringInterceptor.LedgerHandle(901L, "size-chart"));
    }

    @Test
    @DisplayName("한도 초과는 BUDGET_EXCEEDED(재시도 없음)이고, 이 달 첫 거절이면 누계만 WARN 으로 남긴다")
    void budgetExceededTranslates(CapturedOutput output) {
        meter.onReserve = command -> {
            throw new AiBudgetExceededException(new AiBudgetAlert("2026-11", false, true, 9_000L, 8_000L, 10_000L), 6_000L, false);
        };

        assertThatThrownBy(() -> interceptor.before(CONTEXT)).isInstanceOfSatisfying(AiException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(Reason.BUDGET_EXCEEDED);
            assertThat(exception.reason().retryable()).isFalse();
            assertThat(exception.feature()).isEqualTo("size-chart");
        });
        assertThat(output).contains("ai monthly budget exceeded").contains("limitMicros=10000");
    }

    @Test
    @DisplayName("예상치 하나가 한도보다 크면 설정 경고(WARN)로 남기고 BUDGET_EXCEEDED 다")
    void singleCallOverLimitWarnsAsConfiguration(CapturedOutput output) {
        meter.onReserve = command -> {
            throw new AiBudgetExceededException(new AiBudgetAlert("2026-11", false, false, 0L, 0L, 10_000L), 15_000L, true);
        };

        assertThatThrownBy(() -> interceptor.before(CONTEXT))
            .isInstanceOfSatisfying(AiException.class, exception -> assertThat(exception.reason()).isEqualTo(Reason.BUDGET_EXCEEDED));
        assertThat(output).contains("ai call estimate exceeds monthly budget").contains("estimateMicros=15000");
    }

    @Test
    @DisplayName("예약 경합(RESERVE_CONTENDED)은 METERING_FAILED(재시도)이고 오류 코드만 남긴다")
    void contendedReserveIsMeteringFailed(CapturedOutput output) {
        meter.onReserve = command -> {
            throw new AiUsageException(AiUsageErrorCode.RESERVE_CONTENDED);
        };

        assertThatThrownBy(() -> interceptor.before(CONTEXT)).isInstanceOfSatisfying(AiException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(Reason.METERING_FAILED);
            assertThat(exception.reason().retryable()).isTrue();
            assertThat(exception.getCause()).isNull();
        });
        assertThat(output).contains("errorCode=AI_USAGE_020");
    }

    @Test
    @DisplayName("원장 장애(그 밖의 예외)는 METERING_FAILED 이고, 예외 메시지는 AiException · 로그 어디에도 남지 않는다")
    void ledgerFailureIsMeteringFailedWithoutMessage(CapturedOutput output) {
        meter.onReserve = command -> {
            throw new IllegalStateException(LEAK_PROBE);
        };

        assertThatThrownBy(() -> interceptor.before(CONTEXT)).isInstanceOfSatisfying(AiException.class, exception -> {
            assertThat(exception.reason()).isEqualTo(Reason.METERING_FAILED);
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getMessage()).doesNotContain(LEAK_PROBE);
        });
        assertThat(output).contains("ai usage reserve failed").contains(IllegalStateException.class.getName()).doesNotContain(LEAK_PROBE);
    }

    @Test
    @DisplayName("COMPLETED 는 실측 토큰(캐시 포함) · 답한 모델 · 메시지 ID · 걸린 시간으로 정산한다")
    void completedSettlesActualUsage() {
        interceptor.after(handle(), new AiCallOutcome(Status.COMPLETED, new AiUsage(30, 7, 200, 1000), "claude-opus-5-5", null, null, "msg_1", 120));

        assertThat(meter.settles).containsExactly(new AiUsageSettleCommand(
            901L, AiUsageStatus.COMPLETED, new AiTokenUsage(30, 7, 200, 1000), "claude-opus-5-5", null, null, "msg_1", 120
        ));
    }

    @Test
    @DisplayName("FAILED 는 오류 원인 · 상태 코드를, TIMEOUT · NOT_SENT 는 원인만 싣고 사용량 없이 정산한다")
    void failedTimeoutAndNotSentSettleWithoutUsage() {
        interceptor.after(handle(), new AiCallOutcome(Status.FAILED, null, "claude-opus-5-5", Reason.UNAVAILABLE, 529, null, 40));
        interceptor.after(handle(), new AiCallOutcome(Status.TIMEOUT, null, "claude-opus-5-5", Reason.UNAVAILABLE, null, null, 1_000));
        interceptor.after(handle(), new AiCallOutcome(Status.NOT_SENT, null, "claude-opus-5-5", Reason.BUSY, null, null, 0));

        assertThat(meter.settles).containsExactly(
            new AiUsageSettleCommand(901L, AiUsageStatus.FAILED, null, "claude-opus-5-5", "UNAVAILABLE", 529, null, 40),
            new AiUsageSettleCommand(901L, AiUsageStatus.TIMEOUT, null, "claude-opus-5-5", "UNAVAILABLE", null, null, 1_000),
            new AiUsageSettleCommand(901L, AiUsageStatus.NOT_SENT, null, "claude-opus-5-5", "BUSY", null, null, 0)
        );
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    @DisplayName("ai-core 결과 다섯 가지가 모두 원장의 같은 이름 상태로 옮겨진다")
    void everyOutcomeStatusMaps(Status status) {
        AiUsage usage = status == Status.COMPLETED || status == Status.UNUSABLE ? new AiUsage(1, 1, 0, 0) : null;

        AiUsageSettleCommand command = AiUsageMeteringInterceptor.toCommand(1L, new AiCallOutcome(status, usage, "m", null, null, null, 0));

        assertThat(command.outcome().name()).isEqualTo(status.name());
    }

    @Test
    @DisplayName("정산이 실패해도 던지지 않고 타입만 WARN 으로 남긴다 — 메시지는 남기지 않는다(예약은 회수가 닫는다)")
    void settleFailureIsSwallowed(CapturedOutput output) {
        meter.onSettle = command -> {
            throw new IllegalStateException(LEAK_PROBE);
        };

        assertThatCode(() -> interceptor.after(handle(), new AiCallOutcome(Status.NOT_SENT, null, "m", Reason.BUSY, null, null, 0))).doesNotThrowAnyException();
        assertThat(output).contains("ai usage settle failed").contains("callId=901").contains(IllegalStateException.class.getName()).doesNotContain(LEAK_PROBE);
    }

    @Test
    @DisplayName("인터럽트 플래그가 켜진 채 불려도 플래그를 지우지 않고 UseCase 에 그대로 넘긴다")
    void interruptFlagIsLeftAlone() {
        Thread.currentThread().interrupt();
        try {
            interceptor.after(handle(), new AiCallOutcome(Status.NOT_SENT, null, "m", Reason.BUSY, null, null, 0));
        } finally {
            assertThat(Thread.interrupted()).as("플래그가 그대로다(여기서 내린다)").isTrue();
        }
        assertThat(meter.interruptedAtSettle).containsExactly(true);
    }

    @Test
    @DisplayName("이번 정산으로 처음 넘은 임계(경고 · 한도)를 WARN 으로 남긴다")
    void crossedThresholdIsLogged(CapturedOutput output) {
        meter.onSettle = command -> new AiUsageSettleResult(true, AiUsageStatus.COMPLETED, 100L, new AiBudgetAlert("2026-11", true, false, 8_100L, 8_000L, 10_000L));

        interceptor.after(handle(), new AiCallOutcome(Status.COMPLETED, new AiUsage(1, 1, 0, 0), "m", null, null, "msg", 1));

        assertThat(output).contains("ai monthly budget warning threshold crossed").contains("period=2026-11");
    }

    @Test
    @DisplayName("모르는 핸들이면 정산하지 않는다")
    void unknownHandleIsIgnored() {
        interceptor.after(new AiCallHandle() {
        }, new AiCallOutcome(Status.NOT_SENT, null, "m", Reason.BUSY, null, null, 0));

        assertThat(meter.settles).isEmpty();
    }

    private static AiCallHandle handle() {
        return new AiUsageMeteringInterceptor.LedgerHandle(901L, "size-chart");
    }

    /** 받은 커맨드를 기록하는 가짜 UseCase. 정산 때 인터럽트 플래그를 읽기만 한다(지우지 않는다). */
    private static final class FakeMeter implements AiUsageMeterUseCase {

        private final List<AiUsageReserveCommand> reserves = new ArrayList<>();
        private final List<AiUsageSettleCommand> settles = new ArrayList<>();
        private final List<Boolean> interruptedAtSettle = new ArrayList<>();
        private Function<AiUsageReserveCommand, AiUsageReservation> onReserve = command -> new AiUsageReservation(1L, "2026-11", 1L);
        private Function<AiUsageSettleCommand, AiUsageSettleResult> onSettle = command -> new AiUsageSettleResult(true, command.outcome(), 0L, null);

        @Override
        public AiUsageReservation reserve(AiUsageReserveCommand command) {
            reserves.add(command);
            return onReserve.apply(command);
        }

        @Override
        public AiUsageSettleResult settle(AiUsageSettleCommand command) {
            settles.add(command);
            interruptedAtSettle.add(Thread.currentThread().isInterrupted());
            return onSettle.apply(command);
        }
    }
}
