package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.Blocker;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.FaultRefund;
import com.commercefoundry.domainlayer.order.application.info.ClaimAdminDetailInfo.ReturnDecision;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderPaymentRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.ReturnRefundPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반품 결정 준비 (#17 B2c) — 결정 대기 반품({@link Claim#isAwaitingReturnDecision()})을 지금 승인할 수 있는지와 귀책별 정책 금액을 계산한다. 읽기만 한다.
 *
 * <p>판정 순서와 사유는 승인 API(B2c-2)의 409 와 같다 — 반품 접수가 아님(읽는 사이 승인 · 거절된 경우 포함) · 주문이 반품 요청이 아님 {@link Blocker#NOT_DECIDABLE},
 * 같은 주문의 다른 반품 환불이 진행 중({@link Claim#isReturnRefundInFlight()}) {@link Blocker#RETURN_IN_FLIGHT}, 주문 없음 · 결제 기록 없음 · 결제 금액과 주문 총액
 * 불일치 · 산식 예외 {@link Blocker#NOT_CALCULABLE}. 거절은 {@link Blocker#NOT_DECIDABLE} 만 막는다({@link ReturnDecision#rejectable()}).
 * 결제 대조는 결제 후 고객 취소({@link OrderCancellationProcessor#request})와 같다 — {@code order_payment} 의 결제 완료 기록 금액이 주문 총액과 같아야 한다. 계산 불가는 데이터 이상이라 error 로그를 남긴다(금액은 남기지 않는다).
 *
 * <p>미리보기는 잠그지 않는다 — 승인 API 는 주문을 FOR UPDATE 로 잠근 뒤 같은 판정 · 산식을 다시 한다(정본은 그쪽이다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimReturnDecisionProcessor {

    private final OrderRepositoryPort orderRepositoryPort;
    private final ClaimRepositoryPort claimRepositoryPort;
    private final OrderPaymentRepositoryPort orderPaymentRepositoryPort;

    /**
     * 반품 결정 미리보기. 결정 대기 반품이 아니면(취소 클레임 포함) 주문을 읽지 않고 {@link Blocker#NOT_DECIDABLE} 이다 — 취소 클레임은 기본 귀책도 없다(null).
     * 주문이 반품 요청이 아니면 클레임 목록 · 결제를 읽지 않는다.
     *
     * <p>트랜잭션은 부르는 쪽(어드민 상세 {@link ClaimAdminProcessor}, 읽기 전용 · 5초)에 합류한다 — 여기서는 읽기 전용만 표시하고 시간 제한을 따로 두지 않는다.
     *
     * @param claim 지금 저장된 클레임
     */
    @Transactional(readOnly = true)
    public ReturnDecision preview(Claim claim) {
        if (claim.type() != Claim.Type.RETURN) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, null);
        }
        ReturnRefundPolicy.Fault defaultFault = ReturnRefundPolicy.Fault.defaultOf(claim);
        if (!claim.isAwaitingReturnDecision()) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, defaultFault);
        }
        Optional<Order> found = orderRepositoryPort.findById(claim.orderId());
        if (found.isEmpty()) {
            log.error("return claim has no order. claimId={}, orderId={}", claim.id(), claim.orderId());
            return ReturnDecision.blocked(Blocker.NOT_CALCULABLE, defaultFault);
        }
        Order order = found.get();
        if (order.status() != OrderStatus.RETURN_REQUESTED) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, defaultFault);
        }
        List<Claim> claims = claimRepositoryPort.findAllByOrderId(order.id());
        // 클레임을 읽은 뒤 그 사이 승인 · 거절됐으면 데이터 이상이 아니라 경쟁이다 — 결정 대상 아님으로 돌리고 error 로그를 남기지 않는다
        if (claims.stream().anyMatch(current -> current.id() == claim.id() && !current.isAwaitingReturnDecision())) {
            return ReturnDecision.blocked(Blocker.NOT_DECIDABLE, defaultFault);
        }
        if (claims.stream().anyMatch(other -> other.id() != claim.id() && other.isReturnRefundInFlight())) {
            return ReturnDecision.blocked(Blocker.RETURN_IN_FLIGHT, defaultFault);
        }
        Optional<OrderPayment> payment = orderPaymentRepositoryPort.findPaidByOrderId(order.id());
        if (payment.isEmpty()) {
            log.error("return order has no payment record. claimId={}, orderId={}", claim.id(), order.id());
            return ReturnDecision.blocked(Blocker.NOT_CALCULABLE, defaultFault);
        }
        if (!payment.get().totalAmount().equals(order.quote().totalAmount())) {
            // 금액은 로그에 남기지 않는다 — 주문 · 결제 아이디로 운영자가 대조한다
            log.error("return order total differs from approved payment amount. claimId={}, orderId={}, paymentId={}", claim.id(), order.id(),
                payment.get().paymentId());
            return ReturnDecision.blocked(Blocker.NOT_CALCULABLE, defaultFault);
        }
        try {
            ReturnRefundPolicy.Settled settled = ReturnRefundPolicy.Settled.of(order, claims, claim);
            List<FaultRefund> refunds = Arrays.stream(ReturnRefundPolicy.Fault.values()).map(fault -> {
                ReturnRefundPolicy.Refund refund = ReturnRefundPolicy.refund(order, settled, claim, fault);
                return new FaultRefund(fault, refund.policyAmount(), refund.currency());
            }).toList();
            return ReturnDecision.approvable(defaultFault, refunds);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
            // 지금 전이표로는 도달하지 않는 데이터 이상(거절이 아닌 취소 클레임 · 누적 수량 초과 · 통화 불일치 등) — 메시지에 금액이 없는 예외만 남긴다
            log.error("return refund preview failed. claimId={}, orderId={}, reason={}", claim.id(), order.id(), failure.getClass().getSimpleName());
            return ReturnDecision.blocked(Blocker.NOT_CALCULABLE, defaultFault);
        }
    }
}
