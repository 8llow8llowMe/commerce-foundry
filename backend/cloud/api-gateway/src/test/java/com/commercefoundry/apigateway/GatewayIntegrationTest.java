package com.commercefoundry.apigateway;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

/**
 * 게이트웨이 통합 테스트 공통 설정. Eureka 없이 실제 서버를 무작위 포트로 띄운다.
 *
 * <p>설정이 한 글자라도 다르면 테스트 컨텍스트를 따로 띄우므로, 통합 테스트는 이 애노테이션만 붙여 컨텍스트 하나를 공유한다.
 * Eureka 를 끄면 등록된 인스턴스가 없어 모든 {@code lb://} 라우트가 "인스턴스 없음" 으로 끝난다.
 *
 * <p>CORS origin 과 dev 라우트 · Swagger 스위치(꺼짐)는 여기서 고정한다. 셸의 {@code GATEWAY_CORS_ALLOWED_ORIGINS} ·
 * {@code SPRING_PROFILES_ACTIVE}(local 이면 dev 라우트가 켜진다)에 따라 결과가 바뀌지 않게.
 * 켠 상태는 {@code GatewayDevRouteTest} 가 {@code @TestPropertySource} 로 덮어 따로 본다.
 *
 * <p>비로그인 IP 계수({@code gateway.client-ip.*})는 운영 기본값(끔 · HMAC 키 없음)을 그대로 쓴다 — 이 설정으로 컨텍스트가 뜨는 것이
 * "끈 기본값에서는 키 없이 뜬다" 의 증거다 ({@code security-guide.md} §7-3). 계수를 보는 테스트만 {@link #CLIENT_IP_ENABLED} ·
 * {@link #CLIENT_IP_HASH_KEY} 를 {@code @TestPropertySource} 로 켠다(그 테스트들은 원래 컨텍스트를 따로 띄운다).
 *
 * <p>응답 대기는 30초다. 기본 5초는 CI 러너가 모듈 테스트를 병렬로 돌릴 때 404 하나도 넘긴 적이 있다(#49).
 * 정상 응답은 수십 ms 라 테스트 시간은 늘지 않고, 실제로 멈춘 요청은 여전히 실패한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "eureka.client.enabled=false",
        "gateway.cors.allowed-origins=" + GatewayIntegrationTest.ALLOWED_ORIGIN,
        "gateway.dev-routes.enabled=false",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        // 기동 가드(RedisStartupGuard)를 통과할 접속 값. 접속은 첫 명령 때 하므로 Redis 가 없어도 뜬다.
        // 차단 목록을 실제로 읽는 테스트(GatewaySecurityTest)는 조회기를 스텁으로 바꾼다
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6379",
        "spring.data.redis.password=gateway-test-only"
    }
)
@AutoConfigureWebTestClient(timeout = "30s")
public @interface GatewayIntegrationTest {

    /** 통합 테스트에서 CORS 를 허용하는 유일한 origin. */
    String ALLOWED_ORIGIN = "http://localhost:3000";

    /** 비로그인 요청의 IP 를 키로 바꾸는 테스트 전용 HMAC 키 (base64 "gateway-test-only-client-ip-hash-key", 36바이트). 운영 값은 Vault 에만 있다. */
    String TEST_CLIENT_IP_HASH_KEY = "Z2F0ZXdheS10ZXN0LW9ubHktY2xpZW50LWlwLWhhc2gta2V5";

    /** 비로그인 IP 계수를 켠다 (기본 끔). {@link #CLIENT_IP_HASH_KEY} 와 함께 넣는다. */
    String CLIENT_IP_ENABLED = "gateway.client-ip.enabled=true";

    /** 켤 때만 필요한 HMAC 키. */
    String CLIENT_IP_HASH_KEY = "gateway.client-ip.hash-key=" + TEST_CLIENT_IP_HASH_KEY;
}
