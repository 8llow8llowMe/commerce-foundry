package com.commercefoundry.domainlayer.job.adapter.out.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JobResultAuditLogAdapterTest {
    @Test
    void auditContainsOnlyActionActorTargetAndResult() {
        Logger logger = (Logger) LoggerFactory.getLogger("admin.audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var audit = new JobResultAuditLogAdapter();
            audit.downloaded(7L, 42L, true);
            audit.downloaded(7L, 42L, false);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=JOB_RESULT_DOWNLOAD actorMemberId=7 target=job:42 result=SUCCESS",
                "action=JOB_RESULT_DOWNLOAD actorMemberId=7 target=job:42 result=FAILURE");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
