package com.commercefoundry.domainlayer.legaldocument;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.LegalDocumentRepositoryAdapter;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentErrorCode;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentException;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=operations",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
@Import({OperationsServiceFeaturesConfig.class, LegalDocumentRepositoryAdapter.class})
class LegalDocumentPostgresTest extends LegalDocumentPersistenceContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");

    @Test
    void concurrentFirstDraftCreatesOneRootAndOneRevision() throws Exception {
        race(() -> draft(KEY, null, "A"), () -> draft(KEY, null, "B"));
        assertThat(documents.count()).isEqualTo(1);
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void concurrentDraftsSerializeRevisionNumbers() throws Exception {
        draft(KEY, null, "Initial");
        race(() -> draft(KEY, 0L, "A"), () -> draft(KEY, 0L, "B"));
        assertThat(revisions.count()).isEqualTo(2);
        assertThat(adapter.detail(KEY).orElseThrow().document().latestRevision()).isEqualTo(2);
    }

    @Test
    void draftAndPublishRaceHaveOneWinnerAndNoPartialPublication() throws Exception {
        draft(KEY, null, "Initial");
        race(() -> draft(KEY, 0L, "Next"), () -> adapter.publish(KEY, 0L, 1, true, 8, NOW));
        var result = adapter.detail(KEY).orElseThrow();
        assertThat(result.document().version()).isEqualTo(1);
        if (result.document().publishedRevision() == null) {
            assertThat(revisions.count()).isEqualTo(2);
            assertThat(adapter.published("ko")).isEmpty();
        } else {
            assertThat(revisions.count()).isEqualTo(1);
            assertThat(adapter.published("ko")).hasSize(1);
        }
    }

    @Test
    void simultaneousPublishConflictsThenRetryIsIdempotent() throws Exception {
        draft(KEY, null, "Initial");
        race(() -> adapter.publish(KEY, 0L, 1, true, 8, NOW), () -> adapter.publish(KEY, 0L, 1, true, 9, NOW.plusSeconds(1)));
        var before = adapter.detail(KEY).orElseThrow();
        var retry = adapter.publish(KEY, 0L, 1, true, 10, NOW.plusSeconds(2));
        assertThat(retry).isEqualTo(before);
    }

    private void race(Callable<?> first, Callable<?> second) throws Exception {
        CyclicBarrier readBarrier = new CyclicBarrier(2);
        Advised repositoryProxy = (Advised) documents;
        MethodInterceptor synchronizeReads = invocation -> {
            Object result = invocation.proceed();
            Object[] arguments = invocation.getArguments();
            if (invocation.getMethod().getName().equals("findByTypeAndLocale") && arguments.length == 2
                && KEY.type().equals(arguments[0]) && KEY.locale().equals(arguments[1])) {
                readBarrier.await(20, TimeUnit.SECONDS);
            }
            return result;
        };
        repositoryProxy.addAdvice(0, synchronizeReads);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> outcome(first));
            var b = executor.submit(() -> outcome(second));
            int winners = (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(winners).isEqualTo(1);
        } finally {
            repositoryProxy.removeAdvice(synchronizeReads);
        }
    }

    private boolean outcome(Callable<?> action) throws Exception {
        try {
            action.call();
            return true;
        } catch (LegalDocumentException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(LegalDocumentErrorCode.CONFLICT);
            return false;
        }
    }
}
