package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request;

/**
 * 전시 요청 문자열 정규화 — 앞뒤 공백({@code strip()}, 전각 공백 포함)을 빼고, 비면 없는 값(null)으로 본다. 요청 DTO 끼리만 쓴다.
 */
final class TextValues {

    private TextValues() {
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
