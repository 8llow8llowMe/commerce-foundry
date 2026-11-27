package com.commercefoundry.domainlayer.sizechart.application.info;

/** 원문 HTML은 포함하지 않는다. sourceId는 DB 식별자가 아니라 본문 안의 후보 토큰이다. */
public record SizeChartTextSourceInfo(String sourceId, String bodyHash, String sourceText) {
    @Override
    public String toString() {
        return "SizeChartTextSourceInfo[redacted]";
    }
}
