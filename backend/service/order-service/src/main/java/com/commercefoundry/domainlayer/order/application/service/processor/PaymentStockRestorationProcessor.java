package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.PaymentQueryException;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.application.model.StockRestorationTarget;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.PaymentQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration;
import com.commercefoundry.global.properties.OrderPaymentRestorationProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 반영 재고 복원 스윕의 단계 (#582, {@code order-flow-guide.md} §3-6) — DB 구간은 짧은 트랜잭션, 원격 호출(복원 · 결제 재조회)은 트랜잭션 없이 한다.
 * 복원은 컨슈머와 같은 인자(작업의 클레임 · 선점 아이디, 주문 스냅숏의 SKU 별 수량 합)로 부르고, 실패 사유를 {@link RestoreOutcome} 으로 보존한다 —
 * 컨슈머 경로({@code OrderStockProcessor#restoreCanceledPayment})는 사유를 오류 코드로 접어 HELD 사유를 남길 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStockRestorationProcessor {

    private final OrderStockRestorationRepositoryPort orderStockRestorationRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;
    private final OrderRepositoryPort orderRepositoryPort;
    private final StockRestorationPort stockRestorationPort;
    private final PaymentQueryPort paymentQueryPort;
    private final OrderPaymentRestorationProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true, timeout = 10)
    public List<OrderStockRestoration> findDueTargets() {
        return orderStockRestorationRepositoryPort.findDueRestorations(clock.instant(), properties.sweep().batchSize());
    }

    /** 원격 호출 전 선점 — 다음 시각을 backoff 뒤로 미루는 조건부 UPDATE 를 먼저 커밋한다. 다른 처리가 먼저 집었으면 빈 값. */
    @Transactional(timeout = 5)
    public Optional<OrderStockRestoration> claim(OrderStockRestoration target) {
        OrderStockRestoration claimed = target.claimAttempt(clock.instant(), properties.sweep().initialBackoff(), properties.sweep().maxBackoff());
        return orderStockRestorationRepositoryPort.claim(target, claimed, clock.instant()) ? Optional.of(claimed) : Optional.empty();
    }

    /**
     * 재검증 — 컨슈머의 재전달 경로({@code OrderPaymentProcessor#complete})와 같은 조건이다: 결제 기록이 같은 주문 · 선점 · 클레임의 복원 대기이고,
     * 주문이 있고 회원 · 금액이 기록과 같고, 같은 주문의 결제 완료 기록이 없고, 주문이 결제 대기 · 만료 · 취소다.
     */
    @Transactional(readOnly = true, timeout = 10)
    public Revalidation revalidate(OrderStockRestoration claimed) {
        Optional<OrderPayment> found = orderPaymentRepositoryPort.findByPaymentId(claimed.paymentId());
        if (found.isPresent() && found.get().restorationClaimId() != null && found.get().restorationClaimId() == claimed.claimId()
            && found.get().restoredAt() != null) {
            return new Revalidation(null, true);
        }
        Optional<Order> order = found.flatMap(record -> orderRepositoryPort.findById(record.orderId()));
        if (found.isEmpty() || order.isEmpty() || !matches(claimed, found.get(), order.get())
            || orderPaymentRepositoryPort.findPaidByOrderId(claimed.orderId()).isPresent()) {
            log.error("payment restoration revalidation mismatch, holding. restorationId={}, orderId={}, paymentId={}",
                claimed.id(), claimed.orderId(), claimed.paymentId());
            return new Revalidation(null, false);
        }
        return new Revalidation(new StockRestorationTarget(found.get(), order.get()), false);
    }

    /** 복원 — 트랜잭션 없음. 다시 불러도 같은 거절은 HELD 사유로, 그 밖은 다음 시각에 다시 한다. */
    @Transactional(propagation = Propagation.NEVER)
    public RestoreOutcome restore(OrderStockRestoration claimed, StockRestorationTarget target) {
        try {
            stockRestorationPort.restoreCancellation(claimed.claimId(), claimed.stockReservationId(), OrderStockProcessor.quantitiesBySkuId(target.order().quote()));
            return RestoreOutcome.success();
        } catch (StockReservationException exception) {
            String holdCode = switch (exception.getFailure()) {
                case NOT_CONFIRMED -> "PRODUCT_027";
                case QUANTITY_EXCEEDED -> "PRODUCT_028";
                case RESTORATION_MISMATCH -> "PRODUCT_029";
                case NOT_FOUND -> "PRODUCT_003";
                default -> null;
            };
            if (holdCode != null) {
                log.error("payment restoration rejected permanently, holding. restorationId={}, orderId={}, reservationId={}, claimId={}, failure={}",
                    claimed.id(), claimed.orderId(), claimed.stockReservationId(), claimed.claimId(), exception.getFailure());
                return RestoreOutcome.held(holdCode);
            }
            log.warn("payment restoration failed, retrying later. restorationId={}, orderId={}, failure={}", claimed.id(), claimed.orderId(), exception.getFailure());
            return RestoreOutcome.retry();
        } catch (RuntimeException exception) {
            log.warn("payment restoration failed, retrying later. restorationId={}, orderId={}, error={}", claimed.id(), claimed.orderId(),
                exception.getClass().getSimpleName());
            return RestoreOutcome.retry();
        }
    }

    /**
     * 결제가 전액 취소됐는가 — 트랜잭션 없음. 컨슈머 마무리와 같은 규칙(재조회 상태 {@code CANCELED})이다. 조회하지 못하면 빈 값 — 이번 실행은 넘기고
     * 다음 시각에 같은 클레임으로 복원부터 다시 한다(같은 클레임이라 200).
     */
    @Transactional(propagation = Propagation.NEVER)
    public Optional<Boolean> paymentFullyCanceled(OrderStockRestoration claimed) {
        try {
            Optional<PaymentQueryResult> payment = paymentQueryPort.findPayment(claimed.paymentId());
            if (payment.isEmpty() || payment.get().paymentId() != claimed.paymentId()) {
                log.error("payment restoration payment lookup missing. restorationId={}, paymentId={}", claimed.id(), claimed.paymentId());
                return Optional.empty();
            }
            return Optional.of("CANCELED".equals(payment.get().statusCode()));
        } catch (PaymentQueryException exception) {
            log.warn("payment restoration payment lookup failed. restorationId={}, failure={}", claimed.id(), exception.getFailure());
            return Optional.empty();
        }
    }

    /** HELD — 선점한 그대로일 때만. 다시 집지 않는다(운영 SQL 로 되돌린다, {@code order-flow-guide.md} §3-6). */
    @Transactional(timeout = 5)
    public boolean hold(OrderStockRestoration claimed, String holdCode) {
        return orderStockRestorationRepositoryPort.hold(claimed, claimed.hold(holdCode), clock.instant());
    }

    /** 결제 기록이 이미 복원 시각을 가졌는데 작업만 남았다 — 작업만 끝낸다(회신 없음). */
    @Transactional(timeout = 5)
    public void markRestoredOnly(OrderStockRestoration claimed) {
        orderStockRestorationRepositoryPort.markRestored(claimed.claimId(), clock.instant());
    }

    private static boolean matches(OrderStockRestoration claimed, OrderPayment record, Order order) {
        return record.awaitsRestoration() && record.orderId() == claimed.orderId() && record.stockReservationId() == claimed.stockReservationId()
            && record.restorationClaimId() == claimed.claimId() && order.memberId() == record.memberId() && order.quote().totalAmount().equals(record.totalAmount())
            && (order.isAwaitingPaymentApproval() || order.status() == OrderStatus.CANCELED);
    }

    /**
     * @param target 복원할 대상. 불일치 · 이미 복원이면 null
     * @param alreadyRestored 결제 기록이 이미 복원 시각을 가졌다
     */
    public record Revalidation(StockRestorationTarget target, boolean alreadyRestored) {
    }

    /** @param holdCode HELD 사유. 복원됨 · 재시도면 null */
    public record RestoreOutcome(boolean restored, String holdCode) {

        static RestoreOutcome success() {
            return new RestoreOutcome(true, null);
        }

        static RestoreOutcome held(String holdCode) {
            return new RestoreOutcome(false, holdCode);
        }

        static RestoreOutcome retry() {
            return new RestoreOutcome(false, null);
        }
    }
}
