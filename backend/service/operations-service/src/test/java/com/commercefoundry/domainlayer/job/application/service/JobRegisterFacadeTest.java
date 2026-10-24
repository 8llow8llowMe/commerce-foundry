package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handler;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.application.command.JobRegisterCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.info.JobRegistrationInfo;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRegistrationProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 등록 Facade — 실제 Processor 와 가짜 저장소로 멱등 등록을 본다. 실제 유니크 키는 {@code JobPersistenceAdapterTest} 가 본다. */
class JobRegisterFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

    private InMemoryJobRepositoryPort repository;
    private JobRegisterFacade facade;

    @BeforeEach
    void setUp() {
        repository = new InMemoryJobRepositoryPort();
        JobHandlerProcessor handlerProcessor = new JobHandlerProcessor(
            handlers(handler("REPORT_EXPORT"), handler("ONCE", new JobHandlerOptions(null, 1, null, null))), properties()
        );
        JobRegistrationProcessor registrationProcessor = new JobRegistrationProcessor(
            repository, handlerProcessor, new SnowflakeIdGenerator(1, 1), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        facade = new JobRegisterFacade(registrationProcessor);
    }

    @Test
    @DisplayName("새 작업은 QUEUED · 예약 시각 now · 시도 상한은 핸들러 옵션(없으면 기본 3)이다")
    void registers() {
        JobRegistrationInfo info = facade.register(JobRegisterCommand.now("REPORT_EXPORT", "{\"month\":\"2026-09\"}", 9L));
        JobRegistrationInfo once = facade.register(JobRegisterCommand.now("ONCE", "{}", null));

        Job saved = repository.get(info.jobId());
        assertThat(info.created()).isTrue();
        assertThat(info.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(saved.scheduledAt()).isEqualTo(NOW);
        assertThat(saved.maxAttempts()).isEqualTo(3);
        assertThat(saved.requestedByMemberId()).isEqualTo(9L);
        assertThat(repository.get(once.jobId()).maxAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등 키의 활성 작업이 있으면 새로 만들지 않고 그 작업을 돌려준다")
    void returnsActiveJobForSameDedupKey() {
        JobRegistrationInfo first = facade.register(command("REPORT_EXPORT@2026-09"));

        JobRegistrationInfo second = facade.register(command("REPORT_EXPORT@2026-09"));

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(second.created()).isFalse();
    }

    @Test
    @DisplayName("멱등 키는 종류마다 따로다 — 다른 종류가 같은 키를 써도 서로 막지 않는다")
    void dedupKeyIsScopedByType() {
        JobRegistrationInfo report = facade.register(command("2026-09"));

        JobRegistrationInfo once = facade.register(new JobRegisterCommand("ONCE", "{}", "2026-09", null, 0, null));

        assertThat(once.created()).isTrue();
        assertThat(once.jobId()).isNotEqualTo(report.jobId());
    }

    @Test
    @DisplayName("같은 키의 작업이 끝났으면 다시 등록된다 — 활성 멱등 키가 비었다")
    void registersAgainAfterFinished() {
        JobRegistrationInfo first = facade.register(command("REPORT_EXPORT@2026-09"));
        Job stored = repository.get(first.jobId());
        repository.overwrite(stored.requestCancel(null, NOW));

        JobRegistrationInfo second = facade.register(command("REPORT_EXPORT@2026-09"));

        assertThat(second.created()).isTrue();
        assertThat(second.jobId()).isNotEqualTo(first.jobId());
    }

    @Test
    @DisplayName("동시 등록에서 유니크 키에 지면 새 트랜잭션에서 이긴 작업을 읽어 돌려준다")
    void returnsWinnerOnDedupConflict() {
        Job winner = Job.register(777L, "REPORT_EXPORT", "{}", "REPORT_EXPORT@2026-09", null, 0, NOW, 3);
        repository.loseNextInsertTo(winner);

        JobRegistrationInfo info = facade.register(command("REPORT_EXPORT@2026-09"));

        assertThat(info.jobId()).isEqualTo(777L);
        assertThat(info.created()).isFalse();
    }

    @Test
    @DisplayName("등록되지 않은 종류 · 16KiB 를 넘는 payload 는 거절한다")
    void rejectsUnknownTypeAndLargePayload() {
        assertThatThrownBy(() -> facade.register(JobRegisterCommand.now("UNKNOWN", "{}", null)))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.UNKNOWN_JOB_TYPE));
        assertThatThrownBy(() -> facade.register(JobRegisterCommand.now("REPORT_EXPORT", "x".repeat(Job.MAX_PAYLOAD_BYTES + 1), null)))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("예약 시각을 주면 그 시각부터 가져갈 수 있다")
    void scheduled() {
        JobRegistrationInfo info = facade.register(new JobRegisterCommand("REPORT_EXPORT", "{}", null, null, 5, NOW.plus(Duration.ofHours(1))));

        Job saved = repository.get(info.jobId());
        assertThat(saved.priority()).isEqualTo(5);
        assertThat(saved.isClaimable(NOW)).isFalse();
        assertThat(saved.isClaimable(NOW.plus(Duration.ofHours(1)))).isTrue();
    }

    private static JobRegisterCommand command(String dedupKey) {
        return new JobRegisterCommand("REPORT_EXPORT", "{}", dedupKey, 9L, 0, null);
    }
}
