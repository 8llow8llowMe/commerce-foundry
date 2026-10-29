package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.response.OrderCreateResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderCreateCommand;
import java.util.UUID;

/**
 * 고객 주문 생성. memberId 는 컨트롤러가 JWT 주체에서만 꺼낸다. 금액 계산은 미리보기({@code OrderQuoteWebUseCase})와 같은 코드다.
 */
public interface OrderWebUseCase {

    /**
     * 같은 회원 · 같은 키 · 같은 본문의 재요청은 같은 주문을 지금 상태로 다시 돌려준다(응답을 저장하지 않는다).
     * 미리보기의 거절에 더해 {@code ORDER_AMOUNT_CHANGED}(409) · {@code STOCK_INSUFFICIENT}(409) · {@code STOCK_RESERVATION_INCONSISTENT}(500) ·
     * {@code IDEMPOTENCY_IN_PROGRESS}(409) · {@code IDEMPOTENCY_KEY_REUSED}(409) · {@code ORDER_SAVE_FAILED}(500)
     *
     * @return 새로 만들었는지(201)와 주문
     */
    Result create(long memberId, UUID idempotencyKey, OrderCreateCommand command);

    /**
     * @param created 이 요청이 주문을 만들었으면 true(201), 이미 만든 주문을 다시 돌려주면 false(200)
     */
    record Result(boolean created, OrderCreateResponse order) {
    }
}
