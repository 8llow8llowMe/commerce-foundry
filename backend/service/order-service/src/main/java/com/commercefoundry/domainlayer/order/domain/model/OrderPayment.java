package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 결제 반영 기록 (#308) — payment-service 의 결제 승인({@code payment-approved} 이벤트 + 재조회)을 주문에 반영한 결과 한 건이다. 결제당 한 행이고
 * ({@code payment_id} 유니크), 결제 완료(PAID)는 주문당 하나다({@code paid_order_id} 유니크). 주문 행 · {@link Order} 는 결제를 모른다 — 결제 연결은 여기에만 둔다.
 *
 * <p>추가만 한다 — 바뀌는 칸은 확정 뒤 취소가 이긴 경합의 재고 복원(#308 PR-C)이 채우는 {@code restorationClaimId} · {@code restoredAt} 뿐이고,
 * 그 갱신은 조건부 UPDATE(아직 비어 있을 때만)로 한다 — {@code OrderPaymentRepositoryPort} 참고.
 *
 * <p><b>확정 선점은 결제 완료 · 취소 주문 거절에만 기록한다</b>({@link Result#recordsConfirmedReservation()}). 이중 결제에서 진 쪽({@link Result#REJECTED_ALREADY_PAID})이
 * 이긴 결제가 확정한 같은 선점 아이디를 들고 있으면 PR-C 복원이 이긴 주문의 재고를 되돌려 초과 판매가 된다. 복원 클레임은 취소 주문 거절에만 있다.
 *
 * @param orderId 이벤트가 가리킨 주문 아이디 — {@link Result#REJECTED_ORDER_NOT_FOUND} 면 없는 주문이다
 * @param memberId 결제한 회원 아이디 (payment 재조회 값)
 * @param totalAmount 결제 금액 (payment 재조회 값, 최소 단위 정수 + 통화)
 * @param approvedAt 결제 승인 시각 (payment 재조회 값, 마이크로초로 자른다). 결제 완료면 필수, 거절이면 없을 수 있다
 * @param stockReservationId 확정 200 을 받은 재고 선점 아이디. 결제 완료면 필수, 취소 주문 거절이면 있을 수 있고, 그 밖의 결과는 null
 * @param eventId 반영한 이벤트 아이디 (봉투 eventId) — 같은 결제를 다른 eventId 로 다시 받아도 행은 하나다
 * @param restorationClaimId 확정한 선점을 되돌리는 재고 복원의 클레임 아이디 (PR-C). 확정 선점이 있는 취소 주문 거절({@link Result#REJECTED_ORDER_CANCELED})에만
 * @param restoredAt 재고 복원 200 을 받은 시각 (PR-C). 복원 클레임이 있을 때만
 * @param createdAt 저장된 시각(읽기 전용). 저장 전이면 null
 */
public record OrderPayment(
    long id, long orderId, long paymentId, long memberId, Money totalAmount, Instant approvedAt, Result result, Long stockReservationId, UUID eventId,
    Long restorationClaimId, Instant restoredAt, Instant createdAt
) {

    public OrderPayment {
        if (id <= 0 || orderId <= 0 || paymentId <= 0 || memberId <= 0) {
            throw new IllegalArgumentException("positive identifiers required");
        }
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(eventId, "eventId");
        if (totalAmount.amount() <= 0) {
            throw new IllegalArgumentException("positive payment amount required");
        }
        if ((stockReservationId != null && stockReservationId <= 0) || (restorationClaimId != null && restorationClaimId <= 0)) {
            throw new IllegalArgumentException("positive reservation and claim identifiers required");
        }
        if (result == Result.PAID && (stockReservationId == null || approvedAt == null)) {
            throw new IllegalArgumentException("결제 완료는 확정한 재고 선점 · 승인 시각이 있어야 합니다: orderId=" + orderId);
        }
        if (stockReservationId != null && !result.recordsConfirmedReservation()) {
            throw new IllegalArgumentException("확정 선점은 결제 완료 · 취소 주문 거절에만 기록합니다: orderId=" + orderId + ", result=" + result);
        }
        if (restorationClaimId != null && (result != Result.REJECTED_ORDER_CANCELED || stockReservationId == null)) {
            throw new IllegalArgumentException("재고 복원은 확정 선점이 있는 취소 주문 거절에만 있습니다: orderId=" + orderId);
        }
        if (restoredAt != null && restorationClaimId == null) {
            throw new IllegalArgumentException("복원 시각은 복원 클레임이 있을 때만 있습니다: orderId=" + orderId);
        }
        approvedAt = approvedAt == null ? null : approvedAt.truncatedTo(ChronoUnit.MICROS);
        restoredAt = restoredAt == null ? null : restoredAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 결제 완료 반영 — {@code paid} 는 {@link Order#markPaid} 로 바뀐 주문이다(선점 아이디가 확정한 선점).
     *
     * @throws IllegalArgumentException 주문이 결제 완료가 아닐 때
     */
    public static OrderPayment paid(long id, Order paid, long paymentId, Money totalAmount, Instant approvedAt, UUID eventId) {
        if (paid.status() != OrderStatus.PAID) {
            throw new IllegalArgumentException("결제 완료 기록은 결제 완료된 주문에서만 만듭니다: orderId=" + paid.id() + ", status=" + paid.status());
        }
        return new OrderPayment(id, paid.id(), paymentId, paid.memberId(), totalAmount, approvedAt, Result.PAID, paid.stockReservationId(), eventId,
            null, null, null);
    }

    /**
     * 확정 전 거절 반영 — 주문은 바꾸지 않았고 확정 선점을 기록하지 않는다. 취소 주문에서 확정 선점이 있으면(확정 뒤 취소가 이긴 경합)
     * {@link #canceledAfterConfirmation} 을 쓴다.
     *
     * @throws IllegalArgumentException {@code result} 가 결제 완료일 때
     */
    public static OrderPayment rejected(long id, long orderId, long paymentId, long memberId, Money totalAmount, Instant approvedAt, Result result, UUID eventId) {
        if (result == Result.PAID) {
            throw new IllegalArgumentException("거절 기록에 결제 완료 결과를 쓸 수 없습니다");
        }
        return new OrderPayment(id, orderId, paymentId, memberId, totalAmount, approvedAt, result, null, eventId, null, null, null);
    }

    /**
     * 확정 뒤 취소가 이긴 경합의 거절 반영 — 결과 {@link Result#REJECTED_ORDER_CANCELED} 와 확정한 선점, 그 선점을 되돌릴 복원 클레임 아이디를 함께 남긴다
     * (#308 PR-C1). 복원 시각은 비어 있다 — 복원 200 뒤 조건부 UPDATE 로 채운다({@link #awaitsRestoration()}).
     *
     * @param confirmedReservationId 이 결제 처리가 확정 200 을 받았거나 해제 프로브가 확정({@code PRODUCT_024})으로 확인한 선점 아이디
     * @param restorationClaimId 재고 복원의 멱등 키 — 재시도 · 재전달 모두 이 값을 다시 쓴다
     */
    public static OrderPayment canceledAfterConfirmation(
        long id, long orderId, long paymentId, long memberId, Money totalAmount, Instant approvedAt, long confirmedReservationId, UUID eventId,
        long restorationClaimId
    ) {
        return new OrderPayment(id, orderId, paymentId, memberId, totalAmount, approvedAt, Result.REJECTED_ORDER_CANCELED, confirmedReservationId, eventId,
            restorationClaimId, null, null);
    }

    /**
     * 재고 복원이 남았는가 (#308 PR-C1) — 확정 선점 · 복원 클레임이 있는 취소 주문 거절이고 아직 복원 시각이 없다. 재전달은 이 기록의 같은 클레임 아이디로
     * 복원부터 다시 하고, 그 뒤에야 inbox · 거절 회신을 남긴다.
     */
    public boolean awaitsRestoration() {
        return result == Result.REJECTED_ORDER_CANCELED && stockReservationId != null && restorationClaimId != null && restoredAt == null;
    }

    /** 주문당 결제 완료 하나를 막는 칸 — 결제 완료면 주문 아이디, 아니면 null ({@code uk_order_payment_paid_order_id}). */
    public Long paidOrderId() {
        return result == Result.PAID ? orderId : null;
    }

    public boolean isNew() {
        return createdAt == null;
    }

    /**
     * 반영 결과. 거절은 payment 로 보내는 회신 {@code order-payment-rejected} 의 {@code reason} 과 일대일이다({@link #rejectionReason()}, 30자 이하).
     * 더하기만 한다 — DB check(이름 없는 Hibernate enum check)가 값 목록을 가져 값을 더하면 운영 DDL 로 check 를 지우고 다시 만든다.
     */
    public enum Result {
        /** 주문을 결제 완료로 바꿨다. */
        PAID(null),
        /** 결제 전 취소된 주문 — 확정 · 재선점 없이 거절한다(확정 뒤 취소가 이기면 확정한 선점을 기록하고 PR-C 가 복원한다). */
        REJECTED_ORDER_CANCELED("ORDER_CANCELED"),
        /** 이벤트가 가리킨 주문이 없다. */
        REJECTED_ORDER_NOT_FOUND("ORDER_NOT_FOUND"),
        /** 재조회한 결제와 주문(금액 · 통화 · 회원)이 맞지 않는다. */
        REJECTED_PAYMENT_MISMATCH("PAYMENT_MISMATCH"),
        /** 만료 주문의 재선점이 재고 부족 · 판매 불가였다. */
        REJECTED_STOCK_UNAVAILABLE("STOCK_UNAVAILABLE"),
        /** 다른 결제로 이미 결제 완료된 주문이다. 확정 선점을 기록하지 않는다(이긴 결제의 선점이다). */
        REJECTED_ALREADY_PAID("ALREADY_PAID"),
        /** 재조회한 결제가 승인(DONE)이 아니다 — 부분 취소처럼 돈이 남은 상태 포함. 판정은 컨슈머(PR-B2)가 한다. */
        REJECTED_PAYMENT_NOT_DONE("PAYMENT_NOT_DONE");

        private final String rejectionReason;

        Result(String rejectionReason) {
            this.rejectionReason = rejectionReason;
        }

        /** 거절 회신 사유 코드. 결제 완료면 null. */
        public String rejectionReason() {
            return rejectionReason;
        }

        /** 확정한 재고 선점을 기록하는 결과인가 — 결제 완료 · 취소 주문 거절만이다. DB check {@code ck_order_payment_reservation_scope} 와 같다. */
        public boolean recordsConfirmedReservation() {
            return this == PAID || this == REJECTED_ORDER_CANCELED;
        }
    }

    @Override
    public String toString() {
        return "OrderPayment[id=" + id + ", orderId=" + orderId + ", paymentId=" + paymentId + ", result=" + result + "]";
    }
}
