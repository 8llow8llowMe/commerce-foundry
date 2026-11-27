package com.commercefoundry.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 고객 상담 경로({@code /api/v1/support/**})의 응답은 캐시하지 않는다 — 대화 본문과 비회원 자격 증명(발급 응답 본문)이 실린다.
 * 보안 필터보다 먼저 헤더를 걸어 인증 실패(401 · 403) 응답도 no-store 다. 대화 · 비회원 자격 두 컨텍스트에 걸쳐 global 에 둔다.
 * 경로는 MVC 매칭과 같은 {@link PathPattern} 으로 본다 — 퍼센트 인코딩 · 매트릭스 변수로 접두 비교를 비켜 가지 못한다. 게이트웨이도 같은 범위다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SupportCacheControlFilter extends OncePerRequestFilter {
    private static final PathPattern SUPPORT = PathPatternParser.defaultInstance.parse("/api/v1/support/**");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (SUPPORT.matches(RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication())) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        chain.doFilter(request, response);
    }
}
