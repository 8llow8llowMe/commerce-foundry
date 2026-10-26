package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

/** 앞뒤 유니코드 공백을 뺀다. 빈 값과 공백만인 값은 null 이다. */
final class SizeChartTextValues {

    private SizeChartTextValues() {
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
