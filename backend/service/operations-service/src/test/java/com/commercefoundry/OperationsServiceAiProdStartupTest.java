package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.domainlayer.aiusage.adapter.in.metering.AiUsageMeteringInterceptor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 운영(prod) 판정에서 {@code ai.enabled=true} 로 기동한다 (#435 완료 기준). 활성 프로파일에 local · dev · test 가 없으면 ai-core 기동 가드는 prod 로 보고
 * {@code allow-unmetered} · {@code allow-insecure-loopback} 을 막는다 — 둘 다 없이, https {@code base-url} 과 계량 인터셉터 하나로 뜨는지 본다.
 *
 * <p>외부 의존(H2 · Eureka 끔)은 test 프로파일 yml 을 프로파일이 아닌 파일로 가져와 끊는다({@code spring.config.import}). API 는 부르지 않는다 —
 * {@code base-url} 은 해석되지 않는 예약 도메인({@code .invalid})이다. 키는 가짜다.
 */
@SpringBootTest(properties = {
    "spring.config.import=classpath:application-test.yml",
    "spring.datasource.url=jdbc:h2:mem:operations-ai-prod;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false",
    "ai.enabled=true",
    "ai.api-key=test-only-key",
    "ai.base-url=https://ai.invalid"
})
@ActiveProfiles("prod")
class OperationsServiceAiProdStartupTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiClient aiClient;

    @Test
    @DisplayName("prod 프로파일 · AI 켜짐 · allow-unmetered 없이 기동하고, 계량 인터셉터는 원장 어댑터 하나다")
    void startsMeteredInProd() {
        assertThat(Arrays.asList(applicationContext.getEnvironment().getActiveProfiles())).containsExactly("prod");
        assertThat(applicationContext.getEnvironment().getProperty("ai.allow-unmetered")).isNull();
        assertThat(applicationContext.getEnvironment().getProperty("ai.allow-insecure-loopback")).isNull();
        assertThat(applicationContext.getBeansOfType(AiCallInterceptor.class).values())
            .singleElement().isInstanceOf(AiUsageMeteringInterceptor.class);
        assertThat(aiClient.getClass().getSimpleName()).as("꺼진 클라이언트가 아니다").isNotEqualTo("DisabledAiClient");
    }
}
