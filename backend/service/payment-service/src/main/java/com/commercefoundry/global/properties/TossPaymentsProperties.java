package com.commercefoundry.global.properties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스페이먼츠 연동 설정 ({@code payment-guide.md} §2). 값의 형식 · 프로파일 조건은 빈이 만들어지기 전에 {@code TossPaymentsStartupGuard} 가 본다.
 * 타임아웃은 여기 두지 않는다 — Feign 이 실제로 쓰는 {@code spring.cloud.openfeign.client.config.toss-payments} 한 곳에 두고 가드가 그 값을 본다.
 *
 * @param secretKey 시크릿 키 — Vault {@code TOSS_SECRET_KEY}. 로그 · {@link #toString()} 에 남기지 않는다
 * @param baseUrl API 주소 (기본 {@code https://api.tosspayments.com}). 테스트만 가짜 서버로 덮는다
 */
@ConfigurationProperties(prefix = "toss")
public record TossPaymentsProperties(String secretKey, String baseUrl) {

    /**
     * HTTP Basic 인증 헤더 값 — 사용자 ID 가 시크릿 키, 비밀번호는 없다({@code base64("{secretKey}:")}). 부를 때마다 만든다 — 인코딩한 값도 시크릿이라 필드로 두지 않는다.
     */
    public String basicAuthorization() {
        return "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** 레코드 기본 {@code toString} 은 시크릿 키를 그대로 찍는다 — 가린다. */
    @Override
    public String toString() {
        return "TossPaymentsProperties[secretKey=***, baseUrl=" + baseUrl + "]";
    }
}
