package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.domain.model.OrderPayment;
import java.util.Optional;

/**
 * 결제 반영 기록 (#308, {@code orders.order_payment}) — 결제당 한 행, 주문당 결제 완료 하나.
 *
 * <p>행은 추가만 한다. PR-C 가 더할 복원 칸 갱신({@code restoration_claim_id} · {@code restored_at})은 읽고 merge 하지 않고 조건부 UPDATE 한 문장으로 한다 —
 * {@code ... set restoration_claim_id = :claimId where id = :id and restoration_claim_id is null},
 * {@code ... set restored_at = :now where id = :id and restored_at is null}. 0행이면 다른 처리가 먼저 채운 것이다(lost update 방지).
 */
public interface OrderPaymentRepositoryPort {

    /**
     * 새 기록을 넣고 바로 flush 한다 — 유니크 위반을 이 자리에서 사유로 가른다. 호출 Processor 의 트랜잭션을 필수로 요구한다(주문 상태 · 이력 · inbox · 회신 outbox 와 한 커밋).
     *
     * <p>결제 승인 컨슈머(PR-B2)의 권장 순서: 주문 저장({@code saveStatusChange} — {@code @Version} 비교 flush) → 이 {@code insert} → inbox {@code record} → 회신 outbox.
     * 주문 버전 충돌이 먼저 드러나고, 기록 유니크 위반이 그다음, inbox 중복은 커밋 flush 에서 드러난다. inbox 를 이 호출 전에 넣었다면 이 호출의 첫 flush 가
     * 그 중복을 {@code EVENT_ALREADY_PROCESSED} 로 가른다 — 그 밖의 앞선 쓰기 실패는 원래 예외 그대로다.
     *
     * @return 저장된 기록 — 생성 시각이 채워진다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderPaymentSaveException 사유는 {@code Reason}. 트랜잭션은 rollback-only 가 된다 —
     *     새 트랜잭션에서 다시 읽어 판정한다
     * @throws IllegalArgumentException 이미 저장된 기록일 때
     */
    OrderPayment insert(OrderPayment orderPayment);

    /** 결제의 반영 기록. 없으면 빈 값이다. */
    Optional<OrderPayment> findByPaymentId(long paymentId);

    /** 주문의 결제 완료 기록(결과 PAID). 없으면 빈 값이다. */
    Optional<OrderPayment> findPaidByOrderId(long orderId);
}
