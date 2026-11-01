package com.commercefoundry.domainlayer.order.application.port.in;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.List;

/** 어드민 주문 관리 (#17 PR-1) — 목록 · 상세 · 상태 변경 · 일괄 상태 변경. 클레임 · 환불은 PR-2 다. */
public interface OrderAdminUseCase {

    /** 열람을 감사로 남긴다. */
    PageResponse<OrderAdminItem> getOrders(OrderAdminSearchCommand command, long adminId);

    /** 없는 주문이면 {@code NOT_FOUND_ORDER}(404). 열람을 감사로 남긴다. */
    OrderAdminResponse getOrder(long orderId, long adminId);

    /**
     * 주문 하나의 상태를 바꾸고 바뀐 상세를 돌려준다. 이미 그 상태면 바꾸지 않고 지금 상세다.
     *
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderException {@code ORDER_001} · {@code ORDER_020} · {@code ORDER_023} ·
     *     {@code ORDER_024} · {@code ORDER_025}
     */
    OrderAdminResponse changeStatus(long orderId, OrderStatusChangeCommand command, long adminId);

    /** 주문마다 따로 바꾼다 — 한 건이 실패해도 나머지는 바뀐다. 결과는 요청 순서다. */
    OrderStatusBulkChangeResponse changeStatuses(List<Long> orderIds, OrderStatusChangeCommand command, long adminId);
}
