package com.commercefoundry.domainlayer.member.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.member.application.port.in.AdminAuditLogRetentionSchedulerUseCase;
import com.commercefoundry.global.properties.AdminAuditRetentionProperties;
import java.time.Duration;
import java.time.Period;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuditLogRetentionSchedulerTest {

    private static final int BATCH_SIZE = 2;
    private static final int MAX_BATCHES = 3;
    private static final long CUTOFF = 50L;

    private AdminAuditLogRetentionSchedulerUseCase useCase;
    private AdminAuditLogRetentionScheduler scheduler;

    @BeforeEach
    void setUp() {
        useCase = mock(AdminAuditLogRetentionSchedulerUseCase.class);
        given(useCase.cutoffId()).willReturn(CUTOFF);
        scheduler = new AdminAuditLogRetentionScheduler(useCase, new AdminAuditRetentionProperties(
            Period.ofYears(2), Duration.ofMinutes(1), Duration.ofHours(1), BATCH_SIZE, MAX_BATCHES
        ));
    }

    @Test
    @DisplayName("배치가 가득 차지 않으면 한 번만 지운다")
    void singleBatch() {
        given(useCase.deleteBatch(CUTOFF, BATCH_SIZE)).willReturn(1);

        scheduler.purgeExpired();

        verify(useCase).cutoffId();
        verify(useCase).deleteBatch(CUTOFF, BATCH_SIZE);
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("배치가 가득 차면 이어서 지우고, 덜 찬 배치에서 멈춘다")
    void continuesWhileFull() {
        given(useCase.deleteBatch(CUTOFF, BATCH_SIZE)).willReturn(BATCH_SIZE, 1);

        scheduler.purgeExpired();

        verify(useCase, times(2)).deleteBatch(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("배치가 계속 가득 차도 한 실행은 max-batches-per-run 번까지만 지운다")
    void stopsAtMaxBatches() {
        given(useCase.deleteBatch(CUTOFF, BATCH_SIZE)).willReturn(BATCH_SIZE);

        scheduler.purgeExpired();

        verify(useCase, times(MAX_BATCHES)).deleteBatch(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("한 배치가 실패하면 그 실행은 멈추고 다음 배치는 부르지 않는다")
    void stopsWhenABatchFails() {
        given(useCase.deleteBatch(anyLong(), anyInt())).willReturn(BATCH_SIZE).willThrow(new IllegalStateException("db down"));

        scheduler.purgeExpired();

        verify(useCase, times(2)).deleteBatch(CUTOFF, BATCH_SIZE);
    }

    @Test
    @DisplayName("지울 행이 없으면 한 번만 확인하고 끝낸다")
    void empty() {
        given(useCase.deleteBatch(CUTOFF, BATCH_SIZE)).willReturn(0);

        scheduler.purgeExpired();

        verify(useCase).cutoffId();
        verify(useCase).deleteBatch(CUTOFF, BATCH_SIZE);
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("주기는 보관 설정 키에서 읽는다")
    void scheduleUsesRetentionProperties() throws NoSuchMethodException {
        var scheduled = AdminAuditLogRetentionScheduler.class.getMethod("purgeExpired").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertThat(scheduled.initialDelayString()).isEqualTo("${auth.admin-audit.retention.initial-delay}");
        assertThat(scheduled.fixedDelayString()).isEqualTo("${auth.admin-audit.retention.fixed-delay}");
    }
}
