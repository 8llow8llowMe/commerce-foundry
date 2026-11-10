package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.application.port.in.PaymentRestorationSweepUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentStockRestorationProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentStockRestorationProcessor.RestoreOutcome;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentStockRestorationProcessor.Revalidation;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 반영 재고 복원 스윕 (#582, {@code order-flow-guide.md} §3-6) — 컨슈머가 재시도 예산을 넘겨 DLT 로 보낸 복원도 같은 클레임 아이디로 끝내고 거절 회신을 낸다.
 *
 * <p>트랜잭션을 걸지 않는다 — 복원 · 결제 재조회가 원격 호출이라 DB 구간(선점 · 재검증 · HELD · 마무리)만 Processor 의 짧은 트랜잭션으로 나눈다.
 * 인스턴스 간 중복 실행은 ShedLock 없이 행 선점(다음 시각 조건부 UPDATE)으로 막는다 — {@code ClaimCancellationRetrySchedulerFacade} 와 같다.
 * 선점한 행이 중간에 끊기면 다음 시각(backoff)에 다시 집힌다. 복원은 같은 클레임이라 다시 불러도 200 이고, 회신은 결제 기록의 복원 시각 조건부 UPDATE 가 하나만 통과시킨다.
 * inbox 를 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRestorationSweepFacade implements PaymentRestorationSweepUseCase {

    private final PaymentStockRestorationProcessor paymentStockRestorationProcessor;
    private final OrderPaymentProcessor orderPaymentProcessor;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void sweepDueRestorations() {
        List<OrderStockRestoration> targets = paymentStockRestorationProcessor.findDueTargets();
        int restored = 0;
        for (OrderStockRestoration target : targets) {
            try {
                restored += sweepOne(target) ? 1 : 0;
            } catch (RuntimeException exception) {
                // 한 행의 실패는 그 행만 다음 시각으로 넘기고 배치는 이어 간다. 원문 메시지 없이 타입 · 스택만 남긴다
                log.error("payment restoration sweep failed. restorationId={}, error={}", target.id(), exception.getClass().getSimpleName(),
                    RedactedThrowable.of(exception));
            }
        }
        if (restored > 0) {
            log.info("payment restorations swept. count={}", restored);
        }
    }

    /** @return 이번 실행이 복원을 마무리했는가 */
    private boolean sweepOne(OrderStockRestoration target) {
        Optional<OrderStockRestoration> claim = paymentStockRestorationProcessor.claim(target);
        if (claim.isEmpty()) {
            log.info("payment restoration claimed by another worker, skipping. restorationId={}", target.id());
            return false;
        }
        OrderStockRestoration claimed = claim.get();
        Revalidation revalidation = paymentStockRestorationProcessor.revalidate(claimed);
        if (revalidation.alreadyRestored()) {
            paymentStockRestorationProcessor.markRestoredOnly(claimed);
            return false;
        }
        if (revalidation.target() == null) {
            paymentStockRestorationProcessor.hold(claimed, OrderStockRestoration.OWNERSHIP_MISMATCH);
            return false;
        }
        RestoreOutcome outcome = paymentStockRestorationProcessor.restore(claimed, revalidation.target());
        if (outcome.holdCode() != null) {
            paymentStockRestorationProcessor.hold(claimed, outcome.holdCode());
            return false;
        }
        if (!outcome.restored()) {
            return false;
        }
        Optional<Boolean> fullyCanceled = paymentStockRestorationProcessor.paymentFullyCanceled(claimed);
        if (fullyCanceled.isEmpty()) {
            return false;
        }
        boolean replied = orderPaymentProcessor.completeSweptRestoration(revalidation.target().orderPayment(), fullyCanceled.get());
        log.info("payment restoration completed by sweep. restorationId={}, orderId={}, replied={}", claimed.id(), claimed.orderId(), replied);
        return true;
    }
}
