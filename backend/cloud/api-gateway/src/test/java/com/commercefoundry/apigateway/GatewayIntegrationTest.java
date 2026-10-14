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
 * <p>CORS origin 은 여기서 고정한다. 셸의 {@code GATEWAY_CORS_ALLOWED_ORIGINS} · {@code SPRING_PROFILES_ACTIVE} 에 따라 결과가 바뀌지 않게.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false", "gateway.cors.allowed-origins=" + GatewayIntegrationTest.ALLOWED_ORIGIN}
)
@AutoConfigureWebTestClient
public @interface GatewayIntegrationTest {

    /** 통합 테스트에서 CORS 를 허용하는 유일한 origin. */
    String ALLOWED_ORIGIN = "http://localhost:3000";
}
