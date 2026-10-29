package com.commercefoundry.domainlayer.onboarding.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 운영자 한 명의 업무 하나의 완료 판정 (#357). 스토어 공통 준비({@link StoreTaskStatus})와 본인 실습({@link MemberTaskStatus})을 따로 둔다.
 *
 * @param key 업무
 * @param storeStatus 스토어 공통 준비 상태
 * @param myStatus 본인 실습 상태
 * @param myCompletedAt 본인이 처음 해낸 시각(UTC). {@code myStatus} 가 {@code COMPLETED} 일 때만 있다
 */
public record OnboardingTask(OnboardingTaskKey key, StoreTaskStatus storeStatus, MemberTaskStatus myStatus, Instant myCompletedAt) {

    public OnboardingTask {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(storeStatus, "storeStatus");
        Objects.requireNonNull(myStatus, "myStatus");
        if ((myStatus == MemberTaskStatus.COMPLETED) != (myCompletedAt != null)) {
            throw new IllegalArgumentException("myCompletedAt 은 COMPLETED 일 때만 있다");
        }
    }

    /**
     * 담당 서비스의 사실로 판정한다. 본인 기록이 있으면 완료다(스토어 상태와 관계없이 — 기록은 되돌리지 않는다).
     * 본인 기록이 없으면 스토어가 준비됐을 때 {@code NO_EVIDENCE}, 아니면 {@code NOT_COMPLETED} 다.
     *
     * @param storeReady 스토어에 설정이 있는가 (누가 했든)
     * @param memberFirstSavedAt 본인이 처음 해낸 시각. 기록이 없으면 null
     */
    public static OnboardingTask fromFact(OnboardingTaskKey key, boolean storeReady, Instant memberFirstSavedAt) {
        StoreTaskStatus storeStatus = storeReady ? StoreTaskStatus.READY : StoreTaskStatus.NOT_READY;
        if (memberFirstSavedAt != null) {
            return new OnboardingTask(key, storeStatus, MemberTaskStatus.COMPLETED, memberFirstSavedAt);
        }
        return new OnboardingTask(key, storeStatus, storeReady ? MemberTaskStatus.NO_EVIDENCE : MemberTaskStatus.NOT_COMPLETED, null);
    }

    /** 담당 서비스 장애 · 타임아웃 · 서킷 오픈 · 계약 위반 — 둘 다 확인 불가다. 완료로도 미완료로도 추정하지 않는다. */
    public static OnboardingTask unavailable(OnboardingTaskKey key) {
        return new OnboardingTask(key, StoreTaskStatus.UNAVAILABLE, MemberTaskStatus.UNAVAILABLE, null);
    }
}
