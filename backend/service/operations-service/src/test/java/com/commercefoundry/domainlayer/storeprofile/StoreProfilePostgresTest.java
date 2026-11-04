package com.commercefoundry.domainlayer.storeprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.StoreProfileRepositoryAdapter;
import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository.StoreProfileRepository;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileErrorCode;
import com.commercefoundry.domainlayer.storeprofile.application.exception.StoreProfileException;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
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
@Import({OperationsServiceFeaturesConfig.class, StoreProfileRepositoryAdapter.class})
class StoreProfilePostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/operations-schema.sql");
    @Autowired
    StoreProfileRepositoryAdapter adapter;
    @Autowired
    StoreProfileRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    void firstInsertRaceKeepsOneRowAndReturnsConflicts() throws Exception {
        race(null);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void versionRaceKeepsOneWinner() throws Exception {
        var initial = adapter.replace(null, StoreProfilePersistenceTest.profile("Initial"));
        race(initial.version());
        assertThat(adapter.find().orElseThrow().version()).isEqualTo(1);
    }

    private void race(Long expected) throws Exception {
        int editors = 6;
        CyclicBarrier readBarrier = new CyclicBarrier(editors);
        Advised repositoryProxy = (Advised) repository;
        MethodInterceptor synchronizeReads = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findById") && invocation.getArguments().length == 1
                && Objects.equals(invocation.getArguments()[0], 1L)) {
                readBarrier.await(20, TimeUnit.SECONDS);
            }
            return result;
        };
        // 실제 Spring Data 조회를 실행한 뒤 모든 편집자가 같은 초기 상태를 읽을 때까지 기다린다.
        repositoryProxy.addAdvice(0, synchronizeReads);
        CountDownLatch ready = new CountDownLatch(editors);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(editors)) {
            List<Callable<Boolean>> actions = new ArrayList<>();
            for (int i = 0; i < editors; i++) {
                String name = "Store " + i;
                actions.add(() -> {
                    ready.countDown();
                    assertThat(start.await(20, TimeUnit.SECONDS)).isTrue();
                    try {
                        adapter.replace(expected, StoreProfilePersistenceTest.profile(name));
                        return true;
                    } catch (StoreProfileException exception) {
                        assertThat(exception.getErrorCode()).isEqualTo(StoreProfileErrorCode.VERSION_CONFLICT);
                        return false;
                    }
                });
            }
            var futures = actions.stream().map(executor::submit).toList();
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winners = 0;
            for (var future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            repositoryProxy.removeAdvice(synchronizeReads);
        }
    }
}
