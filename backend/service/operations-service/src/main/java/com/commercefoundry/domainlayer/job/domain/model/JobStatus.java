package com.commercefoundry.domainlayer.job.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 백그라운드 작업 상태. 전이는 {@link Job} 의 도메인 메서드로만 일어난다 — 허용 전이 표는 {@link Job} 에 있다.
 *
 * <pre>
 * QUEUED ──claim──▶ RUNNING ──succeed──▶ SUCCEEDED
 *   ▲                 │ ├──fail(재시도 남음)──▶ QUEUED
 *   │                 │ ├──fail(상한 · 재시도 불가) · lease 만료(상한)──▶ FAILED
 *   └────release──────┘ └──cancel(취소 요청을 받은 뒤)──▶ CANCELLED
 * QUEUED ──취소 요청──▶ CANCELLED
 * </pre>
 *
 * <p>어드민 작업 목록이 화면 표에 상태를 보여 주므로 {@code {code, name, description}} 메타데이터로 내린다 ({@code coding-conventions.md} §11).
 */
@Getter
@RequiredArgsConstructor
public enum JobStatus implements CodeNameDescribable {

    QUEUED("대기", "실행 차례를 기다립니다. 재시도 대기도 여기입니다."),
    RUNNING("실행 중", "워커가 가져가 실행하고 있습니다."),
    SUCCEEDED("성공", "실행을 마쳤습니다."),
    FAILED("실패", "재시도 상한에 닿았거나 다시 시도할 수 없는 오류로 끝났습니다."),
    CANCELLED("취소", "취소 요청으로 끝났습니다.");

    private final String displayName;
    private final String description;

    /** 아직 끝나지 않았다 — 같은 멱등 키({@code dedup_key})로 다시 등록하면 이 작업을 돌려준다. */
    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean isFinished() {
        return !isActive();
    }
}
