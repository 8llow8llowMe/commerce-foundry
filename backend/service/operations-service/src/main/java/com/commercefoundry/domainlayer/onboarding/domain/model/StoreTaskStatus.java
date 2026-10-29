package com.commercefoundry.domainlayer.onboarding.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 업무의 스토어 공통 준비 상태 (#357) — 누가 했든 스토어에 그 설정이 있는가. 운영자 본인의 실습 완료({@link MemberTaskStatus})와 따로 본다.
 */
@Getter
@RequiredArgsConstructor
public enum StoreTaskStatus implements CodeNameDescribable {

    READY("준비됨", "스토어에 이미 설정되어 있습니다."),
    NOT_READY("준비 전", "스토어에 아직 설정되어 있지 않습니다."),
    UNAVAILABLE("확인 불가", "지금은 담당 서비스에서 확인할 수 없습니다. 잠시 후 다시 확인해 주세요.");

    private final String displayName;
    private final String description;
}
