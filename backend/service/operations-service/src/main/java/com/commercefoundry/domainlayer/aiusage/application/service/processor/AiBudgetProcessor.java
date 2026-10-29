package com.commercefoundry.domainlayer.aiusage.application.service.processor;

import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.info.AiBudgetAlert;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageCallRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.AiUsageMonthRepositoryPort;
import com.commercefoundry.domainlayer.aiusage.application.port.out.query.AiUsageMonthQueryResult;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiBudgetPeriod;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiCost;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiEstimatePolicy;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable.AiPriceQuote;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import com.commercefoundry.global.properties.AiUsageProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월 한도 예약 · 정산 · 임계 판정 (#215 PR-B). 월 카운터는 읽고 판정하지 않고 조건부 UPDATE 한 문장으로 바꾼다 — 동시 예약이 각자 읽은 누계로 함께 한도를 넘지 않게.
 *
 * <p>호출 행 정산은 "아직 PENDING 일 때만" 조건부 UPDATE 다. 1행일 때만 월 카운터를 옮긴다 — 같은 결과가 두 번 오거나 회수와 겹쳐도 한 번만 반영된다(멱등).
 * 애플리케이션 락 · 인터럽트에 반응하는 대기가 없다.
 */
@Component
public class AiBudgetProcessor {

    private final AiUsageCallRepositoryPort aiUsageCallRepositoryPort;
    private final AiUsageMonthRepositoryPort aiUsageMonthRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final AiUsageProperties.Budget budget;
    private final AiPriceTable priceTable;
    private final AiEstimatePolicy estimatePolicy;

    public AiBudgetProcessor(
        AiUsageCallRepositoryPort aiUsageCallRepositoryPort, AiUsageMonthRepositoryPort aiUsageMonthRepositoryPort, SnowflakeIdGenerator snowflakeIdGenerator,
        AiUsageProperties aiUsageProperties
    ) {
        this.aiUsageCallRepositoryPort = aiUsageCallRepositoryPort;
        this.aiUsageMonthRepositoryPort = aiUsageMonthRepositoryPort;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.budget = aiUsageProperties.budget();
        this.priceTable = aiUsageProperties.pricing().toTable();
        this.estimatePolicy = aiUsageProperties.estimate().toPolicy();
    }

    /**
     * 예약을 한 번 시도한다. 월 카운터 UPDATE → 호출 행 INSERT 순서다 — 한도를 넘으면 호출 행을 남기지 않는다.
     *
     * <ul>
     *   <li>예상치 하나가 월 한도보다 크면 DB 를 건드리지 않고 거절한다 — 누계와 관계없이 영원히 들어가지 않는 호출이라 이 달의 초과 표시를 세우지 않는다.</li>
     *   <li>UPDATE 가 0행이면 월 행이 없거나, 넣으면 넘거나, 그 사이 상태가 바뀌었다(다른 트랜잭션이 월 행을 막 만들었거나 정산이 누계를 줄였다).
     *       초과 표시는 "지금 넣으면 넘는다" 를 조건으로 세우고, 그것도 0행이면 다시 읽어 지금 넘는지 본다 — 넘지 않으면 {@code RETRY} 다.</li>
     *   <li>거절이면 표시를 세우고 <b>정상 반환해 커밋한다</b> — 예외로 롤백하면 표시가 사라진다.</li>
     * </ul>
     *
     * @throws IllegalStateException 월 한도 · 단가 설정이 없다
     */
    @Transactional
    public ReserveAttempt tryReserve(AiUsageReserveCommand command, Instant now) {
        long limitMicros = limitMicros();
        String period = AiBudgetPeriod.of(now, budget.zone()).value();
        AiPriceQuote quote = priceTable.quote(command.requestedModel());
        long estimateMicros = AiCost.estimateMicros(estimatePolicy, command.inputChars(), command.imageCount(), command.maxTokens(), quote.price());

        if (estimateMicros > limitMicros) {
            Optional<AiUsageMonthQueryResult> month = aiUsageMonthRepositoryPort.find(period);
            AiBudgetAlert alert = new AiBudgetAlert(
                period, false, false, month.map(AiUsageMonthQueryResult::reservedMicros).orElse(0L),
                month.map(AiUsageMonthQueryResult::settledMicros).orElse(0L), limitMicros
            );
            return ReserveAttempt.exceeded(alert, estimateMicros, true);
        }
        if (aiUsageMonthRepositoryPort.reserve(period, estimateMicros, limitMicros, now)) {
            AiUsageCall call = AiUsageCall.pending(
                snowflakeIdGenerator.generateId(), new AiBudgetPeriod(period), command.feature(), command.promptVersion(), command.callerRef(),
                command.requestedModel(), command.structured(), command.imageCount(), command.inputChars(), estimateMicros, quote, now
            );
            aiUsageCallRepositoryPort.insert(call);
            return ReserveAttempt.reserved(new AiUsageReservation(call.id(), period, estimateMicros));
        }
        if (aiUsageMonthRepositoryPort.markExceededByReserve(period, estimateMicros, limitMicros, now)) {
            return ReserveAttempt.exceeded(alert(period, false, true, limitMicros), estimateMicros, false);
        }
        Optional<AiUsageMonthQueryResult> month = aiUsageMonthRepositoryPort.find(period);
        if (month.isEmpty()) {
            return ReserveAttempt.monthMissing(period, estimateMicros);
        }
        if (month.get().reservedMicros() + estimateMicros > limitMicros) {
            // 이미 이 달 초과를 표시했고 지금도 넘는다
            return ReserveAttempt.exceeded(toAlert(month.get(), false, false, limitMicros), estimateMicros, false);
        }
        return ReserveAttempt.retry(period, estimateMicros);
    }

    /** 그 달 첫 예약 전에 월 행을 만든다. 새 트랜잭션은 포트가 연다 — 유니크 키 경합에서 진 쪽의 트랜잭션이 중단되지 않게. */
    public void openMonth(String period, Instant now) {
        aiUsageMonthRepositoryPort.createIfAbsent(period, limitMicros(), now);
    }

    /**
     * 결과로 호출을 닫고 월 카운터를 옮긴 뒤 임계를 판정한다. 호출자의 트랜잭션 안에서만 돈다(셋이 한 트랜잭션이어야 한다).
     *
     * <p>응답을 받은 결과(COMPLETED · UNUSABLE)는 답한 모델(없으면 요청한 모델)의 단가로 실측 비용을 계산한다. 나머지는 단가가 필요 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AiUsageSettleResult settle(AiUsageSettleCommand command, Instant now) {
        Optional<AiUsageCall> found = aiUsageCallRepositoryPort.findById(command.callId());
        if (found.isEmpty() || !found.get().status().isPending()) {
            return AiUsageSettleResult.notApplied();
        }
        AiUsageCall call = found.get();
        AiPriceQuote actualQuote = command.outcome().chargesActualUsage()
            ? priceTable.quote(command.model() == null ? call.requestedModel() : command.model())
            : null;
        AiUsageCall closed = call.settle(
            command.outcome(), command.usage(), command.model(), actualQuote, command.reason(), command.httpStatus(), command.messageId(), command.elapsedMs(), now
        );
        if (!aiUsageCallRepositoryPort.closeIfPending(closed)) {
            return AiUsageSettleResult.notApplied();
        }
        String period = closed.period().value();
        aiUsageMonthRepositoryPort.settle(period, closed.estimateCostMicros(), closed.costMicros(), closed.status().countsAsCall() ? 1 : 0, now);
        return new AiUsageSettleResult(true, closed.status(), closed.costMicros(), crossedThresholds(period, now));
    }

    /** 한도가 설정돼 있을 때만 판정한다. 둘 다 이미 세웠으면 두 UPDATE 모두 0행이다. */
    private AiBudgetAlert crossedThresholds(String period, Instant now) {
        if (budget.limitMicros().isEmpty()) {
            return null;
        }
        long limitMicros = budget.limitMicros().getAsLong();
        boolean warned = aiUsageMonthRepositoryPort.markWarned(period, budget.warnThresholdMicros(limitMicros), now);
        boolean exceeded = aiUsageMonthRepositoryPort.markExceededAtLimit(period, limitMicros, now);
        if (!warned && !exceeded) {
            return null;
        }
        return alert(period, warned, exceeded, limitMicros);
    }

    private AiBudgetAlert alert(String period, boolean warned, boolean exceeded, long limitMicros) {
        AiUsageMonthQueryResult month = aiUsageMonthRepositoryPort.find(period)
            .orElseThrow(() -> new IllegalStateException("월 카운터가 없습니다: " + period));
        return toAlert(month, warned, exceeded, limitMicros);
    }

    private static AiBudgetAlert toAlert(AiUsageMonthQueryResult month, boolean warned, boolean exceeded, long limitMicros) {
        return new AiBudgetAlert(month.period(), warned, exceeded, month.reservedMicros(), month.settledMicros(), limitMicros);
    }

    private long limitMicros() {
        return budget.limitMicros()
            .orElseThrow(() -> new IllegalStateException("operations.ai.budget.monthly-usd 가 없어 AI 호출을 예약할 수 없습니다."));
    }

    /**
     * 예약 한 번의 결과.
     *
     * @param reservation         예약했을 때만
     * @param period              월
     * @param estimateCostMicros  예상 비용
     * @param alert               한도 초과일 때만 — 지금 누계와 이 달 첫 거절인지
     * @param singleCallOverLimit 한도 초과 중에서도 예상치 하나가 월 한도보다 커서 영원히 들어가지 않는다
     */
    public record ReserveAttempt(
        Outcome outcome, AiUsageReservation reservation, String period, long estimateCostMicros, AiBudgetAlert alert, boolean singleCallOverLimit
    ) {

        /** {@code MONTH_MISSING} 은 월 행을 만든 뒤, {@code RETRY} 는 그대로 다시 시도한다. */
        public enum Outcome {
            RESERVED, MONTH_MISSING, RETRY, EXCEEDED
        }

        static ReserveAttempt reserved(AiUsageReservation reservation) {
            return new ReserveAttempt(Outcome.RESERVED, reservation, reservation.period(), reservation.estimateCostMicros(), null, false);
        }

        static ReserveAttempt monthMissing(String period, long estimateCostMicros) {
            return new ReserveAttempt(Outcome.MONTH_MISSING, null, period, estimateCostMicros, null, false);
        }

        static ReserveAttempt retry(String period, long estimateCostMicros) {
            return new ReserveAttempt(Outcome.RETRY, null, period, estimateCostMicros, null, false);
        }

        static ReserveAttempt exceeded(AiBudgetAlert alert, long estimateCostMicros, boolean singleCallOverLimit) {
            return new ReserveAttempt(Outcome.EXCEEDED, null, alert.period(), estimateCostMicros, alert, singleCallOverLimit);
        }
    }
}
