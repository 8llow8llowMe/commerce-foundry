package com.commercefoundry.domainlayer.order.application.exception;

/**
 * {@code ClaimRepositoryPort#insert} 의 실패 — 같은 주문 · 같은 요청 키의 클레임이 이미 있다({@code uk_claim_order_id_request_key}, #16 PR-2).
 * 결제 후 전체 취소는 키가 결정적이라 이 위반이 "같은 취소가 먼저 접수됨" 이다 — 부른 쪽이 다시 읽어 재생한다. 그 밖의 제약 위반은 이 예외가 아니다.
 *
 * <p><b>원인 예외를 싣지 않는다</b> — 제약 위반 메시지에 행 값이 실린다({@link OrderSaveException} 과 같다).
 */
public class ClaimRequestKeyConflictException extends RuntimeException {

    public ClaimRequestKeyConflictException() {
        super("claim request key already used");
    }
}
