package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.port.out.OrderAdminAuditPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 어드민 주문 행위 감사 (#17). Facade 는 감사 포트를 직접 쓰지 않고 이 Processor 를 부른다 (ArchUnit H11, {@code security-guide.md} §11-4).
 * 업무가 끝난 뒤(트랜잭션 밖) 부른다. 대상 문자열 만들기 같은 감사 정책을 여기 둔다.
 */
@Component
@RequiredArgsConstructor
public class OrderAdminAuditProcessor {

    private final OrderAdminAuditPort orderAdminAuditPort;

    /** 대상은 {@code orders?status=..&from=..&to=..&page=..&size=..} — 비어 있는 조건은 {@code ALL} · {@code -}. */
    public void searched(long adminMemberId, OrderAdminSearchCommand command) {
        String condition = "orders?status=" + (command.status() == null ? "ALL" : command.status().name())
            + "&from=" + (command.from() == null ? "-" : command.from())
            + "&to=" + (command.to() == null ? "-" : command.to())
            + "&page=" + command.page() + "&size=" + command.size();
        orderAdminAuditPort.orderSearched(adminMemberId, condition);
    }

    public void viewed(long adminMemberId, long orderId) {
        orderAdminAuditPort.orderViewed(adminMemberId, orderId);
    }

    public void statusChanged(long adminMemberId, long orderId, boolean succeeded) {
        if (succeeded) {
            orderAdminAuditPort.orderStatusChangeSucceeded(adminMemberId, orderId);
        } else {
            orderAdminAuditPort.orderStatusChangeFailed(adminMemberId, orderId);
        }
    }
}
