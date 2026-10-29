package com.commercefoundry.domainlayer.aiusage.application.port.in;

import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.exception.AiBudgetExceededException;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;

/**
 * AI 호출 한 번의 계량 — 보내기 전 예약, 끝난 뒤 정산 (#215 PR-B). 계량 어댑터(ai-core {@code AiCallInterceptor} 구현, PR-B3)가 부른다.
 *
 * <p>DB 장애 등 그 밖의 예외는 그대로 올라간다 — 어댑터가 {@code METERING_FAILED}(재시도)로 번역한다.
 */
public interface AiUsageMeterUseCase {

    /**
     * 예상 비용만큼 월 한도를 예약하고 호출 행(PENDING)을 남긴다.
     *
     * <p>바깥 트랜잭션에 합류하지 않는다 — 시도마다 자기 트랜잭션이다.
     *
     * @throws AiBudgetExceededException 예약하면 월 한도를 넘는다 — 호출 행을 남기지 않는다
     * @throws com.commercefoundry.domainlayer.aiusage.application.exception.AiUsageException {@code RESERVE_CONTENDED} — 다른 예약 · 정산과 거듭 엇갈렸다(재시도 가능)
     * @throws IllegalStateException 월 한도 · 단가 설정이 없다
     */
    AiUsageReservation reserve(AiUsageReserveCommand command);

    /**
     * 결과로 호출을 닫고 월 카운터를 예상치에서 확정 비용으로 옮긴다. 멱등이다 — 이미 닫힌 호출이면 아무것도 바꾸지 않고 {@code applied=false} 다.
     *
     * <p>인터럽트 플래그가 켜진 스레드에서 불러도 끝난다 — 트랜잭션(커넥션 획득)을 열기 전에 플래그를 내리고, 돌아갈 때(예외여도) 되살린다.
     * 애플리케이션 락 없이 조건부 UPDATE 만 쓴다.
     */
    AiUsageSettleResult settle(AiUsageSettleCommand command);
}
