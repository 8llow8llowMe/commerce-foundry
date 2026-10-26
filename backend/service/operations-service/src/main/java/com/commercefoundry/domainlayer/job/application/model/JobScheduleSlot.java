package com.commercefoundry.domainlayer.job.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 정기 작업이 이번에 등록할 슬롯. 멱등 키는 끝난 작업까지 같은 슬롯을 다시 만들지 않게 한다.
 *
 * @param scheduledAt 이 시각부터 가져갈 수 있다. 이미 지났으면 워커가 바로 가져간다
 * @param dedupKey {@code planner:{주기}:{존}:{로컬 시각}}
 */
public record JobScheduleSlot(Instant scheduledAt, String dedupKey) {

    public JobScheduleSlot {
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(dedupKey, "dedupKey");
    }
}
