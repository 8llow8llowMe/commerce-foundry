package com.commercefoundry.domainlayer.legaldraftgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in.LegalDraftGenerationUseCase;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftGenerationGate;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftJobProcessor;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "operations.jobs.worker.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegalDraftGenerationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withInitScript("ddl/operations-schema.sql");

    @Autowired LegalDraftGenerationUseCase useCase;
    @Autowired DataSource dataSource;
    @MockitoSpyBean LegalDraftGenerationGate gate;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentRequestsCommitOneReceiptAndOneJob() throws Exception {
        doReturn(true).when(gate).enabled();
        String key = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        Callable<Long> register = () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return useCase.register(42L, key, "TERMS", "ko", 1, Map.of("COMPANY_NAME", "예시 상점"));
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(register);
            var right = executor.submit(register);
            start.countDown();
            assertThat(left.get(15, TimeUnit.SECONDS)).isEqualTo(right.get(15, TimeUnit.SECONDS));
        }
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("select count(*) from operations.legal_draft_generation_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from operations.job where type = ?", Integer.class, LegalDraftJobProcessor.TYPE))
            .isEqualTo(1);
    }
}
