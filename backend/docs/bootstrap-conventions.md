# Bootstrap Conventions

## Application

```java
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.commercefoundry.domainlayer",
    "com.commercefoundry.global"
})
public class ProductServiceApplication {
}
```

- Application 클래스는 루트 패키지 `com.commercefoundry` 에 둔다. JPA 엔티티·리포지토리의 기본 스캔 패키지가
  Application 의 패키지라서, 여기 있어야 `domainlayer/**` 의 엔티티·리포지토리가 따로 지정하지 않아도 잡힌다.
  같은 이유로 core jar(`com.commercefoundry.persistence` 등)도 엔티티 스캔 범위에 들어가므로 core 에는 `@Entity` 를 두지 않는다
  (`architecture-guide.md` §1-1).
- Feign 이 필요한 서비스만 `@EnableFeignClients(basePackages = "com.commercefoundry.domainlayer")` 를 더한다 — Application 이 아니라 `global.config.*FeignConfig` 에 둔다.
  슬라이스 테스트(`@WebMvcTest` · `@DataJpaTest`)는 Application 클래스를 설정으로 쓰고 리포지토리 테스트는 `*FeaturesConfig` 를 import 하는데,
  거기 있으면 Feign 자동설정이 없는 슬라이스가 클라이언트를 만들려다 실패한다. 스캔으로 잡히는 `*FeignConfig` 는 슬라이스에서 빠진다 (처음 둔 곳: order-service, #12).
  `defaultConfiguration` 은 두지 않는다 (`security-guide.md` §9-5).
- 스케줄러가 있는 서비스만 `@EnableScheduling` 을 더한다 (`global.config.*FeaturesConfig` 에 두는 것을 우선).

## Global Config

서비스 전역 설정은 `global.config` 아래에서 역할별로 나눈다.

| 파일 | 담는 것 |
|------|---------|
| `*BeansConfig` | 공통 Configurer `@Import` (Snowflake, Querydsl, SwaggerSecurity, `ResourceServerSecurityConfigurer` · `InternalResourceServerConfigurer` — 전 서비스, `JwtIssuerConfigurer` — auth 만, `InternalTokenIssuerConfigurer` — internal Feign 클라이언트가 있는 서비스만, `RedisConfigurer` — Redis 를 쓰는 서비스만) |
| `*PropertiesConfig` | 공통 프로퍼티 `@Import` (`JwtVerificationPropertiesConfig` · `InternalTokenPropertiesConfig` — 전 서비스, `JwtIssuerPropertiesConfig` — auth 만) + 서비스 도메인 프로퍼티 `@EnableConfigurationProperties` |
| `*FeaturesConfig` | JPA Auditing(`BaseEntity` 시각), Scheduling, Async 같은 기능 스위치 |
| `*FeignConfig` | `@EnableFeignClients` — Feign 클라이언트가 있는 서비스만 (위 Application 절) |
| `*SwaggerConfig` | 서비스 Swagger 그룹 (고객 / 어드민 그룹 분리) |

- `@Import` 목록은 **단순 클래스명 알파벳순, 한 줄에 하나**다. 이슈마다 한 줄을 끼워 넣어도 다른 줄이 움직이지 않아 PR 끼리 충돌이 적다.
- **예외: 폴백 오류 봉투(`FallbackErrorAutoConfiguration`, common-core)는 `@Import` 하지 않는다 — 자동 구성이다.** 빠뜨려도 기동은 되고
  Boot 기본 오류 JSON 만 조용히 나가므로, 서블릿 서비스면 고르지 않고 언제나 걸리게 했다. 리액티브 게이트웨이는 조건에서 빠진다.
  걸렸는지는 서비스의 `*FallbackErrorSmokeTest` · `*ErrorPageEnvelopeSmokeTest` 가 본다 (아래 보안 절). `/error` 컨트롤러(#81)도 같은 자동 구성이 등록한다.

```java
@Configuration
@Import({
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    SwaggerPropertiesConfig.class
})
@EnableConfigurationProperties({StoreProperties.class, TossPaymentsProperties.class})
public class PaymentServicePropertiesConfig {
}
```

### Swagger 그룹

| 그룹 | 경로 | 비고 |
|------|------|------|
| `customer` | `/api/v1/**` 에서 `/api/v1/admin/**` 를 뺀 것 | |
| `admin` | `/api/v1/admin/**` | |

- `/internal/**` 은 어느 그룹에도 넣지 않는다. 그룹 없는 기본 문서(`/v3/api-docs`)는 그룹의 경로 필터를 거치지 않아
  모든 경로를 담으므로 `springdoc.enable-default-api-docs: false` 로 끈다. 컨트롤러에는 `@Hidden` 도 붙인다 (`coding-conventions.md` §6).
- OpenAPI 서버 주소는 `swagger.server-url: /` (상대 경로)다. 문서를 불러온 origin 으로 "Try it out" 요청이 가서
  게이트웨이로 열든 서비스 포트로 열든 같은 origin 으로 호출한다.

## 설정 파일

| 파일 | 담는 것 |
|------|---------|
| `application.yml` | 포트, 서비스 이름, DB 플레이스홀더, 스키마, Eureka 주소, actuator, Snowflake 플레이스홀더, `security.jwt.*` 플레이스홀더(`${JWT_PUBLIC_KEY}` · `${JWT_PUBLIC_KEY_PREVIOUS:}`), `security.internal.*`(`${spring.application.name}` · `${INTERNAL_JWT_SECRET}` · `${INTERNAL_JWT_SECRET_PREVIOUS:}`), Swagger 기본값 |
| `application-local.yml` | `backend/.env` import, `ddl-auto: update`, Snowflake 고정값, Swagger 열기 |
| `application-dev.yml` | `ddl-auto: update`, Swagger 열기. 값은 Vault 에서 환경변수로 받는다 (`secret-management.md`) |
| `src/test/resources/application-test.yml` | 컨텍스트 로딩 테스트용 H2 · Eureka 끄기 (아래 "컨텍스트 로딩 테스트") |

**기본값은 닫힌 쪽이다.** 프로파일이 빠진 채 배포돼도 열리거나 조용히 틀린 값으로 뜨지 않게, 여는 설정은 프로파일 yml 에만 둔다.

- `ddl-auto` 는 기본 `none`, local · dev 만 `update`. 운영은 애플리케이션이 스키마를 건드리지 않고 사람이 DDL 을 적용한다.
  마이그레이션 도구는 쓰지 않는다 (`coding-conventions.md` §9-8).
- 스키마는 `spring.jpa.properties.hibernate.default_schema` 로 서비스마다 지정한다. 다른 서비스 스키마를 직접 조회하지 않는다.
- actuator 는 `health` 만 노출한다.
- Snowflake 는 base 에 `${SNOWFLAKE_DATACENTER_ID}` · `${SNOWFLAKE_WORKER_ID}` 를 **기본값 없이** 둔다. 값이 빠지면 0/0 으로
  뜨지 않고 기동에 실패한다.
- Swagger 는 `springdoc.api-docs.enabled` · `springdoc.swagger-ui.enabled` 를 `false` 로 두고 local · dev 에서만 켠다.
- `spring.web.resources.add-mappings: false` — 정적 리소스를 내지 않는다. 켜 두면 Swagger 를 닫아도 `/webjars/swagger-ui/**` 가 열린다.
- CORS 는 게이트웨이에서만 처리한다. 서비스에 두면 응답 헤더가 중복된다.

### local Snowflake 배정

local 은 서비스마다 `(datacenter-id, worker-id)` 를 고정한다. 새 서비스는 다음 번호를 쓰고 이 표에 더한다.

| 서비스 | datacenter-id | worker-id |
|--------|---------------|-----------|
| auth-service | 1 | 1 |
| product-service | 1 | 2 |
| order-service | 1 | 3 |
| payment-service | 1 | 4 |
| delivery-service | 1 | 5 |

## 컨텍스트 로딩 테스트

서비스마다 `*ApplicationTests` 하나를 `@SpringBootTest` + `@ActiveProfiles("test")` 로 둔다. 목적은
"빈 정의가 끝까지 조립되는가" 와 "노출 범위가 설정대로인가" 다.

- DB 는 **H2 PostgreSQL 모드**다. Docker 없이 로컬과 CI 가 같은 결과를 낸다.

  ```text
  jdbc:h2:mem:<서비스>-context;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS <스키마>
  ```

- `ddl-auto: create-drop` 으로 엔티티 매핑 오류가 컨텍스트 로딩에서 드러나게 한다. Eureka 는 끈다.
- 락 · `timestamptz` · 인덱스처럼 **엔진에 기대는 동작은 여기서 검증되지 않는다.** 리포지토리 테스트는 Testcontainers(PostgreSQL)로
  따로 두고 CI 에서 돌린다 (아래 "리포지토리 테스트").
- 단언: 공통 빈(Snowflake · `JPAQueryFactory` · JPA Auditing · Swagger 그룹), 서비스 이름 · 포트 · 스키마,
  `/actuator/health` 는 상태만(`components` · `details` 없음) · 다른 actuator 경로 404,
  기본 프로파일에서 Swagger 경로 404, Swagger 를 연 설정에서 그룹 문서 200 · 기본 문서 404.
- 새 서비스를 더하면 게이트웨이의 dev 라우트(`GatewayDevRouteConfig` 는 `ServiceRoute` 를 따른다)와 Swagger UI 목록
  (`api-gateway` 의 `application.yml` `springdoc.swagger-ui.urls`)도 같이 늘린다. `GatewayDevRouteTest` 가 어긋나면 잡는다.
- 새 모듈은 CI 에서 자동으로 `rest` 그룹 job 에 들어간다(`.github/scripts/backend-ci-matrix.py`). 테스트가 길어져 `rest` job 이
  가장 늦게 끝나면 그 스크립트의 `GROUPS` 에 그룹을 더한다. 모듈 이름을 바꾸면 `GROUPS` 도 같이 고친다 — 어긋나면 plan job 이 실패한다.

## 리포지토리 테스트 (Testcontainers, 엔티티를 가진 서비스)

QueryDSL · JPQL · 파생 쿼리는 컴파일로 검증되지 않는다 (`coding-conventions.md` §9-5). `@DataJpaTest` 로 실제 PostgreSQL 에 질의한다.
서비스 `build.gradle` 에 아래 4개를 더한다. 버전은 Boot BOM 이 정한다.

```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:testcontainers-junit-jupiter'
testImplementation 'org.testcontainers:testcontainers-postgresql'
```

```java
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, ProductServiceFeaturesConfig.class, ProductRepositoryAdapter.class, ProductMapperImpl.class})
class ProductPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");
}
```

- **스키마는 DDL 스냅숏(`src/test/resources/ddl/<서비스>-schema.sql`)을 init script 로 적용하고 `ddl-auto=validate` 로 대조한다.**
  컨텍스트가 뜨면 커밋된 스냅숏 — 운영 DDL 의 원본 — 이 실제 PostgreSQL 에서 엔티티 매핑과 맞는다는 뜻이다.
  `create-drop` 으로 바꾸면 스냅숏이 검증되지 않는다. 스냅숏 생성은 `coding-conventions.md` §9-8.
- **`@Testcontainers` 는 기본값으로 두고 `@EnabledWithDockerOrOnCi` 를 붙인다. `disabledWithoutDocker = true` 는 쓰지 않는다.**
  `disabledWithoutDocker` 는 CI 러너에서도 Docker 를 못 찾으면 조용히 skip 하고 초록불을 낸다 — 그러면 스냅숏 DDL 을
  PostgreSQL 에 적용하는 검증과 QueryDSL · JPQL 실제 질의가 소리 없이 사라진다.
  `@EnabledWithDockerOrOnCi`(`com.commercefoundry.support`, 판정은 `DockerOrCiCondition`)는 이렇게 정한다.

  | Docker | 환경변수 `CI` | 결과 |
  |--------|---------------|------|
  | 있음 | 무관 | 돈다 |
  | 없음 | `true` (GitHub Actions 가 넣는다) | 돈다 — 컨테이너 기동 실패로 **시끄럽게 실패**한다 |
  | 없음 | 없음 · 그 밖 | 건너뛴다 — 사유 "Docker 없음 — CI 에서 실행된다" |

  지원 클래스는 서비스 test 소스(`src/test/java/com/commercefoundry/support/`)에 둔다. 처음 만든 것은 product-service 이고,
  `DockerOrCiConditionTest` 가 판정표와 "모든 `@Testcontainers` 테스트가 이 애노테이션을 쓴다" 를 검사한다.
  로컬에서 건너뛴 건은 CI 실행 건수에 세지 않는다(`backend-test-count.py`). 로컬에서 쿼리를 확인하려면 Docker 를 켜고 돌린다.
- 슬라이스는 `@Component` 를 스캔하지 않는다. 테스트할 어댑터와 MapStruct 구현(`*MapperImpl`), `QuerydslConfigurer`(커스텀 리포지토리가
  `JPAQueryFactory` 를 받는다), `*FeaturesConfig`(JPA Auditing — 빠지면 `created_at` NOT NULL 위반)를 `@Import` 한다.
- 컨테이너는 테스트 클래스마다 자기 `static` 필드로 둔다. 상위 클래스의 컨테이너를 여러 클래스가 상속해 쓰면 확장이 클래스마다
  컨테이너를 내렸다 다시 띄우는데, 캐시된 스프링 컨텍스트는 처음 포트를 들고 있어 연결이 끊길 수 있다.
- 데이터는 엔티티 빌더 + 고정 아이디로 `TestEntityManager.persist` 하고 `flush` · `clear` 한 뒤 질의한다 — 1차 캐시가 아니라 DB 를 읽게.
- **제약 위반은 테스트 하나에 하나만 낸다.** `@DataJpaTest` 는 테스트마다 한 트랜잭션이고, PostgreSQL 은 첫 오류 뒤 그 트랜잭션을 중단(aborted)한다.
  같은 테스트에서 두 번째 위반을 기대하면 그 문장은 `current transaction is aborted` 로 실패하고, pgjdbc 가 첫 오류를 원인으로 이어 붙여
  `rootCause()` 는 첫 위반을 가리킨다 — `clear()` 로는 풀리지 않는다. 기대하는 제약 이름은 `@DisplayName` 에 적는다.
- Boot 4 · Testcontainers 2 패키지: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`,
  `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`, `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`,
  `org.springframework.boot.testcontainers.service.connection.ServiceConnection`, `org.testcontainers.postgresql.PostgreSQLContainer`
  (`org.testcontainers.containers.PostgreSQLContainer` 는 deprecated).

## 보안 스모크 테스트

서비스마다 인가 스모크 하나를 한 줄로 둔다. 케이스와 이유는 `security-guide.md` §5-3 "구성 실패 모드" 다.

```java
class ProductServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {
}
```

- `ResourceServerSmokeTestSupport`(security-core 테스트 픽스처)의 애노테이션(`@SpringBootTest` · `@ActiveProfiles("test")` · `@AutoConfigureMockMvc`)은
  `*ApplicationTests` 와 **정확히 같다.** 그래서 컨텍스트 캐시를 같이 써 서비스 컨텍스트를 한 번만 띄운다. 한쪽 애노테이션을 바꾸면 다른 쪽도 바꾼다.
- JWT 키 · 서비스 토큰 비밀은 픽스처의 초기화기가 실행 중 만든 값으로 넣는다. `application-test.yml` 에 적지 않는다 (`security-guide.md` §5-3 "테스트 키").
- 서비스 간 인증(`/internal/**`) 케이스도 같은 스모크에 있다 — 테스트 전용 internal 핸들러는 픽스처의 `ContextCustomizerFactory` 가 모든 서블릿 테스트 컨텍스트에
  등록하므로 `*ApplicationTests` 와의 컨텍스트 공유가 유지된다 (`security-guide.md` §9-10).
- 노출 확대는 스모크의 "actuator 웹 노출은 정확히 `health` 하나" 단언이 잡는다(바인딩된 최종 값이라 `heapdump` · `loggers` 를 목록에 넣어도 깨진다).
  `*ApplicationTests` 의 "다른 actuator 경로 404" 단언은 그대로 둔다 — 목록에 적은 경로만 보지만, 노출하지 않은 엔드포인트가 보안 규칙에
  걸리지 않고 404 라는 동작을 고정한다. 노출 설정이 1차, URL 규칙(노출된 health 외 `denyAll`)이 2차 방어다.
- 발급기를 붙이는 서비스(auth, #4)는 `issuerExpected()` 를 `true` 로 override 한다. 나머지는 상속 한 줄 그대로다.
- **인가 선언 누락 가드**도 같은 스모크에 있다 — 서비스의 모든 MVC 핸들러를 돌며 `/internal/` 핸들러는 허용 호출자(`hasAuthority('SERVICE_…')`),
  `/api/` 핸들러는 `@PreAuthorize` 가 있는지 본다 (`security-guide.md` §9-4 · §9-10). 패키지 기준 정적 규칙(인가 선언 · 어드민 ADMIN · 전역 Feign 인터셉터 · `RequestContextHolder`)은
  서비스의 `<Svc>ArchitectureTest`(`ControllerAuthorizationArchTestSupport` 상속 + `HexagonalArchitectureRules` implements 한 파일)가 본다 — 계층 의존 규칙(H1~H12)도 같은 파일이다.
  새 서비스도 이 파일을 더하고 `applicationClass()` 를 public 으로 구현한다 (`security-guide.md` §9-11, `architecture-guide.md` §3).
- `@PreAuthorize("isAuthenticated()")` 핸들러가 처음 생기는 이슈에서 그 경로의 익명 401 · `SECURITY_001` 케이스를 서비스의 `*SecuritySmokeTest` 에
  메서드로 더한다 — `perform` · `bearer` · `SecurityEnvelopeAssertions.assertEnvelope` 를 쓰고 같은 컨텍스트를 공유한다.
  `@WebMvcTest` 같은 슬라이스로 쓸 때는 `*BeansConfig` 가 빠지므로 `ResourceServerSecurityConfigurer` · `JwtVerificationPropertiesConfig` 를
  `@Import` 한다(안 하면 Boot 기본 체인의 Basic 401 이 나온다). internal 컨트롤러 슬라이스는 `InternalResourceServerConfigurer` ·
  `InternalTokenPropertiesConfig` 도 import 한다.

## 보안 (서블릿 서비스 전부)

security-core 는 서블릿·Boot 보안 자동설정을 끌고 오지 않는다(게이트웨이가 리액티브라서). 서비스 `build.gradle` 이 직접 선언한다.

```groovy
implementation project(':core:security-core')
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-webmvc'
testImplementation testFixtures(project(':core:security-core'))   // 테스트 키 · 토큰 · 인가 스모크
testImplementation testFixtures(project(':core:common-core'))     // 폴백 오류 봉투 스모크
```

- 서비스마다 **폴백 오류 봉투 스모크 `*FallbackErrorSmokeTest extends FallbackErrorSmokeTestSupport {}`**(common-core 테스트 픽스처)를 둔다.
  도메인 · 보안 advice 가 받지 않는 404 · 405 · 415 · 400 · 500 이 `COMMON_00x` 봉투로 나가는지, `@Order` 없는 advice 가 여전히 먼저 받는지 본다.
  애노테이션이 `*ApplicationTests` 와 같아 컨텍스트를 같이 쓰고, 오류를 만드는 테스트 전용 핸들러(`/fallback-smoke/**`)는 픽스처의
  `ContextCustomizerFactory` 가 등록한다 — 서비스 컨트롤러에 기대지 않는다.
- 서비스마다 **`/error` 봉투 스모크 `<Svc>ErrorPageEnvelopeSmokeTest extends ErrorPageEnvelopeSmokeTestSupport`**(common-core 테스트 픽스처)를 하나 둔다.
  `customerAuthorization()`(security-core `TestTokens` 로 만든 고객 토큰)만 재정의한다. 실제 서블릿 컨테이너(`RANDOM_PORT`)로 방화벽 거부 400 · `COMMON_004`,
  직접 `/error` 404 · `COMMON_001`, 그리고 같은 컨테이너에서 `SECURITY_001` · `COMMON_001` 이 그대로인지 본다.
  RANDOM_PORT 라 MockMvc 스모크와 컨텍스트를 같이 쓰지 못해 서비스당 컨텍스트가 하나 더 뜬다(2~4초) — 서비스당 하나만 둔다.
  `FallbackErrorController` 는 Boot `ErrorMvcAutoConfiguration` 앞에 등록된다. **서비스는 `ErrorController` 를 따로 정의하지 않는다** — 정의하면 매핑이 겹쳐 기동이 실패한다.

- `*BeansConfig` 가 `ResourceServerSecurityConfigurer` · `InternalResourceServerConfigurer` 를, `*PropertiesConfig` 가 `JwtVerificationPropertiesConfig` ·
  `InternalTokenPropertiesConfig` 를 `@Import` 한다. auth-service 는 `JwtIssuerConfigurer` · `JwtIssuerPropertiesConfig` 를 더한다.
  internal Feign 클라이언트를 처음 만드는 서비스는 `InternalTokenIssuerConfigurer` 를 더한다 (`security-guide.md` §9-5).
- `security.jwt.*` yml 과 키 환경변수는 `security-guide.md` §5-3 · §6, `security.internal.*` 과 `INTERNAL_JWT_SECRET` 은 §9-6.

## Redis (쓰는 서비스만)

`*BeansConfig` 가 `RedisConfigurer` 를 `@Import` 하고 `application.yml` 에 아래를 둔다. 규칙과 기동 가드는 `coding-conventions.md` §9-7.

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
      timeout: 1s            # 명령 타임아웃 — 없거나 0 이하면 기동하지 않는다
      connect-timeout: 2s    # 연결 타임아웃 — 없거나 0 이하면 기동하지 않는다
```

- 타임아웃 값은 예시다. 서비스가 정하되 비우지 않는다.
- `url` · `sentinel` · `cluster` · `masterreplica` 는 쓰지 않는다 — standalone 만 지원하고 가드가 막는다.
- 키 TTL 은 서비스 프로퍼티로 둔다 (근거는 `coding-conventions.md` §9-7 TTL 표).

## Reason

- 스캔 범위를 `domainlayer`, `global` 로 제한하면 서비스 내부 구조가 명확해진다.
- 공통 설정을 명시적으로 import 하면 어떤 설정이 왜 로딩되는지 바로 보인다.
- `Beans / Properties / Features` 를 나누면 신규 서비스 추가 때 템플릿처럼 재사용하기 쉽다.
- 기본값을 닫힌 쪽에 두면 프로파일 누락 같은 배포 실수가 "열린 채 뜸" 이 아니라 "기동 실패" 나 "닫힌 채 뜸" 으로 끝난다.
