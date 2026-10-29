package com.commercefoundry.domainlayer.onboarding.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 운영자 본인의 업무 실습 상태 (#357). 행위자 · 시각 증거가 있을 때만 완료다 — 다른 직원이 만든 설정이나 이력 없는 과거 설정을 본인 완료로 추정하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum MemberTaskStatus implements CodeNameDescribable {

    COMPLETED("완료", "직접 해 본 기록이 있습니다."),
    NOT_COMPLETED("미완료", "아직 직접 해 보지 않았습니다."),
    /** 스토어에는 설정이 있지만 본인이 한 기록이 없다 — 다른 직원이 했거나 이력이 남기 전의 설정이다. 완료로 세지 않는다. */
    NO_EVIDENCE("기록 없음", "스토어에는 설정되어 있지만 직접 한 기록은 없습니다."),
    UNAVAILABLE("확인 불가", "지금은 담당 서비스에서 확인할 수 없습니다. 잠시 후 다시 확인해 주세요.");

    private final String displayName;
    private final String description;
}
