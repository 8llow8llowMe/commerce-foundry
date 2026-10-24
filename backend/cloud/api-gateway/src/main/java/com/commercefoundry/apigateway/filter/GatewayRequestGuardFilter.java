package com.commercefoundry.apigateway.filter;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.log.GatewayLogRedaction;
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
import org.springframework.http.HttpHeaders;
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
 * <p>정규화를 통과한 뒤 두 가지를 더 끊는다 ({@code security-guide.md} §5-1 · §7).
 * <ul>
 *   <li><b>{@code /internal/**}</b> — 404 {@code GATEWAY_001}. 어느 라우트에도 없어 원래도 404 지만, 라우트 부재에만 기대지 않는다 — catch-all 라우트가
 *       실수로 생겨도 닫혀 있고, 토큰 검증 · 차단 목록 조회 전에 끝난다. 매핑 없는 경로와 같은 응답이라 내부 API 의 존재를 드러내지 않는다.
 *       대소문자 · 비예약 문자 인코딩({@code /%69nternal})도 같은 경로로 본다.</li>
 *   <li><b>{@code Authorization} 이 둘 이상</b> — 400 {@code GATEWAY_004}. 게이트웨이와 서비스가 모두 첫 값을 읽어 지금은 판정이 같지만,
 *       하류 구성이 바뀌면 게이트웨이가 검증한 토큰과 서비스가 쓰는 토큰이 갈라질 수 있다. 보안 체인보다 앞이라 어느 토큰도 검증하지 않는다.</li>
 * </ul>
 *
 * <p>CORS 필터보다도 먼저 돈다. 거절은 공격 탐지 신호라 로그는 debug 로만, 원문 경로는 길이를 잘라 남긴다(플러딩 방지). 헤더 값(토큰)은 남기지 않는다.
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

    /** 서비스 간 API 경로. 서비스 체인의 {@code InternalResourceServerConfigurer.INTERNAL_PATH_PATTERN}({@code /internal/**})과 같은 접두어다. */
    private static final String INTERNAL_PATH = "/internal";

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
            // 경로 규칙 위반 · internal 경로 거절은 원문을 남긴다(DEBUG) — 공격 문자열 자체가 판단 근거다. 정상 경로의 거절은 가린다
            log.debug("gateway request rejected. reason={}, path={}", violation.get(), abbreviate(rawPath));
            return errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, GatewayErrorCode.BAD_REQUEST);
        }

        // 위에서 %2f · %25 를 끊었으므로 디코딩한 경로는 원문과 세그먼트 구조가 같다 — 이중 디코딩이 없다
        if (isInternalPath(request.getURI().getPath())) {
            log.debug("gateway request rejected. reason=internal-path, path={}", abbreviate(rawPath));
            return errorResponseWriter.write(response, HttpStatus.NOT_FOUND, GatewayErrorCode.ROUTE_NOT_FOUND);
        }

        List<String> authorizations = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorizations != null && authorizations.size() > 1) {
            log.debug("gateway request rejected. reason=multiple-authorization-headers, count={}, path={}", authorizations.size(), abbreviate(GatewayLogRedaction.maskPath(rawPath)));
            return errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, GatewayErrorCode.BAD_REQUEST);
        }
        return chain.filter(exchange);
    }

    /** {@code /internal} 자체와 그 아래. 대소문자를 가리지 않는다 — 하류가 구분하더라도 게이트웨이는 닫힌 쪽으로 본다. */
    private static boolean isInternalPath(String decodedPath) {
        if (decodedPath == null) {
            return false;
        }
        String lowerCasePath = decodedPath.toLowerCase(Locale.ROOT);
        return lowerCasePath.equals(INTERNAL_PATH) || lowerCasePath.startsWith(INTERNAL_PATH + "/");
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
