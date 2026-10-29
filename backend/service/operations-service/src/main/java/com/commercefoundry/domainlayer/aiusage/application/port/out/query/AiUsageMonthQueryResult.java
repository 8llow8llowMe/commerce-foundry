package com.commercefoundry.domainlayer.aiusage.application.port.out.query;

import java.time.Instant;

/** 월 카운터 한 행. 금액은 micro-USD 다. */
public record AiUsageMonthQueryResult(
    String period, long reservedMicros, long settledMicros, long callCount, long limitMicrosSnapshot, Instant warnedAt, Instant exceededAt
) {
}
