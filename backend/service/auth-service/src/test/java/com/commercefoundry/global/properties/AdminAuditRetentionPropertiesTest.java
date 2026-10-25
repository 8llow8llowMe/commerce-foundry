package com.commercefoundry.global.properties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuditRetentionPropertiesTest {

    @Test
    @DisplayName("보관 기간 2년 · 1년 · 365일은 통과하고, 1년 미만 · 0 이하 주기 · 배치는 기동을 막는다")
    void retentionPeriodHasAOneYearFloor() {
        assertThatCode(() -> properties(Period.ofYears(2))).doesNotThrowAnyException();
        assertThatCode(() -> properties(Period.ofYears(1))).doesNotThrowAnyException();
        assertThatCode(() -> properties(Period.ofDays(365))).doesNotThrowAnyException();

        assertThatThrownBy(() -> properties(Period.ofDays(364))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Period.ofMonths(11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(Period.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminAuditRetentionProperties(Period.ofYears(2), Duration.ZERO, Duration.ofHours(1), 1000, 50))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdminAuditRetentionProperties(Period.ofYears(2), Duration.ofMinutes(1), Duration.ofHours(1), 0, 50))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static AdminAuditRetentionProperties properties(Period period) {
        return new AdminAuditRetentionProperties(period, Duration.ofMinutes(1), Duration.ofHours(1), 1000, 50);
    }
}
