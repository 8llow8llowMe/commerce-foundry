package com.commercefoundry.domainlayer.storebrand;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.BrandRevisionRepositoryAdapter;
import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.entity.BrandRevisionEntity;
import com.commercefoundry.domainlayer.storebrand.adapter.out.persistence.repository.BrandRevisionRepository;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@EntityScan(basePackageClasses = BrandRevisionEntity.class)
@EnableJpaRepositories(basePackageClasses = BrandRevisionRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.sql.init.mode=never", "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false",
    "spring.datasource.hikari.connection-init-sql=SET statement_timeout = '10s'"
})
@Import({OperationsServiceFeaturesConfig.class, BrandRevisionRepositoryAdapter.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrandRevisionPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Autowired BrandRevisionRepositoryAdapter revisions;

    @Test
    void assignsOneWinnerWhenSaveAndPublishCompeteForTheSameVersion() throws Exception {
        Instant time = Instant.parse("2026-11-27T00:00:00Z");
        revisions.append(new BrandRevision(1, 1, null, null, 10, time, BrandAssets.EMPTY));
        var save = new BrandRevision(2, 2, null, null, 11, time, BrandAssets.EMPTY);
        var publish = new BrandRevision(2, 1, 2L, null, 12, time, BrandAssets.EMPTY);

        var workers = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> writes = List.of(() -> append(save), () -> append(publish));
            var results = workers.invokeAll(writes, 25, TimeUnit.SECONDS);
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder("success", "conflict");
        } finally {
            workers.shutdownNow();
        }

        var winner = revisions.latest().orElseThrow();
        assertThat(winner).isIn(save, publish);
        assertThat(revisions.publications(0, 10).contents()).isEqualTo(winner.equals(publish) ? List.of(publish) : List.of());
    }

    private String append(BrandRevision candidate) {
        try {
            revisions.append(candidate);
            return "success";
        } catch (BrandAssetException exception) {
            assertThat(exception.getCode()).isEqualTo(Code.CONFLICT);
            return "conflict";
        }
    }
}
