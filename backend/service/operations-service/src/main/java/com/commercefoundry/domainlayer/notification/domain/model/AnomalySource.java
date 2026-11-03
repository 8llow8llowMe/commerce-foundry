package com.commercefoundry.domainlayer.notification.domain.model;

/** 입력 원문 대신 승인된 출처만 영속화한다. 임의 DLT / 원천 이벤트를 구독하지 않는다. */
public enum AnomalySource {
    PAYMENT_APPROVED("payment.payment-approved.dlt", NotificationSeverity.CRITICAL, "결제 이벤트 처리 이상을 확인해 주세요", "/admin/payments"),
    ORDER_EXPIRED("order.order-expired.dlt", NotificationSeverity.WARNING, "주문 만료 이벤트 처리 이상을 확인해 주세요", "/admin/orders"),
    ORDER_CANCELED("order.order-canceled.dlt", NotificationSeverity.WARNING, "주문 취소 이벤트 처리 이상을 확인해 주세요", "/admin/orders");

    private final String topicSuffix;
    private final NotificationSeverity severity;
    private final String title;
    private final String linkPath;

    AnomalySource(String topicSuffix, NotificationSeverity severity, String title, String linkPath) {
        this.topicSuffix = topicSuffix;
        this.severity = severity;
        this.title = title;
        this.linkPath = linkPath;
    }

    public String topicSuffix() {
        return topicSuffix;
    }

    public NotificationRequest notification(long recipientMemberId, long emissionId) {
        return new NotificationRequest(NotificationKind.ANOMALY, severity, title, linkPath, recipientMemberId, "ANOMALY", emissionId);
    }
}
