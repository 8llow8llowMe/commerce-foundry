package com.commercefoundry.domainlayer.job.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 감사 한 줄의 형식 — 로그 수집기가 이 모양으로 파싱한다 ({@code security-guide.md} §11-4). */
class JobAdminAuditProcessorTest {

    @Test
    @DisplayName("작업 취소 성공 · 실패는 공통 admin.audit INFO 네 칸이고 예외 원문 · 추가 필드가 없다")
    void jobCancel() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JobAdminAuditProcessor processor = new JobAdminAuditProcessor();
            processor.cancelSucceeded(9L, 42L);
            processor.cancelFailed(9L, 43L);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=JOB_CANCEL actorMemberId=9 target=job:42 result=SUCCESS",
                "action=JOB_CANCEL actorMemberId=9 target=job:43 result=FAILURE"
            );
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getLoggerName()).isEqualTo("admin.audit");
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getTimeStamp()).isPositive();
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
