package com.commercefoundry.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.common.log.CorrelationIds;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("예외 필터 안쪽 · 보안 필터보다 앞에서 돈다")
    void runsInsideTheExceptionFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    @DisplayName("맞는 헤더는 요청 처리 중 로그의 MDC 에 있고, 필터가 끝나면 MDC 가 비며, 응답에는 싣지 않는다")
    void keepsAValidHeaderOnTheRequestLog() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        request.addHeader(CorrelationIds.HEADER, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        ListAppender<ILoggingEvent> events = attach();

        try {
            filter.doFilter(request, response, new MockFilterChain() {
                @Override
                public void doFilter(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) {
                    seen.set(MDC.get(CorrelationIds.MDC_KEY));
                    ((Logger) LoggerFactory.getLogger(CorrelationIdFilterTest.class)).info("handled");
                }
            });
        } finally {
            detach(events);
        }

        assertThat(seen).hasValue(ID);
        assertThat(events.list).singleElement().extracting(event -> event.getMDCPropertyMap().get(CorrelationIds.MDC_KEY)).isEqualTo(ID);
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationIds.HEADER)).isNull();
        assertThat(request.getAttribute(CorrelationIds.REQUEST_ATTRIBUTE)).isEqualTo(ID);
    }

    @Test
    @DisplayName("형식이 틀린 헤더와 값이 둘인 헤더는 버리고 새 id 를 만든다 — 틀린 원문은 로그에 없다")
    void dropsAMalformedHeader() throws ServletException, IOException {
        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        forged.addHeader(CorrelationIds.HEADER, ID + "\nINFO forged");
        String created = idDuring(forged);
        assertThat(created).isNotEqualTo(ID).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(created).doesNotContain("forged");

        MockHttpServletRequest two = new MockHttpServletRequest("GET", "/api/v1/orders/1");
        two.addHeader(CorrelationIds.HEADER, ID);
        two.addHeader(CorrelationIds.HEADER, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(idDuring(two)).isNotEqualTo(ID).isNotEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    }

    @Test
    @DisplayName("에러 디스패치는 요청에 남겨 둔 id 를 다시 쓴다")
    void errorDispatchReusesTheStoredId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, ID);

        assertThat(idDuring(request)).isEqualTo(ID);
    }

    private String idDuring(MockHttpServletRequest request) throws ServletException, IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (servletRequest, servletResponse) -> seen.set(MDC.get(CorrelationIds.MDC_KEY)));
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
        return seen.get();
    }

    private static ListAppender<ILoggingEvent> attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilterTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilterTest.class);
        logger.detachAppender(appender);
    }
}
