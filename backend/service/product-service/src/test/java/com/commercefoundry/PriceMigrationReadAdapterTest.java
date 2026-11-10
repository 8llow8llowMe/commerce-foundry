package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.PriceMigrationReadAdapter;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException.Reason;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.querydsl.core.types.Expression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PriceMigrationReadAdapterTest {
    private static final String SENTINEL = "RAW_SOURCE_SENTINEL_907314";

    @Test
    void beginQueryAndCommitFailuresAreFixedWithoutCauseSuppressedStackOrAdapterLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.warn(SENTINEL);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(SENTINEL));
            appender.list.clear();
            for (String phase : new String[] {"begin", "query", "commit"}) {
                for (boolean page : new boolean[] {false, true}) {
                    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
                    JPAQueryFactory query = mock(JPAQueryFactory.class);
                    JPAQuery<?> selection = mock(JPAQuery.class, org.mockito.Answers.RETURNS_SELF);
                    doReturn(selection).when(query).select(org.mockito.ArgumentMatchers.<Expression<Object>>any());
                    doReturn(java.util.List.of()).when(selection).fetch();
                    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
                    RuntimeException original = new IllegalStateException(SENTINEL);
                    original.addSuppressed(new IllegalStateException(SENTINEL));
                    if (phase.equals("begin")) when(manager.getTransaction(any())).thenThrow(original);
                    if (phase.equals("query")) doThrow(original).when(query).select(org.mockito.ArgumentMatchers.<Expression<Object>>any());
                    if (phase.equals("commit")) doThrow(original).when(manager).commit(any());
                    PriceMigrationReadAdapter adapter = new PriceMigrationReadAdapter(query, manager);
                    Throwable failure = catchThrowable(() -> {
                        if (page) adapter.readPage(new Criteria(Lane.PRODUCT, 42L, null, 3));
                        else adapter.captureBounds();
                    });
                    assertSanitized(failure, Reason.READ_FAILED);
                    if (phase.equals("commit")) verify(manager).commit(any());
                }
            }
            assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(SENTINEL));
            assertThat(appender.list).allSatisfy(event -> {
                if (event.getThrowableProxy() != null) {
                    assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(SENTINEL);
                }
            });
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void transactionHasIndependentReadOnlyRepeatableReadAndFiveSecondTimeout() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JPAQueryFactory query = mock(JPAQueryFactory.class);
        JPAQuery<?> selection = mock(JPAQuery.class, org.mockito.Answers.RETURNS_SELF);
        doReturn(selection).when(query).select(org.mockito.ArgumentMatchers.<Expression<Object>>any());
        new PriceMigrationReadAdapter(query, manager).captureBounds();
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
    }

    @Test
    void invalidCriteriaNeverIncludesCallerValues() {
        assertSanitized(catchThrowable(() -> new Criteria(null, 907314L, -907314L, 1)), Reason.INVALID_CRITERIA);
        assertSanitized(catchThrowable(() -> new Criteria(Lane.SKU, 907314L, -907314L, 0)), Reason.INVALID_CRITERIA);
        assertSanitized(catchThrowable(() -> new Criteria(Lane.SKU, 907314L, -907314L, 501)), Reason.INVALID_CRITERIA);
        PriceMigrationReadAdapter adapter = new PriceMigrationReadAdapter(mock(JPAQueryFactory.class), mock(PlatformTransactionManager.class));
        assertSanitized(catchThrowable(() -> adapter.readPage(null)), Reason.INVALID_CRITERIA);
    }

    static void assertSanitized(Throwable failure, Reason reason) {
        assertThat(failure).isInstanceOf(PriceMigrationReadException.class).hasMessage(reason.name()).hasNoCause();
        assertThat(((PriceMigrationReadException) failure).reason()).isEqualTo(reason);
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getStackTrace()).isEmpty();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertThat(failure.toString() + trace).doesNotContain(SENTINEL, "907314", "select", "fixture");
    }
}
