package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceMigrationReadAdapter;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException.Reason;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;

@Testcontainers
@EnabledWithDockerOrOnCi
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class PriceMigrationReadPostgresIntegrationTest extends PriceMigrationReadIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    @Autowired JPAQueryFactory query;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;

    @Test
    void actualPostgresTransactionsAreReadOnlyRepeatableReadAndSuspendDirtyCaller() {
        insert(Lane.PRODUCT, 1);
        List<List<String>> observations = new ArrayList<>();
        PlatformTransactionManager observer = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                TransactionStatus status = manager.getTransaction(definition);
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try (var statement = connection.createStatement()) {
                    List<String> actual = new ArrayList<>();
                    for (String setting : List.of("transaction_isolation", "transaction_read_only")) {
                        try (var result = statement.executeQuery("show " + setting)) {
                            result.next();
                            actual.add(result.getString(1));
                        }
                    }
                    observations.add(actual);
                } catch (java.sql.SQLException failure) {
                    throw new AssertionError("실제 PostgreSQL 트랜잭션 설정 확인 실패", failure);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
                return status;
            }
            @Override public void commit(TransactionStatus status) { manager.commit(status); }
            @Override public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var observed = new PriceMigrationReadAdapter(query, observer);
        var before = jdbc.queryForMap("select * from product.product where id=1");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            ProductEntity dirty = em.find(ProductEntity.class, 1L);
            ReflectionTestUtils.setField(dirty, "name", "dirty-caller");
            measure();
            observed.captureBounds();
            observed.readPage(new Criteria(Lane.PRODUCT, 1L, null, 1));
            assertCounts(5);
            assertThat(em.contains(dirty)).isTrue();
            assertThat(dirty.getName()).isEqualTo("dirty-caller");
            assertThat(jdbc.queryForMap("select * from product.product where id=1")).isEqualTo(before);
            status.setRollbackOnly();
        });
        assertThat(observations).containsExactly(List.of("repeatable read", "on"), List.of("repeatable read", "on"));
        assertThat(jdbc.queryForMap("select * from product.product where id=1")).isEqualTo(before);
        // 쓰기 가능한 부모 트랜잭션에서 호출해도 원천 읽기가 flush·commit하지 않았다.
        // 다음 페이지는 별도 snapshot이므로 상한 이내 삽입·삭제가 보인다.
        insert(Lane.PRODUCT, 3);
        Long upper = observed.captureBounds().product();
        assertThat(observed.readPage(new Criteria(Lane.PRODUCT, upper, null, 1))).extracting(row -> row.id()).containsExactly(1L);
        insert(Lane.PRODUCT, 2);
        jdbc.update("delete from product.product where id=3");
        assertThat(observed.readPage(new Criteria(Lane.PRODUCT, upper, 1L, 3))).extracting(row -> row.id()).containsExactly(2L);
    }

    @Test
    void actualPostgresTableLockTriggersQueryTimeoutAndFixedAdapterFailure() throws Exception {
        insert(Lane.PRODUCT, 1);
        String sentinel = "RAW_SOURCE_SENTINEL_907314";
        jdbc.update("update product.product set name=? where id=1", sentinel);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (Connection blocker = dataSource.getConnection()) {
            logger.warn(sentinel);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(sentinel));
            appender.list.clear();
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement()) {
                    statement.execute("lock table product.product in access exclusive mode");
                }
                long start = System.nanoTime();
                Throwable failure = catchThrowable(() -> port.readPage(new Criteria(Lane.PRODUCT, 1L, null, 1)));
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                PriceMigrationReadAdapterTest.assertSanitized(failure, Reason.READ_FAILED);
                // 5초 트랜잭션 fallback과 구분한다. 실제 서버 query cancel(57014)이 2초 hint에 의해 발생한다.
                assertThat(elapsedMillis).isBetween(1000L, 4500L);
                assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("57014"));
                assertThat(appender.list).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(sentinel);
                    if (event.getThrowableProxy() != null) {
                        assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(sentinel);
                    }
                });
                // 여기서 관측한 하부 logger에 fixture 값이 없음을 확인할 뿐 모든 JDBC/서버 로그의 정제를 보장하지 않는다.
            } finally {
                blocker.rollback();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(port.readPage(new Criteria(Lane.PRODUCT, 1L, null, 1))).hasSize(1);
    }
}
