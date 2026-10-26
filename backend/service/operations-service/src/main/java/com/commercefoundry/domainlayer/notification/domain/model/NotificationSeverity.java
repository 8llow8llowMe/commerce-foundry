package com.commercefoundry.domainlayer.notification.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민 알림 심각도 ({@code coding-conventions.md} §11, #213).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationSeverity implements CodeNameDescribable {

    INFO("안내", "확인하면 됩니다."),
    WARNING("주의", "곧 처리하는 편이 좋습니다."),
    CRITICAL("긴급", "바로 확인이 필요합니다.");

    private final String displayName;
    private final String description;
}
