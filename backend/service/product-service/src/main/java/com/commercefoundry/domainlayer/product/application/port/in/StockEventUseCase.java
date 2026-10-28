package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.application.command.OrderStockReleaseCommand;
import com.commercefoundry.domainlayer.product.application.model.StockReleaseOutcome;

/**
 * 다른 서비스의 이벤트(Kafka)가 부르는 재고 처리 ({@code order-flow-guide.md} §3-5). 진입은 {@code adapter/in/event} 리스너다.
 */
public interface StockEventUseCase {

    /**
     * 주문 만료 · 결제 전 취소로 선점을 푼다. RESERVED 면 RELEASED + 재고 복원, 그 밖에는 아무것도 바꾸지 않고 결과만 알린다 —
     * 확정된 선점은 풀지 않는다. 동시 변경(@Version)은 {@code PRODUCT_026} 으로 롤백된다.
     */
    StockReleaseOutcome release(OrderStockReleaseCommand command);
}
