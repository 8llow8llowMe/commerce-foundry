package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.CancellationStepOutcome;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentCancelQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import java.util.SortedMap;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 후 취소 클레임의 남은 단계를 잇는다 (#16 PR-2, {@code order-flow-guide.md} §5) — 환불 대기면 환불(ext1) → 결과 반영(tx2), 복원 대기면 재고 복원(ext2) →
 * 결과 반영(tx3). 고객 요청(접수 직후)과 재시도 스케줄러(선점 직후)가 같은 이 경로를 쓴다.
 *
 * <p><b>트랜잭션을 걸지 않는다</b> — 환불은 PG 취소를 기다리는 긴 원격 호출이라 DB 커넥션을 쥔 채 기다리지 않고, 원격이 성공한 뒤 롤백돼 "PG 는 취소했는데 DB 는 모르는"
 * 창을 만들지 않으려고 DB 구간만 {@link OrderCancellationProcessor} 의 짧은 트랜잭션으로 나눈다. 그 사이 끊겨도 클레임이 남아 같은 클레임 아이디 · 저장된 금액으로
 * 다시 보낸다(payment · product 둘 다 클레임 아이디로 멱등).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationPipelineProcessor {

    /** 클레임 항목이 주문 스냅숏과 맞지 않아 복원 수량을 만들 수 없다 — 다시 해도 같아 바로 종결한다. */
    public static final String RESTORATION_DATA_INVALID = "RESTORATION_DATA_INVALID";

    private final OrderCancellationProcessor orderCancellationProcessor;
    private final PaymentCancelPort paymentCancelPort;
    private final StockRestorationPort stockRestorationPort;

    /**
     * 클레임 상태에 맞는 남은 단계를 실행한다. 결과 반영에서 버전으로 지면(다른 워커 · 요청이 먼저 처리) 조용히 멈춘다 — 단, 환불이 <b>확정</b>됐는데 그 사이
     * 클레임이 미확정으로 닫혔으면({@link Claim#REFUND_UNCONFIRMED}) 다시 읽은 클레임으로 확정을 한 번 더 반영하고 복원까지 잇는다(늦게 온 환불 성공).
     * 그 밖의 DB 실패는 그대로 나간다 — 클레임은 다음 시각에 재시도 스케줄러가 다시 집는다.
     *
     * @param claim 접수 · 선점에서 막 저장한 클레임(버전 포함)
     * @return 마지막으로 저장된 클레임. 버전에서 졌으면 그 직전 값
     */
    @Transactional(propagation = Propagation.NEVER)
    public Claim progress(Claim claim) {
        Claim current = claim;
        try {
            if (current.status() == Claim.Status.REQUESTED) {
                current = recordRefund(current, refund(current));
            }
            if (current.status() == Claim.Status.REFUNDED) {
                Order order = orderCancellationProcessor.getOrder(current.orderId());
                current = orderCancellationProcessor.recordRestoration(current, restore(current, order));
            }
        } catch (OptimisticLockingFailureException exception) {
            logLostVersion(current, exception);
        }
        return current;
    }

    /** tx2 — 확정을 반영하다 버전에서 졌고 클레임이 미확정으로 닫혀 있으면 다시 읽은 값으로 한 번 더 반영한다. 그 밖의 경합은 그대로 던진다. */
    private Claim recordRefund(Claim claim, CancellationStepOutcome outcome) {
        try {
            return orderCancellationProcessor.recordRefund(claim, outcome);
        } catch (OptimisticLockingFailureException exception) {
            if (outcome.kind() != CancellationStepOutcome.Kind.DONE) {
                throw exception;
            }
            Claim reloaded = orderCancellationProcessor.findClaim(claim.orderId())
                .filter(read -> read.status() == Claim.Status.REFUND_FAILED && Claim.REFUND_UNCONFIRMED.equals(read.failureCode()))
                .orElseThrow(() -> exception);
            log.warn("late refund success for unconfirmed cancellation claim, applying. claimId={}, orderId={}, paymentId={}",
                reloaded.id(), reloaded.orderId(), reloaded.paymentId());
            return orderCancellationProcessor.recordRefund(reloaded, outcome);
        }
    }

    /** ext1 — 저장된 결제 아이디 · 환불액만 보낸다(다시 계산하지 않는다, {@code PAYMENT_019} 방지). Feign 밖 예외(토큰 발급 실패 등)도 미확정이다. */
    private CancellationStepOutcome refund(Claim claim) {
        PaymentCancelQueryResult result;
        try {
            result = paymentCancelPort.cancel(claim.paymentId(), claim.id(), claim.expectedRefund());
        } catch (RuntimeException exception) {
            log.warn("cancellation refund call failed, will retry. claimId={}, error={}", claim.id(), exception.getClass().getSimpleName());
            return CancellationStepOutcome.retry();
        }
        return switch (result.outcome()) {
            case SUCCEEDED -> CancellationStepOutcome.done();
            case REJECTED -> CancellationStepOutcome.failed(result.failureCode());
            case UNKNOWN -> CancellationStepOutcome.retry();
        };
    }

    /**
     * ext2 — 주문의 확정 선점에서 클레임 항목 수량을 SKU 별로 묶어 복원한다. 실패 코드는 환불(원격 {@code PAYMENT_*} 코드)과 같게 product 의 원격 오류 코드로 남긴다 —
     * 어댑터가 그 코드에서 사유를 갈랐으므로 사유 → 코드는 1:1 이다.
     *
     * <ul>
     *   <li>027 {@code NOT_CONFIRMED} · 028 {@code QUANTITY_EXCEEDED} · 029 {@code RESTORATION_MISMATCH} · 003 {@code NOT_FOUND} — 종결(다시 해도 같다)</li>
     *   <li>클레임 항목이 주문에 없음 — 종결 {@link #RESTORATION_DATA_INVALID}(호출하지 않는다)</li>
     *   <li>026 · 030 · 031 {@code CONFLICT} · 모르는 4xx {@code REJECTED} · 전송 실패 · 타임아웃 · 5xx · 서킷 · 계약 위반 {@code UNKNOWN} · 그 밖의 예외 — 미확정</li>
     * </ul>
     */
    private CancellationStepOutcome restore(Claim claim, Order order) {
        SortedMap<Long, Integer> quantities;
        try {
            quantities = quantitiesBySkuId(claim, order);
        } catch (IllegalStateException exception) {
            log.error("cancellation claim items do not match order snapshot. claimId={}, orderId={}", claim.id(), claim.orderId());
            return CancellationStepOutcome.failed(RESTORATION_DATA_INVALID);
        }
        try {
            stockRestorationPort.restoreCancellation(claim.id(), order.stockReservationId(), quantities);
            return CancellationStepOutcome.done();
        } catch (StockReservationException exception) {
            String code = switch (exception.getFailure()) {
                case NOT_CONFIRMED -> "PRODUCT_027";
                case QUANTITY_EXCEEDED -> "PRODUCT_028";
                case RESTORATION_MISMATCH -> "PRODUCT_029";
                case NOT_FOUND -> "PRODUCT_003";
                default -> null;
            };
            if (code != null) {
                return CancellationStepOutcome.failed(code);
            }
            log.warn("cancellation stock restoration failed, will retry. claimId={}, failure={}", claim.id(), exception.getFailure());
            return CancellationStepOutcome.retry();
        } catch (RuntimeException exception) {
            log.warn("cancellation stock restoration call failed, will retry. claimId={}, error={}", claim.id(), exception.getClass().getSimpleName());
            return CancellationStepOutcome.retry();
        }
    }

    /** 주문 버전 충돌(출고 · 다른 상태 변경과 경합)은 warn, 클레임 버전 충돌(다른 워커가 처리)은 info 로 나눠 남긴다. */
    private static void logLostVersion(Claim claim, OptimisticLockingFailureException exception) {
        String entity = exception instanceof ObjectOptimisticLockingFailureException objectLock ? objectLock.getPersistentClassName() : null;
        if (entity != null && entity.endsWith("OrderEntity")) {
            log.warn("cancellation step lost to a concurrent order change, stopping. claimId={}, orderId={}, status={}", claim.id(), claim.orderId(),
                claim.status());
            return;
        }
        log.info("cancellation claim progressed elsewhere, stopping. claimId={}, status={}", claim.id(), claim.status());
    }

    /** 클레임 항목(주문 항목 아이디 · 수량)을 주문 스냅숏의 SKU 로 바꿔 SKU 아이디 오름차순으로 합친다 — 같은 클레임이면 늘 같은 값이다. */
    static SortedMap<Long, Integer> quantitiesBySkuId(Claim claim, Order order) {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (ClaimItem item : claim.items()) {
            int index = order.itemIds().indexOf(item.orderItemId());
            if (index < 0) {
                throw new IllegalStateException("claim item not in order: claimId=" + claim.id() + ", orderItemId=" + item.orderItemId());
            }
            OrderQuoteLine line = order.quote().lines().get(index);
            quantities.merge(line.skuId(), item.quantity(), Math::addExact);
        }
        return quantities;
    }
}
