package com.commercefoundry.domainlayer.aiusage.domain.model;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 월 한도의 기간 — {@code YYYY-MM}. 월 경계는 설정한 시간대(기본 {@code Asia/Seoul})로 자른다 — 한국 시각 11월 1일 00:00 은 UTC 10월 31일 15:00 이다.
 *
 * @param value {@code YYYY-MM}
 */
public record AiBudgetPeriod(String value) {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM");

    public AiBudgetPeriod {
        Objects.requireNonNull(value, "value");
        // 형식이 틀리면 여기서 실패한다 — char(7) 칸에 다른 모양의 값이 들어가지 않게
        YearMonth.parse(value, FORMAT);
    }

    public static AiBudgetPeriod of(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        return new AiBudgetPeriod(YearMonth.from(instant.atZone(zone)).format(FORMAT));
    }
}
