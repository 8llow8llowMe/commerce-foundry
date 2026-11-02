package com.commercefoundry.domainlayer.job.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.adapter.out.persistence.repository.JobResultFileRepository;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultFileRepositoryPort;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultFileProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultUploadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.job.support.FakeJobResultStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 H2 리포지토리·Spring 트랜잭션과 fake 오브젝트 저장소로 재생한다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:result-file-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false",
    "operations.jobs.result-file.cleanup-initial-delay=1d",
    "operations.jobs.result-file.cleanup-delay=1d"
})
@ActiveProfiles("test")
class JobResultFileFlowTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final AtomicLong IDS = new AtomicLong(300_000);
    private static final String WORKER = "result-test-worker";
    private static final JobResultContent CONTENT = new JobResultContent("report.csv", "text/csv", new byte[] {1, 2, 3}, false);

    @Autowired private JobRepositoryPort jobs;
    @Autowired private JobResultFileRepositoryPort files;
    @Autowired private JobResultFileRepository rows;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private JobResultFileProcessor processor;
    @Autowired private JobResultUploadProcessor upload;
    @Autowired private JobRunProcessor run;
    @Autowired private JobResultCleanupFacade cleanup;
    @Autowired private JobResultDownloadFacade download;
    @Autowired private FakeJobResultStorage storage;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        rows.deleteAllInBatch();
        storage.reset();
        clock.now = NOW;
    }

    @Test
    void deleteSuccessWithDatabaseFailureIsRetriedFromLedger() {
        var file = available();
        clock.now = file.expiresAt();
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable")).doCallRealMethod()
            .when(processor).recordDeletion(file.id(), true);
        cleanup.cleanup();
        assertThat(storage.objects).isEmpty();
        assertThat(files.findById(file.id()).orElseThrow().lastDeletedAt()).isNull();
        cleanup.cleanup();
        assertThat(storage.deletes).isEqualTo(2);
        assertThat(files.findById(file.id()).orElseThrow().lastDeletedAt()).isEqualTo(clock.now);
    }

    @Test
    void uploadThenSuccessCommitsAttachmentWithJob() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        assertThat(staged.state()).isEqualTo(JobResultFile.State.STAGED);
        var prepared = upload.upload(staged, CONTENT);
        assertThat(run.succeed(job.id(), WORKER, 1, "{\"rows\":1}", prepared)).isTrue();
        assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(processor.downloadable(job.id()).state()).isEqualTo(JobResultFile.State.AVAILABLE);
        var response = download.download(job.id(), 7L);
        assertThat(response.fileName()).isEqualTo("report.csv");
        assertThat(storage.ttl).isEqualTo(Duration.ofSeconds(60));
        assertThat(response.toString()).doesNotContain("signature", "SECRET");
    }

    @Test
    void failedConfirmationRollsBackJobSuccess() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        var prepared = upload.upload(staged, CONTENT);
        tx().executeWithoutResult(status -> files.update(stagedWithVersion(staged).retire(NOW.plus(Duration.ofMinutes(11)))));
        assertThatThrownBy(() -> run.succeed(job.id(), WORKER, 1, "{}", prepared)).isInstanceOf(JobException.class);
        assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(files.findById(staged.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.RETIRED);
    }

    @Test
    void lostLeaseOrAttemptCannotAttach() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        var prepared = upload.upload(staged, CONTENT);
        assertThat(run.succeed(job.id(), WORKER, 2, "{}", prepared)).isFalse();
        clock.now = NOW.plus(Duration.ofMinutes(6));
        assertThat(run.succeed(job.id(), WORKER, 1, "{}", prepared)).isFalse();
        assertThat(files.findById(staged.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.STAGED);
        assertThatThrownBy(() -> processor.reserve(job.id(), WORKER, 1, CONTENT)).isInstanceOf(JobException.class);
    }

    @Test
    void oversizedJsonFailsJobWithoutPublishingAttachment() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        var prepared = upload.upload(staged, CONTENT);
        assertThat(run.succeed(job.id(), WORKER, 1, "x".repeat(65_537), prepared)).isTrue();
        assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
        assertThat(files.findById(staged.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.STAGED);
    }

    @Test
    void putAndHeadFailureRemainRecoverableAndLatePutIsDeletedAgain() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        storage.failPut = true;
        assertThatThrownBy(() -> upload.upload(staged, CONTENT)).isInstanceOf(RuntimeException.class);
        storage.failPut = false;
        storage.failHead = true;
        assertThatThrownBy(() -> upload.upload(staged, CONTENT)).isInstanceOf(RuntimeException.class);
        storage.failHead = false;
        clock.now = NOW.plus(Duration.ofMinutes(11));
        cleanup.cleanup();
        assertThat(storage.objects).isEmpty();
        assertThat(files.findById(staged.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.RETIRED);
        storage.put(staged.objectKey(), CONTENT);
        clock.now = clock.now.plus(Duration.ofHours(1));
        cleanup.cleanup();
        assertThat(storage.objects).isEmpty();
        assertThat(storage.deletes).isEqualTo(2);
        assertThat(files.findById(staged.id())).isPresent();
    }

    @Test
    void availableExpiresAndDeleteFailureRetries() {
        var file = available();
        clock.now = file.expiresAt();
        assertThatThrownBy(() -> download.download(file.jobId(), 7L)).isInstanceOfSatisfying(JobException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(JobErrorCode.RESULT_FILE_EXPIRED));
        storage.failDelete = true;
        cleanup.cleanup();
        assertThat(storage.objects).isNotEmpty();
        assertThat(files.findById(file.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.RETIRED);
        storage.failDelete = false;
        clock.now = clock.now.plusSeconds(30);
        cleanup.cleanup();
        cleanup.cleanup();
        assertThat(storage.objects).isEmpty();
        assertThat(storage.deletes).isEqualTo(2);
        assertThatThrownBy(() -> download.download(file.jobId(), 7L)).isInstanceOfSatisfying(JobException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(JobErrorCode.RESULT_FILE_EXPIRED));
    }

    @Test
    void cleanupSkipsDisabledStorage() {
        var file = available();
        storage.enabled = false;
        clock.now = file.expiresAt();
        cleanup.cleanup();
        assertThat(storage.deletes).isZero();
        assertThat(files.findById(file.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.AVAILABLE);
    }

    @Test
    void headIdentityAndTtlBoundariesAreValidated() {
        var file = available();
        storage.objects.put(file.objectKey(), new JobResultStoragePort.Metadata(file.sizeBytes(), file.contentType(), "changed"));
        assertThatThrownBy(() -> download.download(file.jobId(), 7L)).isInstanceOf(JobException.class);
        storage.objects.put(file.objectKey(), new JobResultStoragePort.Metadata(file.sizeBytes(), file.contentType(), file.eTag()));
        clock.now = file.expiresAt().minusMillis(1_900);
        assertThat(download.download(file.jobId(), 7L).expiresAt()).isEqualTo(clock.now.plusSeconds(1));
        assertThat(storage.ttl).isEqualTo(Duration.ofSeconds(1));
        clock.now = file.expiresAt().minusMillis(999);
        assertThatThrownBy(() -> download.download(file.jobId(), 7L)).isInstanceOf(JobException.class);
        clock.now = file.expiresAt().minusSeconds(60);
        storage.extraExpiry = Duration.ofSeconds(1);
        assertThatThrownBy(() -> download.download(file.jobId(), 7L)).isInstanceOf(JobException.class);
    }

    @Test
    void dueQueryUsesTimeAndIdKeysetAndVersionsRejectStaleWrites() {
        var first = processor.reserve(running().id(), WORKER, 1, CONTENT);
        var second = processor.reserve(running().id(), WORKER, 1, CONTENT);
        clock.now = NOW.plus(Duration.ofMinutes(10));
        var batch = processor.findDue(clock.now, Instant.EPOCH, 0, 1);
        assertThat(batch).hasSize(1);
        assertThat(processor.findDue(clock.now, batch.getFirst().nextDeleteAt(), batch.getFirst().id(), 1)).hasSize(1);
        var old = files.findById(first.id()).orElseThrow();
        processor.retire(first.id());
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> files.update(old.available("old", NOW))))
            .isInstanceOf(JobException.class);
        assertThat(processor.findDue(clock.now, Instant.EPOCH, 0, 100)).extracting(JobResultFile::id).contains(first.id(), second.id());
    }

    @Test
    void releasedAttemptCanReserveNewGenerationAndOldWorkerCannotFinish() {
        Job job = tx().execute(status -> {
            var registered = jobs.insert(Job.register(IDS.incrementAndGet(), "RESULT_TEST", "{}", null, null, 0, clock.instant(), 1));
            return jobs.update(registered.claim(WORKER, clock.instant(), Duration.ofMinutes(5)));
        });
        var old = processor.reserve(job.id(), WORKER, 1, CONTENT);
        var oldPrepared = upload.upload(old, CONTENT);
        run.release(job.id(), WORKER, 1);
        String nextWorker = "replacement-worker";
        tx().executeWithoutResult(status -> jobs.update(jobs.findById(job.id()).orElseThrow().claim(nextWorker, clock.instant(), Duration.ofMinutes(5))));
        var replacement = processor.reserve(job.id(), nextWorker, 1, CONTENT);
        var prepared = upload.upload(replacement, CONTENT);
        assertThat(replacement.objectKey()).isNotEqualTo(old.objectKey());
        assertThat(run.succeed(job.id(), WORKER, 1, "{}", oldPrepared)).isFalse();
        assertThat(run.succeed(job.id(), nextWorker, 1, "{}", prepared)).isTrue();
        assertThat(processor.downloadable(job.id()).id()).isEqualTo(replacement.id());
        clock.now = NOW.plus(Duration.ofMinutes(11));
        cleanup.cleanup();
        assertThat(files.findById(old.id()).orElseThrow().state()).isEqualTo(JobResultFile.State.RETIRED);
        assertThat(storage.objects).containsOnlyKeys(replacement.objectKey());
    }

    private JobResultFile stagedWithVersion(JobResultFile file) { return files.findById(file.id()).orElseThrow(); }

    private JobResultFile available() {
        Job job = running();
        var staged = processor.reserve(job.id(), WORKER, 1, CONTENT);
        run.succeed(job.id(), WORKER, 1, "{}", upload.upload(staged, CONTENT));
        return processor.downloadable(job.id());
    }

    private Job running() {
        return tx().execute(status -> {
            var registered = jobs.insert(Job.register(IDS.incrementAndGet(), "RESULT_TEST", "{}", null, null, 0, clock.instant(), 3));
            return jobs.update(registered.claim(WORKER, clock.instant(), Duration.ofMinutes(5)));
        });
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    static class MutableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary MutableClock resultClock() { return new MutableClock(); }
        @Bean @Primary FakeJobResultStorage resultStorage(MutableClock clock) { return new FakeJobResultStorage(clock); }
    }
}
