package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
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
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobQueryProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.domain.model.RetryPolicy;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.domainlayer.job.support.RecordingJobDispatchPort;
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

/**
 * 어드민 작업 Facade — 운영자 응답(작업 이름 · 안내 문구 · 취소 가능 여부, #305) · 목록 조건 · 상세 404 · 취소(QUEUED · RUNNING · 끝남 · 워커와 겹침)와
 * 감사 로그를 가짜 저장소로 본다.
 */
class JobAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final long ADMIN = 9L;
    private static final String WORKER = "operations-service:test:1:00000000";
    private static final String REPORT = "REPORT";
    private static final RetryPolicy RETRY = properties().retry().toPolicy();

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
        JobHandlerProcessor handlerProcessor = new JobHandlerProcessor(handlers(new ReportHandler()), properties());
        return new JobAdminFacade(
            new JobQueryProcessor(repository, handlerProcessor),
            new JobRunProcessor(repository, new RecordingJobDispatchPort(), properties(), events::add, clock),
            new JobAdminAuditProcessor(auditPort),
            new JobAdminPresenter()
        );
    }

    private Job queued(long id, String type) {
        return repository.insert(Job.register(id, type, "{\"month\":\"2026-09\"}", null, ADMIN, 0, NOW, 3));
    }

    private Job running(long id, String type) {
        queued(id, type);
        repository.overwrite(repository.get(id).claim(WORKER, NOW, Duration.ofMinutes(5)));
        return repository.get(id);
    }

    private void failFinally(long id, String errorCode) {
        repository.overwrite(running(id, REPORT).fail(errorCode, "내부 진단 원문", false, NOW, RETRY));
    }

    @Test
    @DisplayName("목록은 아이디 내림차순이고 상태 · 종류로 거른다 — 작업 이름은 핸들러 표시명, 모르는 종류는 코드 그대로다")
    void searches() {
        queued(1L, REPORT);
        running(2L, REPORT);
        queued(3L, "SETTLEMENT");

        PageResponse<JobAdminItem> all = facade().getJobs(new JobAdminSearchCommand(null, null, 0, 2));
        PageResponse<JobAdminItem> queuedReports = facade().getJobs(new JobAdminSearchCommand(JobStatusFilter.QUEUED, REPORT, 0, 20));

        assertThat(all.contents()).extracting(JobAdminItem::jobId).containsExactly("3", "2");
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.contents()).extracting(JobAdminItem::typeName).containsExactly("SETTLEMENT", "월 매출 리포트");
        JobAdminItem item = queuedReports.contents().getFirst();
        assertThat(item.jobId()).isEqualTo("1");
        assertThat(item.status().code()).isEqualTo("QUEUED");
        assertThat(item.requestedAt()).isEqualTo(NOW);
        assertThat(item.cancellable()).isTrue();
        assertThat(item.notice()).isNull();
    }

    @Test
    @DisplayName("실패 안내 — 핸들러 고유 코드는 핸들러 문구, 엔진 코드(실행 시간 초과)는 엔진 문구, 모르는 코드는 일반 문구다. 오류 원문은 나가지 않는다")
    void failureNotices() {
        failFinally(1L, "INVALID_PERIOD");
        failFinally(2L, Job.RUN_TIMEOUT);
        failFinally(3L, "UNKNOWN_FAILURE");

        JobAdminResponse handlerCode = facade().getJob(1L);
        assertThat(handlerCode.status().code()).isEqualTo("FAILED");
        assertThat(handlerCode.notice()).isEqualTo("조회 기간을 확인해 주세요.");
        assertThat(handlerCode.cancellable()).isFalse();
        assertThat(facade().getJob(2L).notice()).isEqualTo("작업이 너무 오래 걸려 중단했습니다. 범위를 줄여 다시 요청해 주세요.");
        assertThat(facade().getJob(3L).notice()).startsWith("작업을 끝내지 못했습니다.");
        assertThat(handlerCode.toString()).doesNotContain("내부 진단 원문", "INVALID_PERIOD");
    }

    @Test
    @DisplayName("재시도를 기다리는 대기 작업은 '잠시 뒤 다시 시도' 안내가 붙는다")
    void retryWaitingNotice() {
        repository.overwrite(running(1L, REPORT).fail("UPSTREAM_DOWN", "내부 진단 원문", true, NOW, RETRY));

        JobAdminResponse response = facade().getJob(1L);

        assertThat(response.status().code()).isEqualTo("QUEUED");
        assertThat(response.notice()).isEqualTo("일시적인 오류로 잠시 뒤 다시 시도합니다.");
        assertThat(response.cancellable()).isTrue();
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
        queued(1L, REPORT);

        JobAdminResponse response = facade().cancel(1L, ADMIN);

        assertThat(response.status().code()).isEqualTo("CANCELLED");
        assertThat(response.finishedAt()).isEqualTo(NOW);
        assertThat(response.cancellable()).isFalse();
        assertThat(repository.get(1L).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(events).hasSize(1);
        verify(auditPort).jobCancelSucceeded(ADMIN, 1L);
        verifyNoMoreInteractions(auditPort);
    }

    @Test
    @DisplayName("실행 중인 작업을 취소하면 취소 요청만 남는다 — 상태는 RUNNING, 안내 문구가 붙고 더 취소할 수 없으며, 다시 보내도 같다(멱등)")
    void requestsCancelOnRunning() {
        running(1L, REPORT);

        JobAdminResponse first = facade().cancel(1L, ADMIN);
        JobAdminResponse again = facade().cancel(1L, ADMIN);

        assertThat(first.status().code()).isEqualTo("RUNNING");
        assertThat(first.notice()).isEqualTo("취소를 요청했습니다. 진행 중인 단계가 끝나면 멈춥니다.");
        assertThat(first.cancellable()).isFalse();
        assertThat(again.notice()).isEqualTo(first.notice());
        assertThat(repository.get(1L).cancelRequestedByMemberId()).isEqualTo(ADMIN);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("이미 끝난 작업이면 409(JOB_022)이고 감사 로그 FAILURE 를 남긴다")
    void rejectsFinished() {
        queued(1L, REPORT);
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
        queued(1L, REPORT);

        JobAdminResponse response = facade().cancel(1L, ADMIN);

        assertThat(response.status().code()).isEqualTo("RUNNING");
        assertThat(response.notice()).isEqualTo("취소를 요청했습니다. 진행 중인 단계가 끝나면 멈춥니다.");
        assertThat(events).isEmpty();
        verify(auditPort).jobCancelSucceeded(ADMIN, 1L);
    }

    /** 표시명과 고유 실패 문구를 가진 합성 핸들러. */
    private static final class ReportHandler implements JobHandler {

        @Override
        public String type() {
            return REPORT;
        }

        @Override
        public String displayName() {
            return "월 매출 리포트";
        }

        @Override
        public String failureNotice(String errorCode) {
            return "INVALID_PERIOD".equals(errorCode) ? "조회 기간을 확인해 주세요." : null;
        }

        @Override
        public JobOutcome execute(JobExecution execution) {
            return JobOutcome.empty();
        }
    }
}
