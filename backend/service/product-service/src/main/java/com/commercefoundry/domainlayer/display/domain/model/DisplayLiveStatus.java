package com.commercefoundry.domainlayer.display.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전시 섹션의 지금 노출 상태 — 어드민이 "왜 안 보이는지" 를 노출 칸 · 기간을 직접 읽지 않고 알 수 있게 한다. 저장하지 않고
 * {@link DisplaySection#liveStatusAt} 이 노출 설정 · 기간 · 시각에서 유도한다. 고객 메인 필터({@link DisplaySection#isLiveAt})는 이 값이 {@link #LIVE} 인지로 판정한다.
 *
 * <p>우선순위는 숨김 → 예정 → 종료 → 노출 중이다 — 숨긴 섹션은 기간과 무관하게 숨김이다.
 */
@Getter
@RequiredArgsConstructor
public enum DisplayLiveStatus implements CodeNameDescribable {

    LIVE("노출 중", "지금 고객 메인에 보입니다."),
    HIDDEN("숨김", "노출을 꺼 두어 기간과 무관하게 고객 메인에 보이지 않습니다."),
    SCHEDULED("예정", "노출 시작 전이라 아직 고객 메인에 보이지 않습니다."),
    ENDED("종료", "노출 기간이 끝나 고객 메인에 보이지 않습니다.");

    private final String displayName;
    private final String description;
}
