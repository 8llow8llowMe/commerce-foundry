package com.commercefoundry.domainlayer.job.application.service;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.job.adapter.in.admin.presenter.JobResultDownloadPresenter;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.service.processor.JobHandlerProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobQueryProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultAuditProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultDownloadProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobResultFileProcessor;
import com.commercefoundry.domainlayer.job.domain.model.Job;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.domainlayer.job.support.InMemoryJobRepositoryPort;
import com.commercefoundry.security.principal.AdminPermission;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobResultDownloadFacadeTest {

    @ParameterizedTest
    @CsvSource({"MANAGER,SETTLEMENT", "OWNER,UNKNOWN_HISTORY", "MANAGER,MISSING"})
    void deniesBeforeFileLookupAndSigning(MemberRole role, String type) {
        var repository = new InMemoryJobRepositoryPort();
        if (!type.equals("MISSING")) {
            repository.insert(Job.register(42L, type, "{}", null, 7L, 0, Instant.now(), 3));
        }
        JobHandler settlement = new JobHandler() {
            @Override public String type() { return "SETTLEMENT"; }
            @Override public AdminPermission permission() { return AdminPermission.SETTLEMENT_READ; }
            @Override public JobOutcome execute(JobExecution execution) { return JobOutcome.empty(); }
        };
        var queries = new JobQueryProcessor(repository, new JobHandlerProcessor(handlers(settlement), properties()));
        var files = mock(JobResultFileProcessor.class);
        var downloads = mock(JobResultDownloadProcessor.class);
        var audit = mock(JobResultAuditProcessor.class);
        var presenter = mock(JobResultDownloadPresenter.class);
        var facade = new JobResultDownloadFacade(queries, files, downloads, audit, presenter);

        assertThatThrownBy(() -> facade.download(42L, 7L, AdminPermission.grantedTo(role)))
            .isInstanceOfSatisfying(JobException.class, error -> assertThat(error.getErrorCode()).isEqualTo(JobErrorCode.NOT_FOUND_JOB));
        verifyNoInteractions(files, downloads, presenter);
        verify(audit).downloaded(7L, 42L, false);
    }
}
