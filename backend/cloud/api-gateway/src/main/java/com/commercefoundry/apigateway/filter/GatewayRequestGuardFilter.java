package com.commercefoundry.apigateway.filter;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 정규화되지 않은 경로와 허용하지 않는 메서드를 <b>라우팅 전에</b> 거절한다 ({@code architecture-guide.md} §6).
 *
 * <p>라우트 predicate 는 정규화하지 않은 원문 경로로 매칭하고, 게이트웨이는 그 원문을 그대로 하류에 넘긴다.
 * 그래서 {@code /api/v1/products/../../../internal/v1/orders/1} 이 product-service 라우트에 걸린 뒤
 * 하류 Tomcat 이 {@code /internal/v1/orders/1} 로 정규화한다 — 내부 API 노출, 어드민 경로 판정 우회가 된다.
 * 정규화해서 넘기는 대신 거절한다. 정상 클라이언트는 이런 경로를 만들지 않는다.
 *
 * <p>CORS 필터보다도 먼저 돈다. 거절은 공격 탐지 신호라 로그는 debug 로만, 원문 경로는 길이를 잘라 남긴다(플러딩 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRequestGuardFilter implements WebFilter, Ordered {

    private static final Set<HttpMethod> ALLOWED_METHODS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
        HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)));

    /**
     * 소문자로 바꾼 원문 경로에 있으면 거절하는 문자열.
     * 빈 세그먼트, 경로 파라미터({@code ..;/} 우회), 인코딩된 점·슬래시·역슬래시, 이중 인코딩({@code %25}), 역슬래시.
     */
    private static final List<String> FORBIDDEN_SEQUENCES = List.of("//", ";", "%2e", "%2f", "%5c", "%25", "\\");

    /** 인코딩된 제어문자 ({@code %00}~{@code %1f}, {@code %7f}). */
    private static final Pattern ENCODED_CONTROL_CHAR = Pattern.compile("%(?:[01][0-9a-f]|7f)");

    private static final int LOG_PATH_MAX_LENGTH = 200;

    private final GatewayErrorResponseWriter errorResponseWriter;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        if (!ALLOWED_METHODS.contains(request.getMethod())) {
            log.debug("gateway request rejected. reason=method-not-allowed, method={}", request.getMethod());
            response.getHeaders().setAllow(ALLOWED_METHODS);
            return errorResponseWriter.write(response, HttpStatus.METHOD_NOT_ALLOWED, GatewayErrorCode.BAD_REQUEST);
        }

        String rawPath = request.getURI().getRawPath();
        Optional<String> violation = findViolation(rawPath);
        if (violation.isPresent()) {
            log.debug("gateway request rejected. reason={}, path={}", violation.get(), abbreviate(rawPath));
            return errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, GatewayErrorCode.BAD_REQUEST);
        }
        return chain.filter(exchange);
    }

    /** 쿼리 문자열은 보지 않는다 — 경로만 하류에서 다시 해석된다. */
    private static Optional<String> findViolation(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return Optional.empty();
        }

        String lowerCasePath = rawPath.toLowerCase(Locale.ROOT);
        for (String sequence : FORBIDDEN_SEQUENCES) {
            if (lowerCasePath.contains(sequence)) {
                return Optional.of("forbidden-sequence(" + sequence + ")");
            }
        }
        if (ENCODED_CONTROL_CHAR.matcher(lowerCasePath).find() || rawPath.chars().anyMatch(GatewayRequestGuardFilter::isControlChar)) {
            return Optional.of("control-char");
        }
        // split 의 limit -1 은 끝의 빈 세그먼트를 지우지 않는다. "/a/.." 처럼 끝에 오는 점 세그먼트도 잡는다
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.of("dot-segment");
            }
        }
        return Optional.empty();
    }

    private static boolean isControlChar(int ch) {
        return ch < 0x20 || ch == 0x7f;
    }

    private static String abbreviate(String rawPath) {
        if (rawPath.length() <= LOG_PATH_MAX_LENGTH) {
            return rawPath;
        }
        return rawPath.substring(0, LOG_PATH_MAX_LENGTH) + "...(length=" + rawPath.length() + ")";
    }
}
