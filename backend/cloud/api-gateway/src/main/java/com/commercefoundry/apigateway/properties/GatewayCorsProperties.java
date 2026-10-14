package com.commercefoundry.apigateway.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins CORS 를 허용할 프론트 origin 목록 (스토어프론트 · 어드민). 비어 있으면 어떤 origin 도 허용하지 않는다
 */
@ConfigurationProperties(prefix = "gateway.cors")
public record GatewayCorsProperties(
    List<String> allowedOrigins
) {

    private static final String WILDCARD = "*";

    public GatewayCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
            .map(String::strip)
            .filter(origin -> !origin.isEmpty())
            .toList();

        // 허용 origin 은 하나씩 적는다. 와일드카드는 모든 사이트에 API 를 여는 것이고, 나중에 자격 증명을 켜면(#4)
        // Spring 이 요청마다 500 으로 거부한다 — 그 전에 기동에서 막는다
        if (allowedOrigins.contains(WILDCARD)) {
            throw new IllegalArgumentException("gateway.cors.allowed-origins 에 '*' 를 쓸 수 없습니다. origin 을 하나씩 적으세요.");
        }
    }
}
