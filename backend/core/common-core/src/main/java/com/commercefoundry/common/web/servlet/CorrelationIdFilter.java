package com.commercefoundry.common.web.servlet;

import com.commercefoundry.common.log.CorrelationIds;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서블릿 서비스가 상관 id 를 MDC 에 넣는다 (#284, {@code coding-conventions.md} §8).
 *
 * <p>헤더가 소문자 UUID 하나면 그 값이고, 없거나 둘 이상이거나 형식이 틀리면 새로 만든다. 틀린 원문은 로그에 남기지 않는다.
 * 고른 값은 요청 속성에 둔다. 에러 디스패치({@code /error})는 그 속성을 다시 읽어 500 로그가 같은 id 를 가진다.
 *
 * <p>순서는 {@link Ordered#HIGHEST_PRECEDENCE} + 1 이다. {@link UnhandledFilterExceptionFilter}(가장 바깥) 안쪽이고 보안 필터(-100)보다 앞이다.
 * 컴포넌트 스캔 대상이 아니다. {@link CorrelationIdAutoConfiguration} 이 등록한다.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    /** 500 로그({@link FallbackErrorController})가 같은 id 를 쓰게 에러 디스패치에도 건다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String correlationId = resolve(request);
        request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, correlationId);
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private static String resolve(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            Object stored = request.getAttribute(CorrelationIds.REQUEST_ATTRIBUTE);
            if (stored instanceof String correlationId && CorrelationIds.isAcceptable(correlationId)) {
                return correlationId;
            }
        }
        return CorrelationIds.acceptOrCreate(singleHeader(request));
    }

    /** 값이 정확히 하나일 때만 그 문자열. 없거나 둘 이상이면 {@code null}. */
    private static String singleHeader(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(CorrelationIds.HEADER));
        if (values.size() != 1) {
            return null;
        }
        return values.getFirst();
    }
}
