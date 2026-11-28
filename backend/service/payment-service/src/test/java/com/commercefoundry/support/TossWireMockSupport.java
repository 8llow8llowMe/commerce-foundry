package com.commercefoundry.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 가짜 토스 서버(WireMock) 하나와 그 동적 속성 — 결제 승인 어댑터 시험과 정산 어댑터 시험(#219)이 같이 쓴다.
 *
 * <p>Spring 테스트 컨텍스트 캐시 키에는 {@code @DynamicPropertySource} <b>메서드</b>가 들어간다 — 클래스마다 같은 내용을 따로 적으면 컨텍스트가 하나 더 뜬다
 * (CI 테스트 JVM 메모리, order 의 OOM 선례). 그래서 이 상위 클래스 한 곳에 두고, 하위 클래스는 애노테이션을 똑같이 단다
 * ({@code @SpringBootTest} · {@code @ActiveProfiles("test")}, {@code @MockitoBean} 없음).
 *
 * <ul>
 *   <li>서버는 JVM 에서 한 번만 띄우고 클래스가 끝나도 멈추지 않는다 — 캐시된 컨텍스트가 이 주소를 계속 가리키기 때문이다(JVM 이 끝날 때 멈춘다).
 *       JUnit 확장({@code WireMockExtension})은 {@code @Nested} 클래스마다 새 포트로 다시 띄워 쓰지 않는다.</li>
 *   <li>스텁 · 받은 요청은 하위 클래스가 시험마다 {@code TOSS.resetAll()} 로 비운다.</li>
 *   <li>키는 형식만 맞춘 더미다 — 실키가 아니다. OS 환경변수 TOSS_SECRET_KEY 가 있어도 이 값으로 덮는다.</li>
 * </ul>
 */
public abstract class TossWireMockSupport {

    protected static final String SECRET_KEY = "test_sk_dummyWireMockOnly0001";
    protected static final String EXPECTED_AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8));
    /**
     * 승인 클라이언트(toss-payments)의 응답 타임아웃을 줄여 타임아웃을 빨리 재현한다. 너무 짧으면 느린 CI 에서 정상 응답도 타임아웃으로 떨어지므로 2초로 둔다.
     * 지연 스텁은 이 두 배({@link #TIMEOUT_DELAY_MILLIS})다. 정산 클라이언트(toss-settlements)는 기동 가드가 60초 미만을 막아 yml 값 그대로다.
     */
    protected static final int READ_TIMEOUT_MILLIS = 2_000;
    protected static final int TIMEOUT_DELAY_MILLIS = READ_TIMEOUT_MILLIS * 2;

    protected static final WireMockServer TOSS = startedServer();

    @DynamicPropertySource
    protected static void tossProperties(DynamicPropertyRegistry registry) {
        registry.add("toss.base-url", TOSS::baseUrl);
        registry.add("toss.secret-key", () -> SECRET_KEY);
        registry.add("spring.cloud.openfeign.client.config.toss-payments.read-timeout", () -> READ_TIMEOUT_MILLIS);
    }

    private static WireMockServer startedServer() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "toss-wiremock-stop"));
        return server;
    }
}
