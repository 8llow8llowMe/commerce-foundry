package com.commercefoundry.domainlayer.aiusage.application.service;

import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageErrorCode;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.application.service.processor.AiBudgetProcessor;
import com.commercefoundry.domainlayer.aiusage.application.service.processor.AiBudgetProcessor.ReserveAttempt;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiUsageMeterFacade implements AiUsageMeterUseCase {

    /** 예약 시도 상한 — 월 행 생성 뒤 재시도 · 경합 재시도를 합친 횟수. */
    static final int MAX_RESERVE_ATTEMPTS = 3;

    private final AiBudgetProcessor aiBudgetProcessor;
    private final Clock clock;
    private final TransactionTemplate settleTransaction;

    public AiUsageMeterFacade(AiBudgetProcessor aiBudgetProcessor, Clock clock, PlatformTransactionManager transactionManager) {
        this.aiBudgetProcessor = aiBudgetProcessor;
        this.clock = clock;
        this.settleTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 바깥 트랜잭션에 합류하지 않는다({@code NOT_SUPPORTED}) — 시도 하나({@link AiBudgetProcessor#tryReserve})가 트랜잭션 하나다
     * ({@code architecture-guide.md} §3 Facade 트랜잭션 예외). 그 달 첫 예약이면 월 행이 없어 시도가 비고, 월 행을 새 트랜잭션으로 만든 뒤(경합에서 지면 남이 만든 행을 쓴다)
     * 다시 시도한다. 바깥 트랜잭션 안에서 월 행을 만들면 커넥션 둘을 겹쳐 쥐고, PostgreSQL 은 유니크 키 위반 뒤 그 트랜잭션을 중단시켜 같은 트랜잭션에서 다시 시도할 수 없다.
     * 판정 사이에 다른 예약 · 정산과 엇갈리면({@code RETRY}) 그대로 다시 시도하고, 합쳐 {@value #MAX_RESERVE_ATTEMPTS}번을 넘으면
     * {@link AiUsageErrorCode#RESERVE_CONTENDED}(재시도 가능)다. 거절 표시가 커밋된 뒤에 초과 예외를 던진다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AiUsageReservation reserve(AiUsageReserveCommand command) {
        Instant now = clock.instant();
        for (int attempt = 0; attempt < MAX_RESERVE_ATTEMPTS; attempt++) {
            ReserveAttempt result = aiBudgetProcessor.tryReserve(command, now);
            switch (result.outcome()) {
                case RESERVED -> {
                    return result.reservation();
                }
                case EXCEEDED -> throw new AiBudgetExceededException(result.alert(), result.estimateCostMicros(), result.singleCallOverLimit());
                case MONTH_MISSING -> aiBudgetProcessor.openMonth(result.period(), now);
                case RETRY -> {
                    // 그 사이 월 행이 생겼거나 누계가 줄었다 — 다시 판정한다
                }
            }
        }
        throw new AiUsageException(AiUsageErrorCode.RESERVE_CONTENDED);
    }

    /**
     * 호출 행 닫기 · 월 카운터 이동 · 임계 판정이 한 트랜잭션이다 — 셋 중 하나만 남지 않는다.
     *
     * <p>ai-core 는 인터럽트 플래그가 켜진 스레드에서 정산을 부를 수 있다. 플래그가 켜진 채 트랜잭션을 열면 커넥션 풀 대기가 인터럽트로 실패할 수 있어,
     * 트랜잭션을 열기 <b>전에</b> 플래그를 내려 두고 끝나면(예외여도) 되살린다 — 그래서 선언형 {@code @Transactional} 대신 이 안에서 트랜잭션을 연다.
     */
    @Override
    public AiUsageSettleResult settle(AiUsageSettleCommand command) {
        boolean interrupted = Thread.interrupted();
        try {
            return settleTransaction.execute(status -> aiBudgetProcessor.settle(command, clock.instant()));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
