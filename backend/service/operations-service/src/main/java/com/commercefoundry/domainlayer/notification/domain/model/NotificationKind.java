package com.commercefoundry.domainlayer.notification.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민 알림 종류. 화면 표에 보이므로 {@code {code, name, description}} 으로 내린다 ({@code coding-conventions.md} §11, #213).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationKind implements CodeNameDescribable {

    JOB_SUCCEEDED("작업 완료", "백그라운드 작업이 끝났습니다."),
    JOB_FAILED("작업 실패", "백그라운드 작업이 실패했습니다."),
    JOB_CANCELLED("작업 취소", "백그라운드 작업이 취소되었습니다."),
    ANOMALY("이상 탐지", "이상 거래 · 미처리처럼 확인할 일이 생겼습니다."),
    LOW_STOCK("재고", "재고를 확인할 일이 생겼습니다."),
    CS_HANDOFF("CS 이관", "문의가 관리자에게 넘어왔습니다.");

    private final String displayName;
    private final String description;
}
