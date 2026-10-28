package com.commercefoundry.domainlayer.onboarding.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 운영자 한 명의 안내 투어 상태 (#357). 순위는 {@code NOT_STARTED < IN_PROGRESS < {COMPLETED, SKIPPED}} 다.
 * {@code NOT_STARTED} 는 행으로 저장하지 않는다 — 행이 없으면 미시작이다. 안내를 본 것이지 실제 업무를 끝낸 것이 아니다.
 */
@Getter
@RequiredArgsConstructor
public enum TourStatus implements CodeNameDescribable {

    NOT_STARTED("미시작", "아직 안내를 시작하지 않았습니다."),
    IN_PROGRESS("진행 중", "안내를 보는 중입니다."),
    COMPLETED("완료", "안내를 끝까지 봤습니다."),
    SKIPPED("건너뜀", "안내를 건너뛰었습니다.");

    private final String displayName;
    private final String description;

    /** 끝난 상태 — 완료 · 건너뜀. */
    public boolean isFinished() {
        return this == COMPLETED || this == SKIPPED;
    }
}
