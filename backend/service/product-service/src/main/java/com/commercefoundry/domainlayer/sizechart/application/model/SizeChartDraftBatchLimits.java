package com.commercefoundry.domainlayer.sizechart.application.model;

/**
 * 사이즈표 초안 일괄 제출의 상한 단일 정본 (#737).
 * 진입 DTO 의 검증 어노테이션 · 검증 메시지 · 오류 코드 메시지 · Processor 가 같은 값을 참조한다 — DTO 는 Processor 를 참조할 수 없어 application/model 에 둔다.
 */
public final class SizeChartDraftBatchLimits {
    /** 한 번에 제출할 수 있는 서로 다른 상품 수. 항목마다 원격 등록을 순서대로 호출하므로 요청 시간을 묶어 두는 상한이다. */
    public static final int MAX_ITEMS = 10;

    private SizeChartDraftBatchLimits() {
    }
}
