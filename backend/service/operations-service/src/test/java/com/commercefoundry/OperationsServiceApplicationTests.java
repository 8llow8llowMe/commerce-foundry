package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity.AiUsageMonthEntity;
import com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.repository.AiUsageMonthRepository;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageReserveCommand;
import com.commercefoundry.domainlayer.aiusage.application.command.AiUsageSettleCommand;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageReservation;
import com.commercefoundry.domainlayer.aiusage.application.info.AiUsageSettleResult;
import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMeterUseCase;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiTokenUsage;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.commercefoundry.domainlayer.job.adapter.in.event.JobRunEventListener;
import com.commercefoundry.domainlayer.job.adapter.in.scheduler.JobLeaseExpiryScheduler;
import com.commercefoundry.messaging.outbox.OutboxStore;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
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
    @DisplayName("업무 시각 Clock 은 UTC 하나이고, 리스 만료 판정이 쓸 @Scheduled 처리기가 켜져 있다")
    void clockAndSchedulingAreConfigured() {
        assertThat(applicationContext.getBeanNamesForType(Clock.class)).hasSize(1);
        assertThat(applicationContext.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(applicationContext.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue();
    }

    @Test
    @DisplayName("작업 실행 스레드는 core = max = concurrency(2) · 큐 0 이고, heartbeat 스케줄러는 스레드 하나다")
    void jobWorkerExecutorsAreSized() {
        ThreadPoolTaskExecutor executor = applicationContext.getBean("jobWorkerTaskExecutor", ThreadPoolTaskExecutor.class);
        ThreadPoolTaskScheduler leaseScheduler = applicationContext.getBean("jobLeaseTaskScheduler", ThreadPoolTaskScheduler.class);

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isZero();
        assertThat(executor.getThreadNamePrefix()).isEqualTo("job-worker-");
        assertThat(leaseScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
    }

    /**
     * 작업 워커 전용 실행기 · 스케줄러 빈이 Boot 기본 빈을 밀어내지 않는다 — {@code applicationTaskExecutor} 는 Executor 빈이 하나라도 있으면,
     * {@code taskScheduler} 는 TaskScheduler 빈이 하나라도 있으면 빠지는 조건이다. 밀려나면 {@code @Scheduled} 만료 판정이 heartbeat 스레드를 같이 쓴다.
     */
    @Test
    @DisplayName("Boot 기본 applicationTaskExecutor · taskScheduler 가 그대로 있고, 리스 만료 판정은 그 스케줄러에서 설정 주기(test 200ms)로 돈다")
    void bootDefaultsSurviveAndLeaseSweepIsScheduled() {
        assertThat(applicationContext.containsBean("applicationTaskExecutor")).as("applicationTaskExecutor").isTrue();
        assertThat(applicationContext.containsBean("taskScheduler")).as("taskScheduler").isTrue();
        assertThat(applicationContext.getBean("taskScheduler")).isNotSameAs(applicationContext.getBean("jobLeaseTaskScheduler"));

        List<FixedDelayTask> sweeps = applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(ScheduledTask::getTask)
            .filter(FixedDelayTask.class::isInstance)
            .map(FixedDelayTask.class::cast)
            .filter(task -> task.toString().contains(JobLeaseExpiryScheduler.class.getName()))
            .toList();
        assertThat(sweeps).hasSize(1);
        assertThat(sweeps.getFirst().getIntervalDuration()).isEqualTo(Duration.ofMillis(200));
        assertThat(sweeps.getFirst().getInitialDelayDuration()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("메시징이 꺼져 있어도 outbox 저장소는 조립되고, 실행 신호 리스너는 만들지 않는다 — 컨텍스트를 재시작해도 컨슈머가 뜨지 않는다")
    void outboxStoreIsWiredAndListenerIsAbsent() {
        assertThat(applicationContext.getBeanNamesForType(OutboxStore.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(JobRunEventListener.class)).isEmpty();
        assertThat(applicationContext.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("job-runner")).isNull();
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

    /**
     * AI 원장 설정(test yml)이 바인딩되고, 예약 → 정산의 JPQL 조건부 UPDATE 가 H2 에서도 돈다. 동시성 · 유니크 키 경합은 {@code AiUsageLedgerPostgresTest}(PostgreSQL).
     * 단가 test-small = 입력 1 · 출력 5 USD/MTok — 입력 20자 · 최대 출력 100 의 예상치는 10 + 500 = 510 micro.
     */
    @Test
    @DisplayName("AI 원장 — 예약은 월 행을 만들어 예상치를 쥐고, 정산은 실측으로 옮기며 두 번째 정산은 반영하지 않는다 (H2)")
    void aiUsageReserveAndSettleRoundTrip() {
        AiUsageMeterUseCase meter = applicationContext.getBean(AiUsageMeterUseCase.class);
        AiUsageMonthRepository months = applicationContext.getBean(AiUsageMonthRepository.class);

        AiUsageReservation reservation = meter.reserve(new AiUsageReserveCommand("context-test", null, "job:1", "test-small", 100, 20, 0, false));
        long reservedBefore = months.findByPeriod(reservation.period()).orElseThrow().getReservedMicros();
        AiUsageSettleCommand completed = new AiUsageSettleCommand(
            reservation.callId(), AiUsageStatus.COMPLETED, new AiTokenUsage(10, 10, 0, 0), null, null, null, "msg_context", 5
        );

        AiUsageSettleResult first = meter.settle(completed);
        AiUsageSettleResult second = meter.settle(completed);

        assertThat(reservation.estimateCostMicros()).isEqualTo(510L);
        assertThat(first.applied()).isTrue();
        assertThat(first.costMicros()).isEqualTo(60L);
        assertThat(second.applied()).isFalse();
        AiUsageMonthEntity month = months.findByPeriod(reservation.period()).orElseThrow();
        assertThat(month.getReservedMicros()).isEqualTo(reservedBefore - 510L + 60L);
        assertThat(month.getLimitMicrosSnapshot()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("AI 원장 — 인터럽트 플래그가 켜진 스레드에서도 정산(NOT_SENT)이 실제 커넥션 풀 · 트랜잭션으로 끝나고 플래그는 되살아난다 (H2)")
    void aiUsageSettleSurvivesInterruptFlag() {
        AiUsageMeterUseCase meter = applicationContext.getBean(AiUsageMeterUseCase.class);
        AiUsageReservation reservation = meter.reserve(new AiUsageReserveCommand("context-test", null, null, "test-small", 100, 20, 0, false));

        Thread.currentThread().interrupt();
        AiUsageSettleResult result;
        try {
            result = meter.settle(new AiUsageSettleCommand(reservation.callId(), AiUsageStatus.NOT_SENT, null, null, "BUSY", null, null, 0));
        } finally {
            assertThat(Thread.interrupted()).as("플래그를 되살렸다(여기서 내린다)").isTrue();
        }

        assertThat(result.applied()).isTrue();
        assertThat(result.costMicros()).isZero();
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
