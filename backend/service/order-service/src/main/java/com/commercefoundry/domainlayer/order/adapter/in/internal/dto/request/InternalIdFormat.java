package com.commercefoundry.domainlayer.order.adapter.in.internal.dto.request;

/** internal 요청의 숫자 문자열 아이디 형식 (#18). 아이디는 JS 정밀도 때문에 문자열로 오가므로 형식 · long 범위를 요청 DTO 가 함께 본다. */
final class InternalIdFormat {

    /** 0 으로 시작하지 않는 1~19자리 숫자. 19자리는 long 범위를 넘을 수 있어 {@link #inRangeOrUnchecked} 가 다시 본다. */
    static final String PATTERN = "[1-9][0-9]{0,18}";

    private InternalIdFormat() {
    }

    /** 형식이 맞는 값이 long 범위 안인가. null · 형식이 틀린 값은 다른 제약이 막으므로 여기서는 true 다(같은 위반을 두 번 내지 않게). */
    static boolean inRangeOrUnchecked(String value) {
        if (value == null || !value.matches(PATTERN)) {
            return true;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
