package com.commercefoundry.domainlayer.order.application.service;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderAdminItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.item.OrderStatusChangeResultItem;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderStatusBulkChangeResponse;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.OrderAdminPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.in.OrderAdminUseCase;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 주문 관리 (#17 PR-1). 트랜잭션은 Processor 가 주문마다 건다 — 이 Facade 는 트랜잭션 밖이다.
 *
 * <p>목록 · 상세 열람과 상태 변경(성공 · 실패)을 {@code admin.audit} 로 남긴다 ({@code security-guide.md} §11-4) — 마스킹했어도 개인정보를 읽는 행위다.
 * 사유 · 배송지는 감사 줄 · 로그에 싣지 않는다. 상태 변경의 정본은 같은 트랜잭션의 주문 상태 이력이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class OrderAdminFacade implements OrderAdminUseCase {

    static final String CHANGED = "CHANGED";
    static final String UNCHANGED = "UNCHANGED";
    static final String FAILED = "FAILED";

    private final OrderAdminProcessor orderAdminProcessor;
    private final OrderAdminAuditProcessor orderAdminAuditProcessor;
    private final OrderAdminPresenter orderAdminPresenter;

    @Override
    public PageResponse<OrderAdminItem> getOrders(OrderAdminSearchCommand command, long adminId) {
        PageResponse<OrderAdminItem> page = orderAdminPresenter.toPageResponse(orderAdminProcessor.search(command));
        orderAdminAuditProcessor.searched(adminId, command);
        return page;
    }

    @Override
    public OrderAdminResponse getOrder(long orderId, long adminId) {
        OrderAdminResponse response = detail(orderId);
        orderAdminAuditProcessor.viewed(adminId, orderId);
        return response;
    }

    @Override
    public OrderAdminResponse changeStatus(long orderId, OrderStatusChangeCommand command, long adminId) {
        try {
            change(orderId, command, adminId);
        } catch (RuntimeException exception) {
            orderAdminAuditProcessor.statusChanged(adminId, orderId, false);
            throw exception;
        }
        orderAdminAuditProcessor.statusChanged(adminId, orderId, true);
        return detail(orderId);
    }

    @Override
    public OrderStatusBulkChangeResponse changeStatuses(List<Long> orderIds, OrderStatusChangeCommand command, long adminId) {
        List<OrderStatusChangeResultItem> results = new ArrayList<>(orderIds.size());
        for (long orderId : orderIds) {
            OrderStatusChangeResultItem result = changeOne(orderId, command, adminId);
            orderAdminAuditProcessor.statusChanged(adminId, orderId, !FAILED.equals(result.result()));
            results.add(result);
        }
        return orderAdminPresenter.toBulkResponse(results);
    }

    private OrderAdminResponse detail(long orderId) {
        return orderAdminPresenter.toResponse(orderAdminProcessor.getDetail(orderId));
    }

    /** 일괄 변경의 한 건 — 실패를 결과로 바꾼다. 예상 못 한 예외는 원인 타입만 남기고 {@code ORDER_026} 이다. */
    private OrderStatusChangeResultItem changeOne(long orderId, OrderStatusChangeCommand command, long adminId) {
        try {
            return result(orderId, change(orderId, command, adminId) ? CHANGED : UNCHANGED, null);
        } catch (OrderException exception) {
            return result(orderId, FAILED, exception);
        } catch (RuntimeException exception) {
            log.error("admin order status change failed. orderId={}, error={}", orderId, exception.getClass().getSimpleName());
            return result(orderId, FAILED, new OrderException(OrderErrorCode.ORDER_STATUS_CHANGE_FAILED));
        }
    }

    /** @return 바꿨으면 true. 읽은 뒤 다른 곳이 먼저 바꿨으면 {@code ORDER_025} — 다시 읽어 이미 목표 상태면 false(그대로 둔 것) */
    private boolean change(long orderId, OrderStatusChangeCommand command, long adminId) {
        boolean changed;
        try {
            changed = orderAdminProcessor.changeStatus(orderId, command, adminId);
        } catch (OptimisticLockingFailureException exception) {
            // 다른 운영자가 같은 목표로 먼저 바꿨으면 원하는 상태가 이미 됐다 — 실패가 아니라 그대로 둔 것으로 본다
            if (orderAdminProcessor.getDetail(orderId).order().status() == command.status()) {
                return false;
            }
            log.warn("admin order status change lost to a concurrent change. orderId={}, target={}", orderId, command.status());
            throw new OrderException(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED);
        }
        if (changed) {
            log.info("admin order status changed. orderId={}, target={}, adminId={}", orderId, command.status(), adminId);
        }
        return changed;
    }

    private static OrderStatusChangeResultItem result(long orderId, String result, OrderException failure) {
        return OrderStatusChangeResultItem.builder()
            .orderId(String.valueOf(orderId))
            .result(result)
            .errorCode(failure == null ? null : failure.getErrorCode().getCode())
            .message(failure == null ? null : failure.getMessage())
            .build();
    }
}
