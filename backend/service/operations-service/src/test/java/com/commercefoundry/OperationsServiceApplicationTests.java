package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.ZoneOffset;
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
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
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
class OperationsServiceApplicationTests {

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
    @DisplayName("업무 시각 Clock 은 UTC 하나이고, 작업 워커 폴러가 쓸 @Scheduled 처리기가 켜져 있다")
    void clockAndSchedulingAreConfigured() {
        assertThat(applicationContext.getBeanNamesForType(Clock.class)).hasSize(1);
        assertThat(applicationContext.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(applicationContext.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
    }

    @Test
    @DisplayName("서비스 이름 · 포트 · 스키마가 service-inventory.md 와 같다")
    void serviceIdentity() {
        Environment environment = applicationContext.getEnvironment();

        assertThat(environment.getProperty("spring.application.name")).isEqualTo("operations-service");
        assertThat(environment.getProperty("server.port")).isEqualTo("8086");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("operations");
    }

    /** 키에 오타가 있으면 Hibernate 가 조용히 무시한다. yml 값이 아니라 실제로 만들어진 SessionFactory 의 설정을 본다. */
    @Test
    @DisplayName("Hibernate IN 절 파라미터 패딩이 켜져 있다 (coding-conventions.md §9-6)")
    void inClauseParameterPaddingIsEnabled() {
        SessionFactoryImplementor sessionFactory = applicationContext.getBean(EntityManagerFactory.class).unwrap(SessionFactoryImplementor.class);

        assertThat(sessionFactory.getSessionFactoryOptions().inClauseParameterPaddingEnabled()).isTrue();
    }

    /** 키 이름 · 들여쓰기가 틀려도 오류 없이 드라이버 기본값(true)으로 돈다. yml 값이 아니라 실제로 만들어진 커넥션 풀이 드라이버에 넘기는 속성을 본다. */
    @Test
    @DisplayName("커넥션 풀이 드라이버에 logServerErrorDetail=false 를 넘긴다 — 제약 위반 로그에 행 값(DETAIL)이 실리지 않는다 (coding-conventions.md §8)")
    void driverOmitsServerErrorDetail() {
        HikariDataSource dataSource = applicationContext.getBean(HikariDataSource.class);

        assertThat(dataSource.getDataSourceProperties()).containsEntry("logServerErrorDetail", "false");
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
