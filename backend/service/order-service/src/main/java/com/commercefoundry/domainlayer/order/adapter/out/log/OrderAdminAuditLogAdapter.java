package com.commercefoundry.domainlayer.order.adapter.out.log;

import com.commercefoundry.domainlayer.order.application.port.out.OrderAdminAuditPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 어드민 주문 감사 로그 — 전용 로거 {@value #LOGGER_NAME} 에 INFO 한 줄 {@code key=value} 로 남긴다. 형식의 정본은 {@code security-guide.md} §11-4 다.
 *
 * <pre>
 * action=ORDER_VIEW actorMemberId=4611686018427387904 target=order:4611686018427387905 result=SUCCESS
 * action=ORDER_SEARCH actorMemberId=4611686018427387904 target=orders?status=PAID&amp;from=-&amp;to=-&amp;page=0&amp;size=20 result=SUCCESS
 * </pre>
 */
@Component
public class OrderAdminAuditLogAdapter implements OrderAdminAuditPort {

    public static final String LOGGER_NAME = "admin.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    @Override
    public void orderSearched(long adminMemberId, String condition) {
        write(OrderAdminAuditAction.ORDER_SEARCH, adminMemberId, condition, SUCCESS);
    }

    @Override
    public void orderViewed(long adminMemberId, long orderId) {
        write(OrderAdminAuditAction.ORDER_VIEW, adminMemberId, order(orderId), SUCCESS);
    }

    @Override
    public void orderStatusChangeSucceeded(long adminMemberId, long orderId) {
        write(OrderAdminAuditAction.ORDER_STATUS_CHANGE, adminMemberId, order(orderId), SUCCESS);
    }

    @Override
    public void orderStatusChangeFailed(long adminMemberId, long orderId) {
        write(OrderAdminAuditAction.ORDER_STATUS_CHANGE, adminMemberId, order(orderId), FAILURE);
    }

    private static String order(long orderId) {
        return "order:" + orderId;
    }

    private static void write(OrderAdminAuditAction action, long actorMemberId, String target, String result) {
        AUDIT.info("action={} actorMemberId={} target={} result={}", action, actorMemberId, target, result);
    }

    /** 감사 행위 이름. 로그 검색 키라 한번 정한 이름을 바꾸지 않는다. */
    enum OrderAdminAuditAction {
        ORDER_SEARCH,
        ORDER_VIEW,
        ORDER_STATUS_CHANGE
    }
}
