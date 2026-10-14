package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * dev 라우트 플래그와 Swagger 두 스위치가 프로파일 yml 마다 같은 값인지 본다.
 *
 * <p>셋을 한 플레이스홀더로 묶지 않고 리터럴로 둔다 ({@code application.yml} 주석). 대신 어긋나면 여기서 잡는다 —
 * 라우트는 닫혔는데 Swagger UI 만 열리거나, 그 반대가 되지 않게.
 */
class GatewayDevRouteProfileTest {

    private static final List<String> SWITCHES = List.of("gateway.dev-routes.enabled", "springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled");

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "application.yml,       false",
        "application-local.yml, true",
        "application-dev.yml,   true"
    })
    @DisplayName("프로파일 yml 마다 dev 라우트 · Swagger 스위치가 모두 같은 값이다 — 공통은 닫힘, local · dev 는 열림")
    void devSwitchesAgree(String file, String expected) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        assertThat(sources).hasSize(1);

        for (String key : SWITCHES) {
            assertThat(String.valueOf(sources.getFirst().getProperty(key))).as("%s 의 %s", file, key).isEqualTo(expected);
        }
    }
}
