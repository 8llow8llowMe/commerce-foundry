package com.commercefoundry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 레지스트리 인증 (#248). Eureka REST 는 Jersey 서블릿이라 MockMvc 가 아니라 실제 포트에 HTTP 로 친다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = DiscoverySecurityTest.PASSWORD_PROPERTY)
class DiscoverySecurityTest {

    /** 형식만 맞춘 더미(hex 64자) — 실값이 아니다. */
    static final String PASSWORD = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String PASSWORD_PROPERTY = "discovery.auth.password=" + PASSWORD;

    private static final String APP = "SMOKE-SERVICE";
    private static final String INSTANCE_JSON = """
        {"instance":{"instanceId":"smoke-1","hostName":"127.0.0.1","app":"SMOKE-SERVICE","ipAddr":"127.0.0.1","status":"UP",
        "port":{"$":18080,"@enabled":"true"},"securePort":{"$":443,"@enabled":"false"},
        "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"}}}""";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private PeerAwareInstanceRegistry registry;

    @AfterEach
    void cleanUp() {
        registry.cancel(APP, "smoke-1", false);
    }

    @Test
    @DisplayName("자격 증명 없이 레지스트리를 조회하면 401 이고 Basic 인증을 요구한다")
    void anonymousFetchIsUnauthorized() throws Exception {
        HttpResponse<String> response = send(get("/eureka/apps"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(value -> assertThat(value).startsWith("Basic"));
    }

    @Test
    @DisplayName("자격 증명이 없거나 틀리면 인스턴스를 등록할 수 없다 — 401 이고 레지스트리에 남지 않는다")
    void anonymousOrWrongRegistrationIsUnauthorized() throws Exception {
        assertThat(send(register()).statusCode()).isEqualTo(401);
        assertThat(send(withBasic(register(), "eureka", "wrong-" + PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(send(withBasic(register(), "admin", PASSWORD)).statusCode()).isEqualTo(401);

        assertThat(registry.getApplication(APP)).isNull();
    }

    @Test
    @DisplayName("올바른 자격 증명이면 등록 · 조회된다")
    void authenticatedRegistrationSucceeds() throws Exception {
        assertThat(send(withBasic(register(), "eureka", PASSWORD)).statusCode()).isEqualTo(204);

        HttpResponse<String> fetched = send(withBasic(get("/eureka/apps/" + APP), "eureka", PASSWORD));
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).contains("smoke-1");
        assertThat(registry.getApplication(APP)).isNotNull();
    }

    @Test
    @DisplayName("/actuator/health 는 익명으로 UP 이고, 다른 actuator 는 익명으로 열리지 않는다")
    void healthIsAnonymous() throws Exception {
        HttpResponse<String> health = send(get("/actuator/health"));
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");

        assertThat(send(get("/actuator/env")).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("대시보드는 익명이면 401, 인증해도 기본 프로파일에서는 꺼져 있다")
    void dashboardIsClosedByDefault() throws Exception {
        assertThat(send(get("/")).statusCode()).isEqualTo(401);
        assertThat(send(withBasic(get("/"), "eureka", PASSWORD)).statusCode()).isEqualTo(404);
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Accept", "application/json").GET();
    }

    private HttpRequest.Builder register() {
        return HttpRequest.newBuilder(uri("/eureka/apps/" + APP))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(INSTANCE_JSON));
    }

    private static HttpRequest.Builder withBasic(HttpRequest.Builder builder, String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return builder.header("Authorization", "Basic " + token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
