package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 어드민 카테고리 쓰기의 잠금 대기 상한 — 실제 Facade 트랜잭션(프록시 · 시간 상한 · 행 잠금)으로 PostgreSQL 에서 본다. 잠금 대기 취소는 엔진에 기대므로 H2 로는 볼 수 없다.
 * 테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본) — 잠금을 쥐는 쪽은 별도 스레드의 {@link TransactionTemplate} 트랜잭션이다.
 * Docker 없는 로컬은 건너뛰고 CI 에서는 Docker 가 없으면 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class CategoryAdminConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired
    private CategoryAdminUseCase categoryAdminUseCase;

    @Autowired
    private CategoryProcessor categoryProcessor;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("다른 트랜잭션이 카테고리 잠금을 쥐고 있으면 시간 상한(5초) 안에 409 · PRODUCT_045 로 끊긴다 — 무한정 기다리지 않고 아무것도 쓰지 않는다")
    void lockWaitIsBounded() throws Exception {
        // 잠글 행이 하나는 있어야 한다 — 빈 표는 잠글 것이 없다
        categoryAdminUseCase.createCategory(new CategoryUpsertCommand(null, "상의", 0, true));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                categoryProcessor.getTreeForUpdate();
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            Throwable failure = catchThrowable(() -> categoryAdminUseCase.createCategory(new CategoryUpsertCommand(null, "하의", 1, true)));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(ProductException.class)
                .extracting(exception -> ((ProductException) exception).getErrorCode())
                .isEqualTo(ProductErrorCode.CATEGORY_BUSY);
            assertThat(failure.getCause()).as("PostgreSQL 이 잠금 대기 문장을 취소한다 — 드라이버 57014 → Spring QueryTimeoutException").isInstanceOf(QueryTimeoutException.class);
            assertThat(waitedMillis).isBetween(4_000L, 10_000L);
            assertThat(jdbcTemplate.queryForObject("select count(*) from product.category", Long.class)).isOne();
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    @Test
    @DisplayName("잠금 · 쓰기 Processor 메서드를 트랜잭션 밖에서 부르면 바로 실패한다 (MANDATORY)")
    void writeOutsideTransactionFails() {
        assertThat(catchThrowable(() -> categoryProcessor.getTreeForUpdate())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> categoryProcessor.create(null, "상의", 0, true))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(catchThrowable(() -> categoryProcessor.delete(1L))).isInstanceOf(IllegalTransactionStateException.class);
    }

    private void cleanUp() {
        jdbcTemplate.update("delete from product.category");
    }
}
