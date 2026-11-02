package com.commercefoundry.domainlayer.payment.application.port.out;

import com.commercefoundry.domainlayer.payment.domain.model.Payment;

/**
 * 결제 도메인 이벤트 발행 — transactional outbox({@code payment.outbox})에 넣는다 ({@code payment-guide.md} §6). 호출자 트랜잭션 안에서 불러야
 * 상태 전이 저장과 함께 커밋 · 롤백된다 — 트랜잭션 밖이거나 읽기 전용이면 예외다.
 */
public interface PaymentEventPort {

    /**
     * 결제가 승인됐다(DONE) — order-service 가 받아 결제 internal API 로 재조회한 뒤 주문을 결제 완료로 반영한다 (#308).
     *
     * @param done 저장을 마친 DONE 결제 시도 — 승인 시각 · 결제수단이 채워져 있다
     */
    void approved(Payment done);
}
