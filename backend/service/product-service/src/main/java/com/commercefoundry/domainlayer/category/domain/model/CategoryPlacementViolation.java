package com.commercefoundry.domainlayer.category.domain.model;

/**
 * 카테고리를 어떤 상위 아래에 둘 수 없는 이유 ({@link CategoryTree#placementViolationOf}). 업무 오류 코드는 이 값을 받은 Processor 가 붙인다
 * ({@code coding-conventions.md} §8-1 — domain 은 application 예외를 모른다).
 */
public enum CategoryPlacementViolation {

    /** 상위로 지정한 카테고리가 없다. */
    PARENT_NOT_FOUND,

    /** 상위 아래에 두면 그 카테고리(하위 포함)의 깊이가 {@link CategoryTree#MAX_DEPTH} 를 넘는다. */
    DEPTH_EXCEEDED,

    /** 자기 자신이나 자기 하위를 상위로 지정했다 — 그대로 두면 계층이 순환한다. */
    CYCLE
}
