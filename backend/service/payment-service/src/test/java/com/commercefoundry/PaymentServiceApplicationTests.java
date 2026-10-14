package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class PaymentServiceApplicationTests {

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

        assertThat(environment.getProperty("spring.application.name")).isEqualTo("payment-service");
        assertThat(environment.getProperty("server.port")).isEqualTo("8084");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("payment");
    }

    @Test
    @DisplayName("헬스 체크는 상태만 낸다 — DB 같은 구성 요소 상세를 싣지 않는다")
    void healthExposesStatusOnly() {
        MvcTestResult result = mockMvc.get().uri("/actuator/health").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).bodyJson().doesNotHavePath("$.components").doesNotHavePath("$.details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/info", "/actuator/health/db"})
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
