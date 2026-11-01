package com.commercefoundry.domainlayer.productdraft.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AI 상품 초안 상태 (#359). 화면은 운영자가 읽는 문구({@code name} · {@code description})만 쓴다 — 모델 · 작업 내부 상태를 드러내지 않는다
 * ({@code api-design-guide.md} §2-8).
 *
 * <p>{@code EXPIRED} 는 저장하지 않고 읽을 때 판정한다({@link ProductDraft#statusAt}) — 보관 기간이 지난 진행 중 초안을 정리 작업 없이 막는다.
 * 저장 값 목록(check 제약)에는 둔다 — 나중에 만료를 기록하게 되어도 스키마를 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductDraftStatus implements CodeNameDescribable {

    REQUESTED("생성 대기", "AI 초안 생성을 기다리고 있습니다."),
    GENERATING("생성 중", "AI 가 초안을 만들고 있습니다. 잠시 뒤 다시 확인해 주세요."),
    READY("검토 대기", "초안이 준비되었습니다. 내용을 확인한 뒤 적용하거나 거절해 주세요."),
    FAILED("생성 실패", "초안을 만들지 못했습니다. 다시 생성하거나 직접 입력해 주세요."),
    APPLIED("적용 완료", "초안을 상품에 적용했습니다."),
    REJECTED("거절", "이 초안은 쓰지 않기로 했습니다."),
    EXPIRED("기간 만료", "보관 기간이 지나 더 이상 적용할 수 없습니다. 새 초안을 만들어 주세요.");

    private final String displayName;
    private final String description;

    /** 아직 끝나지 않은 상태 — 보관 기간이 지나면 만료로 보고, 운영자가 거절할 수 있다. */
    public boolean isOpen() {
        return this == REQUESTED || this == GENERATING || this == READY || this == FAILED;
    }
}
