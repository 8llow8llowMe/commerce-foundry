package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.querydsl.jpa.impl.JPAQueryFactory;
import feign.FeignException;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 컨텍스트 로딩 게이트. test 프로파일(H2, Eureka 꺼짐)에서 빈 정의가 끝까지 조립되는지와 Swagger 노출 범위를 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderServiceApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvcTester mockMvc;

    @Test
    @DisplayName("컨텍스트가 뜨고 global.config 가 import 한 공통 빈이 조립된다")
    void contextLoads() {
        assertThat(applicationContext.getBeanNamesForType(SnowflakeIdGenerator.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(JPAQueryFactory.class)).hasSize(1);
        assertThat(applicationContext.containsBean("jpaAuditingHandler")).isTrue();
        assertThat(applicationContext.getBeansOfType(GroupedOpenApi.class).values())
            .extracting(GroupedOpenApi::getGroup)
            .containsExactlyInAnyOrder("customer", "admin");
    }

    @Test
    @DisplayName("서비스 이름 · 포트 · 스키마가 service-inventory.md 와 같다")
    void serviceIdentity() {
        Environment environment = applicationContext.getEnvironment();

        assertThat(environment.getProperty("spring.application.name")).isEqualTo("order-service");
        assertThat(environment.getProperty("server.port")).isEqualTo("8083");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("orders");
    }

    /** 키에 오타가 있으면 Hibernate 가 조용히 무시한다. yml 값이 아니라 실제로 만들어진 SessionFactory 의 설정을 본다. */
    @Test
    @DisplayName("Hibernate IN 절 파라미터 패딩이 켜져 있다 (coding-conventions.md §9-6)")
    void inClauseParameterPaddingIsEnabled() {
        SessionFactoryImplementor sessionFactory = applicationContext.getBean(EntityManagerFactory.class).unwrap(SessionFactoryImplementor.class);

        assertThat(sessionFactory.getSessionFactoryOptions().inClauseParameterPaddingEnabled()).isTrue();
    }

    /**
     * 서킷은 yml 키 오타가 조용히 무시된다. 바인딩된 결과를 본다 ({@code coding-conventions.md} §10). 레지스트리는 선언되지 않은 이름도 기본 설정으로 만들어 주므로
     * 인스턴스 선언은 프로퍼티로 따로 본다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"product-service", "delivery-service"})
    @DisplayName("서비스 간 서킷 인스턴스가 선언돼 있고 4xx 를 실패로 세지 않는다 — 업무 거절이 몰려도 서킷이 열리지 않는다")
    void internalCircuitBreakers(String instance) {
        assertThat(applicationContext.getEnvironment().getProperty("resilience4j.circuitbreaker.instances." + instance + ".base-config")).isEqualTo("default");

        CircuitBreakerConfig config = applicationContext.getBean(CircuitBreakerRegistry.class).circuitBreaker(instance).getCircuitBreakerConfig();
        Request request = Request.create(Request.HttpMethod.POST, "http://" + instance, Map.of(), null, StandardCharsets.UTF_8, null);
        Response conflict = Response.builder().status(409).reason("x").request(request).headers(Map.of()).build();
        Response unavailable = Response.builder().status(503).reason("x").request(request).headers(Map.of()).build();

        assertThat(config.getIgnoreExceptionPredicate().test(FeignException.errorStatus("m", conflict))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(FeignException.errorStatus("m", unavailable))).isFalse();
    }

    /** 타임아웃은 yml 키 오타가 조용히 무시된다. 바인딩된 결과를 본다 ({@code coding-conventions.md} §10). */
    @Test
    @DisplayName("Feign 기본 타임아웃은 60초 기본값이 아니고, 전역 인터셉터가 없다")
    void feignDefaults() {
        FeignClientProperties.FeignClientConfiguration defaults = applicationContext.getBean(FeignClientProperties.class).getConfig().get("default");
        assertThat(defaults.getConnectTimeout()).isEqualTo(2_000);
        assertThat(defaults.getReadTimeout()).isEqualTo(3_000);
        assertThat(defaults.getRequestInterceptors()).as("전역 인터셉터를 두지 않는다 (security-guide.md §9-5)").isNullOrEmpty();
    }

    @Test
    @DisplayName("헬스 체크는 상태만 낸다 — DB 같은 구성 요소 상세를 싣지 않는다")
    void healthExposesStatusOnly() {
        MvcTestResult result = mockMvc.get().uri("/actuator/health").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).bodyJson().doesNotHavePath("$.components").doesNotHavePath("$.details");
    }

    @Test
    @DisplayName("헬스 구성 요소 경로도 닫혀 있다 — show-components 기본값(never)에서는 루트가 아닌 헬스 경로가 404 다")
    void healthComponentPathIsClosed() {
        // DataSource 가 있으니 db 기여자는 있다. 숨겨져서 404 이고, 구성 요소를 보이게 바꾸면 이 경로가 열린다
        assertThat(mockMvc.get().uri("/actuator/health/db")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/info"})
    @DisplayName("actuator 는 health 만 노출한다 — local 은 .env 값이 Environment 에 그대로 들어 있다")
    void actuatorExposesHealthOnly(String path) {
        assertThat(mockMvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/customer", "/v3/api-docs/admin", "/swagger-ui/index.html", "/webjars/swagger-ui/index.html"})
    @DisplayName("Swagger 는 기본 닫힘이다 — 프로파일이 빠진 배포 환경에서 문서가 열리지 않는다")
    void swaggerIsClosedByDefault(String path) {
        assertThat(mockMvc.get().uri(path)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Nested
    @TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    @DisplayName("Swagger 를 연 프로파일(local · dev)")
    class SwaggerOpened {

        @Autowired
        private MockMvcTester mockMvc;

        @ParameterizedTest
        @ValueSource(strings = {"customer", "admin"})
        @DisplayName("고객 / 어드민 그룹 문서를 내고, 요청 주소는 상대 경로 / 다")
        void servesGroupDocs(String group) {
            assertThat(mockMvc.get().uri("/v3/api-docs/" + group))
                .hasStatusOk()
                .bodyJson().extractingPath("$.servers[0].url").isEqualTo("/");
        }

        @Test
        @DisplayName("그룹 없는 기본 문서는 내지 않는다 — 그룹 경로 필터를 거치지 않아 /internal/** 까지 담기 때문이다")
        void defaultDocsAreClosed() {
            assertThat(mockMvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Swagger UI 가 열린다")
        void servesSwaggerUi() {
            assertThat(mockMvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
        }
    }
}
