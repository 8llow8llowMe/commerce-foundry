package com.commercefoundry.domainlayer.notification.application.port.out.query;

import java.util.List;

public record AdminNotificationRecipientsQueryResult(List<Long> memberIds) {
    public AdminNotificationRecipientsQueryResult {
        memberIds = List.copyOf(memberIds);
        if (memberIds.isEmpty() || memberIds.size() > 1000) {
            throw new IllegalArgumentException("Invalid anomaly recipients");
        }
        long previous = 0;
        for (Long id : memberIds) {
            if (id == null || id <= previous) {
                throw new IllegalArgumentException("Invalid anomaly recipients");
            }
            previous = id;
        }
    }

    @Override
    public String toString() {
        return "AdminNotificationRecipientsQueryResult[redacted]";
    }
}
