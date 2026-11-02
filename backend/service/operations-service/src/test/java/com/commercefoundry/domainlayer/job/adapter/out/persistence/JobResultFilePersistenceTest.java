package com.commercefoundry.domainlayer.job.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.port.out.JobResultFileRepositoryPort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultFile;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Import({QuerydslConfigurer.class, OperationsServiceFeaturesConfig.class, JobResultFileRepositoryAdapter.class})
class JobResultFilePersistenceTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Autowired private JobResultFileRepositoryPort files;
    @Autowired private TestEntityManager entityManager;
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void dueKeysetAndVersionAreExecutedOnPostgres() {
        var first = files.insert(staged(1));
        files.insert(staged(2));
        entityManager.clear();
        var page = files.findDue(NOW, NOW.minusSeconds(1), 0, 1);
        assertThat(page).extracting(JobResultFile::id).containsExactly(1L);
        assertThat(files.findDue(NOW, page.getFirst().nextDeleteAt(), page.getFirst().id(), 100))
            .extracting(JobResultFile::id).containsExactly(2L);
        files.update(first.available("etag", NOW.minusSeconds(1)));
        entityManager.clear();
        assertThatThrownBy(() -> files.update(first.retire(NOW))).isInstanceOf(JobException.class);
    }

    private static JobResultFile staged(long id) {
        return new JobResultFile(id, id, 1, "worker", "job-results/" + id, "report.csv", "text/csv", 3, null, false,
            JobResultFile.State.STAGED, NOW.plusSeconds(3600), NOW, NOW, 0, null, null);
    }
}
