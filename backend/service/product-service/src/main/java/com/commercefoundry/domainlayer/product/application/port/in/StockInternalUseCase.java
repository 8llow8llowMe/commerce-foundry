package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.application.command.StockReservationCommand;
import com.commercefoundry.domainlayer.product.application.command.StockRestorationCommand;

/**
 * 서비스 간 재고 선점 · 결제 후 복원 (호출자 order-service). 상태 전이 표는 {@code order-flow-guide.md} §3.
 */
public interface StockInternalUseCase {

    /**
     * 주문 하나의 재고를 선점한다. 같은 주문의 활성 선점이 있으면 항목이 같을 때 그대로 돌려준다(멱등, 200).
     * 항목이 다르면 {@code PRODUCT_023}, 판매 불가 SKU 가 있으면 {@code PRODUCT_022}, 재고가 부족하면 {@code PRODUCT_021} 이고 아무것도 잡지 않는다.
     */
    StockReservationResponse reserve(StockReservationCommand command);

    /** 결제 완료 확정. 이미 확정이면 200, 해제 · 만료됐으면 {@code PRODUCT_025}, 없으면 {@code PRODUCT_003}. */
    StockReservationResponse confirm(long reservationId);

    /** 결제 전 해제. 이미 해제면 200, 확정됐으면 {@code PRODUCT_024}, 없으면 {@code PRODUCT_003}. */
    StockReservationResponse release(long reservationId);

    /**
     * 결제 후 취소 · 반품 입고의 재고 복원. 같은 클레임의 복원이 있으면 내용이 같을 때 그대로 돌려준다(멱등, 200) — 다르면 {@code PRODUCT_029}.
     * 선점이 없으면 {@code PRODUCT_003}, 확정(CONFIRMED)이 아니면 {@code PRODUCT_027}, 복원 가능 수량을 넘으면 {@code PRODUCT_028},
     * 같은 클레임의 동시 생성이면 {@code PRODUCT_030}, 선점 잠금 대기 초과면 {@code PRODUCT_031} 이고 아무것도 되돌리지 않는다.
     */
    StockRestorationResponse restore(StockRestorationCommand command);
}
