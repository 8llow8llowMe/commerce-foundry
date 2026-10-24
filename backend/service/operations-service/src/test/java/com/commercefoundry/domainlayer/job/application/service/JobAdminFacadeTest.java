package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.adapter.in.admin.presenter.JobAdminPresenter;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.model.JobStatusFilter;
import com.commercefoundry.domainlayer.job.application.port.out.JobAdminAuditPort;
import com.commercefoundry.domainlayer.job.application.service.processor.JobAdminAuditProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobQueryProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 어드민 작업 Facade — 목록 조건 · 상세 404 · 취소(QUEUED · RUNNING · 끝남 · 워커와 겹침)와 감사 로그를 가짜 저장소로 본다. */
class JobAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final long ADMIN = 9L;
    private static final String WORKER = "operations-service:test:1:00000000";

    private InMemoryJobRepositoryPort repository;
    private JobAdminAuditPort auditPort;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        repository = new InMemoryJobRepositoryPort();
        auditPort = mock(JobAdminAuditPort.class);
        events = new ArrayList<>();
    }

    private JobAdminFacade facade() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new JobAdminFacade(
            new JobQueryProcessor(repository),
            new JobRunProcessor(repository, properties(), events::add, clock),
            new JobAdminAuditProcessor(auditPort),
            new JobAdminPresenter()
        );
    }

    private Job queued(long id, String type) {
        return repository.insert(Job.register(id, type, "{\"month\":\"2026-09\"}", null, ADMIN, 0, NOW, 3));
    }

    @Test
    @DisplayName("목록은 아이디 내림차순이고 상태 · 종류로 거른다 — 전체 건수는 자르기 전 개수다")
    void searches() {
        queued(1L, "REPORT");
        queued(2L, "REPORT");
        queued(3L, "SETTLEMENT");
        repository.overwrite(repository.get(2L).claim(WORKER, NOW, Duration.ofMinutes(5)));

        PageResponse<JobAdminItem> all = facade().getJobs(new JobAdminSearchCommand(null, null, 0, 2));
        PageResponse<JobAdminItem> queuedReports = facade().getJobs(new JobAdminSearchCommand(JobStatusFilter.QUEUED, "REPORT", 0, 20));

        assertThat(all.contents()).extracting(JobAdminItem::jobId).containsExactly("3", "2");
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(queuedReports.contents()).extracting(JobAdminItem::jobId).containsExactly("1");
        assertThat(queuedReports.contents().getFirst().status().code()).isEqualTo("QUEUED");
        assertThat(queuedReports.contents().getFirst().requestedByMemberId()).isEqualTo("9");
    }

    @Test
    @DisplayName("없는 작업 상세는 404(JOB_001)다")
    void notFound() {
        assertThatThrownBy(() -> facade().getJob(404L))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.NOT_FOUND_JOB));
    }

    @Test
    @DisplayName("대기 중인 작업을 취소하면 바로 CANCELLED 로 끝나고 종료 이벤트 1회 · 감사 로그 SUCCESS 를 남긴다")
    void cancelsQueued() {
        queued(1L, "REPORT");

        JobAdminResponse response = facade().cancel(1L, ADMIN);

        assertThat(response.status().code()).isEqualTo("CANCELLED");
        assertThat(response.finishedAt()).isEqualTo(NOW);
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events).hasSize(1);
        verify(auditPort).jobCancelSucceeded(ADMIN, 1L);
        verifyNoMoreInteractions(auditPort);
    }

    @Test
    @DisplayName("실행 중인 작업을 취소하면 취소 요청만 남는다 — 상태는 RUNNING, 요청한 어드민이 남고, 다시 보내도 같다(멱등)")
    void requestsCancelOnRunning() {
        queued(1L, "REPORT");
        repository.overwrite(repository.get(1L).claim(WORKER, NOW, Duration.ofMinutes(5)));

        JobAdminResponse first = facade().cancel(1L, ADMIN);
        JobAdminResponse again = facade().cancel(1L, ADMIN);

        assertThat(first.status().code()).isEqualTo("RUNNING");
        assertThat(first.cancelRequested()).isTrue();
        assertThat(first.cancelRequestedByMemberId()).isEqualTo("9");
        assertThat(again.cancelRequested()).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("이미 끝난 작업이면 409(JOB_022)이고 감사 로그 FAILURE 를 남긴다")
    void rejectsFinished() {
        queued(1L, "REPORT");
        repository.overwrite(repository.get(1L).requestCancel(ADMIN, NOW));

        assertThatThrownBy(() -> facade().cancel(1L, ADMIN))
            .isInstanceOfSatisfying(JobException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_ALREADY_FINISHED));
        verify(auditPort).jobCancelFailed(ADMIN, 1L);
    }

    @Test
    @DisplayName("읽은 뒤 워커가 가져가 버전에서 지면 다시 읽어 실행 중 작업으로 취소 요청한다")
    void retriesWhenWorkerClaimsInBetween() {
        repository = new InMemoryJobRepositoryPort() {
            private boolean raced;

            @Override
            public synchronized Job update(Job job) {
                if (!raced) {
                    raced = true;
                    overwrite(get(job.id()).claim(WORKER, NOW, Duration.ofMinutes(5)));
                }
                return super.update(job);
            }
        };
        queued(1L, "REPORT");

        JobAdminResponse response = facade().cancel(1L, ADMIN);

        assertThat(response.status().code()).isEqualTo("RUNNING");
        assertThat(response.cancelRequested()).isTrue();
        assertThat(events).isEmpty();
        verify(auditPort).jobCancelSucceeded(ADMIN, 1L);
    }
}
