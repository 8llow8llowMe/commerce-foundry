package com.commercefoundry.domainlayer.display.domain.model;

/**
 * 전시 섹션 규칙 위반 — domain 은 application 예외를 모르므로 값으로 돌려주고, Processor 가 오류 코드로 바꾼다 ({@code coding-conventions.md} §8-1).
 *
 * @param itemType 아이템 수 · 유형 위반이면 그 아이템 유형, 아니면 null
 */
public record DisplayViolation(Kind kind, DisplayItemType itemType) {

    public static DisplayViolation of(Kind kind) {
        return new DisplayViolation(kind, null);
    }

    public enum Kind {

        /** 노출 종료 시각이 시작 시각보다 뒤가 아니다. */
        PERIOD_INVALID,

        /** 링크 주소가 {@code /} 로 시작하는 상대 경로나 https 절대 주소가 아니다. */
        LINK_URL_INVALID,

        /** 링크 문구만 있고 링크 주소가 없다. */
        LINK_LABEL_WITHOUT_URL,

        /** 섹션 유형이 받지 않는 아이템 유형이다 (예: 히어로의 상품). */
        ITEM_TYPE_NOT_ALLOWED,

        /** 섹션 유형이 정한 아이템 수 범위를 벗어났다. */
        ITEM_COUNT_OUT_OF_RANGE,

        /** 한 섹션에 같은 상품이 두 번 있다. */
        PRODUCT_DUPLICATED
    }
}
