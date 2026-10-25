package com.commercefoundry.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

class CorrelationIdLoggingEnvironmentPostProcessorTest {

    private final CorrelationIdLoggingEnvironmentPostProcessor processor = new CorrelationIdLoggingEnvironmentPostProcessor();

    @Test
    @DisplayName("패턴이 없으면 상관 id 조각을 넣고, 이미 있으면 덮어쓰지 않는다")
    void setsThePatternOnlyWhenMissing() {
        StandardEnvironment empty = new StandardEnvironment();
        processor.postProcessEnvironment(empty, new SpringApplication());
        assertThat(empty.getProperty(CorrelationIdLoggingEnvironmentPostProcessor.PROPERTY)).isEqualTo(CorrelationIds.LOG_PATTERN);

        MockEnvironment preset = new MockEnvironment().withProperty(CorrelationIdLoggingEnvironmentPostProcessor.PROPERTY, "kept");
        processor.postProcessEnvironment(preset, new SpringApplication());
        assertThat(preset.getProperty(CorrelationIdLoggingEnvironmentPostProcessor.PROPERTY)).isEqualTo("kept");
    }

    @Test
    @DisplayName("로그 한 줄에 MDC 의 id 가 찍히고, id 가 없으면 그 자리가 비어 있다")
    void patternPrintsTheMdcValue() {
        assertThat(format("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")).contains("[aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee]");
        assertThat(format(null)).doesNotContain("[");
    }

    private static String format(String correlationId) {
        Logger logger = (Logger) LoggerFactory.getLogger("correlation-id-pattern");
        LoggerContext context = logger.getLoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(CorrelationIds.LOG_PATTERN + "%m%n");
        layout.start();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        if (correlationId != null) {
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
        }
        try {
            logger.info("hello");
            return layout.doLayout(appender.list.getFirst());
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
            logger.detachAppender(appender);
        }
    }
}
