package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/**
 * 어드민 주문 행위 감사 ({@code security-guide.md} §11-4). DB 싱크가 없어 공통 로그 유틸을 직접 쓴다.
 * Facade 가 업무 결과가 정해진 뒤(트랜잭션 밖) 부른다. 사유 · 배송지 · 실패 원인은 받지 않는다.
 */
@Component
public class OrderAdminAuditProcessor {

    /** 대상은 {@code orders?status=..&from=..&to=..&page=..&size=..} — 비어 있는 조건은 {@code ALL} · {@code -}. */
    public void searched(long adminMemberId, OrderAdminSearchCommand command) {
        String condition = "orders?status=" + (command.status() == null ? "ALL" : command.status().name())
            + "&from=" + (command.from() == null ? "-" : command.from())
            + "&to=" + (command.to() == null ? "-" : command.to())
            + "&page=" + command.page() + "&size=" + command.size();
        AdminAuditLog.write("ORDER_SEARCH", adminMemberId, condition, true);
    }

    public void viewed(long adminMemberId, long orderId) {
        AdminAuditLog.write("ORDER_VIEW", adminMemberId, AdminAuditLog.target("order", orderId), true);
    }

    public void statusChanged(long adminMemberId, long orderId, boolean succeeded) {
        AdminAuditLog.write("ORDER_STATUS_CHANGE", adminMemberId, AdminAuditLog.target("order", orderId), succeeded);
    }
}
