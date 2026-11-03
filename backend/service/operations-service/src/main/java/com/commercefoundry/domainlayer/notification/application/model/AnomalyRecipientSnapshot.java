package com.commercefoundry.domainlayer.notification.application.model;

import java.util.List;

/** 원격 응답 검증 후 해당 처리의 새 TX 재시도 전체에 재사용한다. */
public record AnomalyRecipientSnapshot(List<Long> memberIds) {
    public AnomalyRecipientSnapshot {
        memberIds = List.copyOf(memberIds);
    }

    @Override
    public String toString() {
        return "AnomalyRecipientSnapshot[redacted]";
    }
}
