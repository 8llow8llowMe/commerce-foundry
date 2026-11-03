package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.OrderAdminPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderAdminSearchCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.model.OrderStatusFilter;
import com.commercefoundry.domainlayer.order.application.service.OrderAdminFacade;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;

/** 주문 감사의 공통 로그 형식과 원문 미노출 — 수집기 계약은 기존 네 칸 그대로다. */
class OrderAdminAuditProcessorTest {

    private final OrderAdminAuditProcessor auditProcessor = new OrderAdminAuditProcessor();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private final Logger facadeLogger = (Logger) LoggerFactory.getLogger(OrderAdminFacade.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        auditLogger.addAppender(appender);
        facadeLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        auditLogger.detachAppender(appender);
        facadeLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("목록 조건 · 상세 · 상태 변경 성공/실패는 admin.audit INFO 네 칸이며 정상 Instant 조건은 바뀌지 않는다")
    void auditFormatAndSearchFilters() {
        auditProcessor.searched(7L, new OrderAdminSearchCommand(null, null, null, 0, 20));
        auditProcessor.searched(7L, new OrderAdminSearchCommand(OrderStatusFilter.PAID,
            Instant.parse("2026-10-01T00:00:00.123456789Z"), Instant.parse("2026-11-01T00:00:00Z"), 1, 50));
        auditProcessor.viewed(7L, 42L);
        auditProcessor.statusChanged(7L, 42L, true);
        auditProcessor.statusChanged(7L, 43L, false);

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
            "action=ORDER_SEARCH actorMemberId=7 target=orders?status=ALL&from=-&to=-&page=0&size=20 result=SUCCESS",
            "action=ORDER_SEARCH actorMemberId=7 target=orders?status=PAID&from=2026-10-01T00:00:00.123456789Z&to=2026-11-01T00:00:00Z&page=1&size=50 result=SUCCESS",
            "action=ORDER_VIEW actorMemberId=7 target=order:42 result=SUCCESS",
            "action=ORDER_STATUS_CHANGE actorMemberId=7 target=order:42 result=SUCCESS",
            "action=ORDER_STATUS_CHANGE actorMemberId=7 target=order:43 result=FAILURE"
        );
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLoggerName()).isEqualTo("admin.audit");
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("실제 일괄 실패 경로도 감사 한 줄이며 사유 · 예외 메시지 · cause 원문을 로그에 싣지 않는다")
    void failedChangeDoesNotLeakReasonOrException() {
        OrderAdminProcessor processor = mock(OrderAdminProcessor.class);
        OrderAdminFacade facade = new OrderAdminFacade(processor, auditProcessor, new OrderAdminPresenter());
        OrderStatusChangeCommand command = new OrderStatusChangeCommand(OrderStatus.PREPARING,
            "SECRET_REASON recipient=가짜이름 address=가짜주소 token=fake-token");
        when(processor.changeStatus(42L, command, 7L))
            .thenThrow(new CannotAcquireLockException("SECRET_EXCEPTION", new IllegalStateException("SECRET_CAUSE")));

        var response = facade.changeStatuses(List.of(42L), command, 7L);

        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.results().getFirst().errorCode()).isEqualTo("ORDER_026");
        assertThat(appender.list).filteredOn(event -> event.getLoggerName().equals(AdminAuditLog.LOGGER_NAME))
            .singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("action=ORDER_STATUS_CHANGE actorMemberId=7 target=order:42 result=FAILURE");
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
            });
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "가짜", "fake-token");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
