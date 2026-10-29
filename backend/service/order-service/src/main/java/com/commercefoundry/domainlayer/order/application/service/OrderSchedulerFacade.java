package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.common.exception.RedactedThrowable;
import com.commercefoundry.domainlayer.order.application.model.OrderExpiryCandidate;
import com.commercefoundry.domainlayer.order.application.port.in.OrderSchedulerUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderExpiryProcessor;
import com.commercefoundry.global.properties.OrderPaymentExpiryProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 주문 만료 스윕 한 번 ({@code order-flow-guide.md} §1-3).
 *
 * <p>트랜잭션을 걸지 않는다({@code NEVER}) — 조회 · 건별 만료가 각자 짧은 트랜잭션이다. 한 건이 실패해도 그 건만 롤백되고 로그만 남긴 뒤 다음 건으로 간다.
 * 실행 시작 시각 하나로 기한을 판정하고, 배치의 마지막 건을 커서로 넘겨 다음 건부터 읽는다(keyset) — 실패한 건이 PENDING_PAYMENT 로 남아도 같은 실행이
 * 그 건을 다시 읽어 배치를 막지 않는다. 실패한 건은 다음 실행이 처음부터 다시 읽는다. 한 실행은 {@code max-batches-per-run} 배치까지다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSchedulerFacade implements OrderSchedulerUseCase {

    private final OrderExpiryProcessor orderExpiryProcessor;
    private final OrderPaymentExpiryProperties properties;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void expireOverdueOrders() {
        // DB 는 timestamp(6)(마이크로초)다. 나노초가 남은 now 로 조회하면 JDBC 바인드가 반올림해 DB 판정(payment_due_at <= now)과
        // 도메인 판정(expire(now))이 경계에서 갈릴 수 있다 — 한 번 잘라 두 판정을 같은 값으로 한다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int expired = 0;
        OrderExpiryCandidate cursor = null;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            List<OrderExpiryCandidate> candidates = orderExpiryProcessor.findDue(now, cursor, properties.batchSize());
            for (OrderExpiryCandidate candidate : candidates) {
                expired += expireOne(candidate.orderId(), now) ? 1 : 0;
            }
            if (candidates.size() < properties.batchSize()) {
                break;
            }
            cursor = candidates.getLast();
        }
        if (expired > 0) {
            log.info("orders expired. count={}", expired);
        }
    }

    /**
     * 실패는 이 건만 롤백된다. 식별자 · 예외 타입만 남긴다. 결제 완료 · 다른 인스턴스의 만료와 겹친 버전 충돌은 정상 경합이라 warn,
     * 그 밖의 예외는 error 다.
     *
     * <p>error 는 예외 타입 · 루트 원인 타입과 {@link RedactedThrowable} 사본(타입 · 스택 프레임 · SQLState 만)을 남긴다 — 원래 예외를 넘기면
     * 원인 사슬의 메시지(PostgreSQL {@code Failing row contains (...)} 같은 행 값)가 찍힌다 ({@code coding-conventions.md} §8, #280).
     */
    private boolean expireOne(long orderId, Instant now) {
        try {
            return orderExpiryProcessor.expire(orderId, now);
        } catch (OptimisticLockingFailureException exception) {
            log.warn("order expire conflicted. orderId={}, error={}", orderId, exception.getClass().getSimpleName());
            return false;
        } catch (RuntimeException exception) {
            log.error("order expire failed. orderId={}, error={}, rootCause={}", orderId, exception.getClass().getSimpleName(),
                NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName(), RedactedThrowable.of(exception));
            return false;
        }
    }
}
