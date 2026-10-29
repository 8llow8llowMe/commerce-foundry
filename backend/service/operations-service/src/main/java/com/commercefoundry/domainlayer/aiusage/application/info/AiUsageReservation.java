package com.commercefoundry.domainlayer.aiusage.application.info;

/**
 * 예약 결과. 계량 어댑터가 핸들에 담아 두었다가 정산에 넘긴다.
 *
 * @param callId             원장 호출 아이디 (Snowflake)
 * @param period             비용을 매기는 월 ({@code YYYY-MM})
 * @param estimateCostMicros 예약한 예상 비용 (micro-USD)
 */
public record AiUsageReservation(long callId, String period, long estimateCostMicros) {
}
