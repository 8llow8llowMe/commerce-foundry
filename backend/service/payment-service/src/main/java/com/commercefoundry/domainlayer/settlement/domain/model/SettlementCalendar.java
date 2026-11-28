package com.commercefoundry.domainlayer.settlement.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 정산 대사의 날짜 규칙 (#219, {@code payment-guide.md} §10). 기준일은 PG 매출일(soldDate) — 토스가 KST 날짜로 매긴다. 서버 시계(UTC)의 Instant 를
 * 이 시간대의 날짜 · 하루 범위로 바꾸는 일은 여기 한 곳에서만 한다.
 */
public final class SettlementCalendar {

    /** 토스 매출일의 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** PG 누락(MISSING_IN_PG) 유예 — 매출일 + 이 일수가 지나야 기록한다. PG 정산 내역이 하루 이틀 늦게 잡힐 수 있다 (사용자 결정 2026-11-17). */
    public static final int MISSING_IN_PG_GRACE_DAYS = 3;

    /**
     * 대사할 수 있는 가장 오래된 매출일 = 오늘(KST) − 이 일수. 정기 실행(PR2)은 최근 7일만 다시 보므로 넉넉한 상한이다 — 오래된 날짜를 실수로 넣어
     * 수집 안 된 과거 승인이 한꺼번에 불일치로 쌓이는 것을 막는다.
     */
    public static final int MAX_LOOKBACK_DAYS = 90;

    private SettlementCalendar() {
    }

    /** 이 시각의 KST 날짜. */
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    /** 매출일 하루의 시작(포함) — KST 00:00. */
    public static Instant startOf(LocalDate soldDate) {
        return soldDate.atStartOfDay(ZONE).toInstant();
    }

    /** 매출일 하루의 끝(제외) — 다음 날 KST 00:00. */
    public static Instant endOf(LocalDate soldDate) {
        return startOf(soldDate.plusDays(1));
    }

    /** 이 시각이 매출일 하루 {@code [00:00, 다음 날 00:00) KST} 안인가. null 이면 아니다. */
    public static boolean within(LocalDate soldDate, Instant instant) {
        return instant != null && !instant.isBefore(startOf(soldDate)) && instant.isBefore(endOf(soldDate));
    }

    /** PG 누락 유예가 지났는가 — {@code today ≥ soldDate + 3일}. */
    public static boolean graceElapsed(LocalDate soldDate, LocalDate today) {
        return !today.isBefore(soldDate.plusDays(MISSING_IN_PG_GRACE_DAYS));
    }

    /** 대사할 수 있는 매출일인가 — 오늘(KST) 이후가 아니고, 오늘 − {@value #MAX_LOOKBACK_DAYS}일보다 앞서지 않는다. */
    public static boolean reconcilable(LocalDate soldDate, LocalDate today) {
        return !soldDate.isAfter(today) && !soldDate.isBefore(today.minusDays(MAX_LOOKBACK_DAYS));
    }
}
