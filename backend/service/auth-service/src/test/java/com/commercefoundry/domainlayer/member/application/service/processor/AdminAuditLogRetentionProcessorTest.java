package com.commercefoundry.domainlayer.member.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRetentionRepositoryPort;
import com.commercefoundry.global.properties.AdminAuditRetentionProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuditLogRetentionProcessorTest {

    private static final Instant NOW = Instant.parse("2028-06-15T12:00:00Z");

    private final AdminAuditLogRetentionRepositoryPort port = mock(AdminAuditLogRetentionRepositoryPort.class);
    private final AdminAuditLogRetentionProcessor processor = new AdminAuditLogRetentionProcessor(
        port,
        new AdminAuditRetentionProperties(Period.ofYears(2), Duration.ofMinutes(1), Duration.ofHours(1), 2, 3),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("컷오프는 지금에서 보관 기간을 뺀 밀리초의 첫 아이디다 — 그 시각의 행은 대상이 아니다")
    void cutoffIsTheFirstIdOfTheRetentionBoundary() {
        assertThat(processor.cutoffId()).isEqualTo(SnowflakeIdGenerator.lowestIdAt(Instant.parse("2026-06-15T12:00:00Z")));
    }

    @Test
    @DisplayName("고른 행이 없으면 DELETE 를 보내지 않는다")
    void skipsDeleteWhenNothingIsDue() {
        when(port.findIdsOlderThan(8L, 2)).thenReturn(List.of());

        assertThat(processor.deleteBatch(8L, 2)).isZero();

        verify(port, never()).deleteByIds(List.of());
    }

    @Test
    @DisplayName("고른 아이디만 지우고, 고른 건수를 돌려준다")
    void deletesTheSelectedIds() {
        when(port.findIdsOlderThan(8L, 2)).thenReturn(List.of(1L, 2L));

        assertThat(processor.deleteBatch(8L, 2)).isEqualTo(2);

        verify(port).deleteByIds(List.of(1L, 2L));
    }
}
