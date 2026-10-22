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
| `*BeansConfig` | 공통 Configurer `@Import` (Snowflake, Querydsl, SwaggerSecurity, `ResourceServerSecurityConfigurer` · `InternalResourceServerConfigurer` — 전 서비스, `JwtIssuerConfigurer` — auth 만, `InternalTokenIssuerConfigurer` — internal Feign 클라이언트가 있는 서비스만, `RedisConfigurer` — Redis 를 쓰는 서비스만, `StorageConfigurer` — 오브젝트 스토리지를 쓰는 서비스만) |
| `*PropertiesConfig` | 공통 프로퍼티 `@Import` (`JwtVerificationPropertiesConfig` · `InternalTokenPropertiesConfig` — 전 서비스, `JwtIssuerPropertiesConfig` — auth 만, `StoragePropertiesConfig` — 오브젝트 스토리지를 쓰는 서비스만) + 서비스 도메인 프로퍼티 `@EnableConfigurationProperties` |
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
| `application.yml` | 포트, 서비스 이름, DB 플레이스홀더, DB 드라이버 속성(`logServerErrorDetail: false`), 스키마, Eureka 주소, actuator, Snowflake 플레이스홀더, `security.jwt.*` 플레이스홀더(`${JWT_PUBLIC_KEY}` · `${JWT_PUBLIC_KEY_PREVIOUS:}`), `security.internal.*`(`${spring.application.name}` · `${INTERNAL_JWT_SECRET}` · `${INTERNAL_JWT_SECRET_PREVIOUS:}`), Swagger 기본값 |
| `application-local.yml` | `backend/.env` import, `ddl-auto: update`, Snowflake 고정값, Swagger 열기 |
| `application-dev.yml` | `ddl-auto: update`, Swagger 열기. 값은 Vault 에서 환경변수로 받는다 (`secret-management.md`) |
| `src/test/resources/application-test.yml` | 컨텍스트 로딩 테스트용 H2 · Eureka 끄기 (아래 "컨텍스트 로딩 테스트") |

**기본값은 닫힌 쪽이다.** 프로파일이 빠진 채 배포돼도 열리거나 조용히 틀린 값으로 뜨지 않게, 여는 설정은 프로파일 yml 에만 둔다.

- `ddl-auto` 는 기본 `none`, local · dev 만 `update`. 운영은 애플리케이션이 스키마를 건드리지 않고 사람이 DDL 을 적용한다.
  마이그레이션 도구는 쓰지 않는다 (`coding-conventions.md` §9-8).
- 스키마는 `spring.jpa.properties.hibernate.default_schema` 로 서비스마다 지정한다. 다른 서비스 스키마를 직접 조회하지 않는다.
- DB 를 쓰는 서비스는 `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false` 를 둔다 — 제약 위반 로그에 행 값(개인정보)이
  실리지 않게 (`coding-conventions.md` §8). H2(컨텍스트 테스트)는 모르는 속성이라 무시하므로 test 프로파일에서 뺄 필요가 없다.
  order-service 는 아직 없다 — #13 C 에서 yml 과 `OrderServiceApplicationTests` 단언을 함께 넣는다.
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
  커넥션 풀이 드라이버에 넘기는 `logServerErrorDetail=false`(`HikariDataSource#getDataSourceProperties`),
  `/actuator/health` 는 상태만(`components` · `details` 없음) · 다른 actuator 경로 404,
  기본 프로파일에서 Swagger 경로 404, Swagger 를 연 설정에서 그룹 문서 200 · 기본 문서 404.
- 새 서비스를 더하면 게이트웨이의 dev 라우트(`GatewayDevRouteConfig` 는 `ServiceRoute` 를 따른다)와 Swagger UI 목록
  (`api-gateway` 의 `application.yml` `springdoc.swagger-ui.urls`)도 같이 늘린다. `GatewayDevRouteTest` 가 어긋나면 잡는다.
- 새 모듈은 CI 에서 자동으로 `rest` 그룹 job 에 들어간다(`.github/scripts/backend-ci-matrix.py`). 테스트가 길어져 `rest` job 이
  가장 늦게 끝나면 그 스크립트의 `GROUPS` 의 기존 그룹에 넣는다 — 그룹을 새로 늘리면 동시 실행 job 한도 때문에 대기가 길어진다(#194). 모듈 이름을 바꾸면 `GROUPS` 도 같이 고친다 — 어긋나면 plan job 이 실패한다.
  PR 의 CI 는 바뀐 모듈과 그 모듈에 의존하는 모듈만 돌린다 — 새 모듈이 core 를 `project(':core:...')` 로 의존하면 그 core 를 바꾼 PR 에서도
  같이 돈다. 의존은 `project(':...')`(또는 `project(path: ':...')`)로만 쓴다 — `projects.core.x` 같은 다른 형식은 CI 가 읽지 못해 PR 마다 전 모듈이 돈다.
  모듈 밖 파일을 읽는 테스트는 `inputs.file(rootProject.file('...'))` 로 선언하고, 그 파일이 CI `paths` 제외 대상(문서 등)이면
  `backend-ci.yml` 의 `paths` 에 재포함 패턴도 더한다(`local-run-guide.md` §4).

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

  지원 클래스는 Testcontainers 를 쓰는 모듈의 test 소스(`src/test/java/com/commercefoundry/support/`)에 둔다. 처음 만든 것은 product-service 이고,
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

## Storage (오브젝트 스토리지를 쓰는 서비스만)

`*BeansConfig` 가 `StorageConfigurer` 를, `*PropertiesConfig` 가 `StoragePropertiesConfig` 를 `@Import` 하고 `build.gradle` 에
`implementation project(':core:storage-core')` 를 더한다. 서비스가 받는 것은 `ObjectStorageClient` 빈 하나다 — AWS SDK 는 storage-core 의
`implementation` 이라 서비스 코드에서 보이지 않는다. 처음 쓰는 곳은 product-service 상품 이미지(#9 PR-F)다.

```yaml
storage:
  enabled: ${STORAGE_ENABLED:false}   # 닫힌 기본값 — 켜는 것은 배포 설정의 환경변수다(Vault 키 아님)
  endpoint: ${STORAGE_ENDPOINT:}
  region: ${STORAGE_REGION:}
  bucket: ${STORAGE_BUCKET:}
  access-key: ${STORAGE_ACCESS_KEY:}
  secret-key: ${STORAGE_SECRET_KEY:}
  public-base-url: ${STORAGE_PUBLIC_BASE_URL:}
  presign-ttl: 5m          # 업로드 서명 URL 유효 시간 — 1분 이상 15분 이하
  connect-timeout: 2s      # 켜면 필수 — 없거나 0 이하면 기동하지 않는다
  read-timeout: 5s         # 켜면 필수 — 바이트 사이 대기의 상한
  api-call-timeout: 8s     # 켜면 필수, read-timeout 이상 — SDK 호출 한 번(재시도 · 백오프 포함)의 전체 시간 상한
```

- **기본값은 꺼짐이다.** 꺼져 있으면 값이 비어 있어도(dev Vault 가 비어도) 기동하고, `ObjectStorageClient` 빈은 그대로 있되 I/O 메서드가 전부
  `StorageException(DISABLED)` 를 던진다. 어댑터는 빈 유무를 분기하지 않고 이 예외를 도메인 503 으로 번역한다. 공개 주소가 있으면 `publicUrlOf` 는 동작한다.
- **켜면 `StorageStartupGuard` 가 클라이언트를 만들기 전에 검사한다** — endpoint · region · bucket · access-key · secret-key · public-base-url 이 비었거나
  `${...}` 가 풀리지 않았는지, endpoint · public-base-url 이 https 인지(루프백 호스트만 http 허용), 버킷 이름 규칙, presign-ttl 1~15분,
  타임아웃 양수 · api-call-timeout 이 read-timeout 이상인지. 위반은 모아서 한 번에 알리고 메시지에 값을 넣지 않는다 (`RedisStartupGuard` 와 같은 방식).
  public-base-url 끝의 `/` 는 지운다. 규칙은 `StorageProperties.violations()` 한 곳에 있고 공개 팩토리 `ObjectStorageClients.create` 도 같은 규칙을 부른다 —
  가드를 거치지 않고 만들어도 http endpoint · 긴 TTL 의 클라이언트가 생기지 않는다(위반이면 `IllegalArgumentException`, 값은 메시지에 없다).
- **타임아웃은 한 호출의 전체 시간 < 게이트웨이 response-timeout 10s 로 맞추고, 재시도는 최대 2회 시도다**(storage-core 가 고정한다).
  connect · read 타임아웃은 시도 하나의 멈춤만 끊어서, 이것만 두면 SDK 기본 재시도(시도 3~4회) · 백오프와 겹쳐 스토리지가 멈췄을 때 호출 한 번이
  수십 초 동안 요청 스레드를 잡는다. `api-call-timeout` 이 재시도까지 포함한 상한이다. 타임아웃은 yml 기본값이고 `.env.example` 에 두지 않는다
  (connect · read 와 같은 취급 — 바꿔야 하면 relaxed binding 의 `STORAGE_API_CALL_TIMEOUT` 등으로 넣는다).
- 체크섬 계산 · 검증은 `WHEN_REQUIRED` 로 낮춰 두었다 — SDK 2.30 부터의 기본값(모든 업로드에 CRC)을 지원하지 않는 S3 호환 스토리지가 있다.
- 키 규칙(어느 prefix 에 무엇을 두는가) · 허용 형식 · 용량 상한은 서비스가 정한다. storage-core 는 키의 빈 구간 · `.` · `..` · 제어 문자만 막는다.
  형식은 `head()` 로 크기 · ETag 를 얻은 뒤 `ImageFormat.detect(client.readRange(key, ImageFormat.DETECTION_LENGTH, stored.eTag()))` 로 앞부분 magic bytes 를 본다
  (JPEG · PNG · WebP).
- **판별이 막는 것은 앞부분이 다른 형식인 파일이다** — SVG · HTML · GIF, 앞이 다른 형식이고 뒤에 JPEG 를 붙인 폴리글롯.
  유효한 이미지 헤더 뒤에 HTML · JS 를 품은 파일은 판별로 막지 못한다. 그 파일에 대한 실제 방어는 아래다.
  - copy 의 `MetadataDirective.REPLACE` 로 복사본의 Content-Type 을 판별한 이미지 형식으로 고정한다.
  - 공개 도메인에 `X-Content-Type-Options: nosniff` 를 둔다 — 브라우저가 내용을 보고 HTML 로 해석하지 않게.
  - 공개 도메인을 스토어 · 어드민 origin 과 분리한다 — 같은 origin 이면 해석된 스크립트가 그 화면의 쿠키 · DOM 에 닿는다.
  - 재인코딩(이미지를 다시 그려 뒤에 붙은 바이트를 버린다)은 후속이다.
- `StorageException.reason()` 으로 번역한다 — `UNAVAILABLE`(네트워크 · 타임아웃 · 5xx · 429) · `DISABLED` 는 503, `ACCESS_DENIED` · `REJECTED` 는 설정 문제(503 + 로그),
  `NOT_FOUND` 는 업로드가 없거나 만료된 것이다. `CHANGED`(412 — `head()` 로 확인한 뒤 같은 키에 다른 내용이 올라왔다)는 다시 확인하라는 4xx(400 · 409)로
  번역한다 — 어느 쪽인지는 서비스(#9 PR-F)가 정한다. 메시지에는 동작과 원인만 있고 키 · 서명 URL 은 없다.
- **서비스 테스트는 가짜 포트를 쓴다.** 컨텍스트 로딩 테스트는 기본값(꺼짐)으로 뜨고, 업무 테스트는 서비스의 out-port(`ImageStoragePort` 등) 가짜로 대신한다.
  실제 S3 호환 서버 왕복은 storage-core 의 `S3CompatibleObjectStorageRoundTripTest`(Testcontainers · `@EnabledWithDockerOrOnCi`)가 본다.

**서비스가 지켜야 할 것 (#9 PR-F 필수).** storage-core 는 도메인을 몰라 대신 막지 못한다 — 어댑터 · Processor 가 지킨다.

- **클라이언트가 보낸 키는 서버가 정한 패턴(예: `uploads/{UUID}.{ext}`) 정규식을 통과한 뒤에만** head · readRange · copy · delete 에 넘긴다.
  prefix 밖 키로 공개 객체(`products/…`)를 지우거나 복사 원본으로 쓰지 못하게 하기 위해서다. storage-core 의 키 검사는 경로 조작(`..` 등)만 막는다.
- presignPut 의 contentType 은 `ImageFormat.ofContentType(...)` 으로 정규화한 형식의 MIME(`ImageFormat.contentType()`)만 넘긴다.
  길이 상한은 서명 전에 서비스가 확인한다.
- copy 의 contentType 은 판별한 형식(magic bytes)에서 가져온다. HEAD 의 Content-Type 이나 요청 값은 쓰지 않는다 — 업로더가 정한 값이다.
- 검증 → 복사는 `head()` 의 eTag 로 묶는다 — readRange · copy 에 같은 eTag 를 넘기면, 그 사이 같은 키에 다른 내용이 올라온 경우 `CHANGED` 로 끊긴다.
- 서명 URL 은 로그에 남기지 않는다. `PresignedUpload.toString()` 은 서명을 가리지만, 서비스의 응답 DTO(record)는 기본 `toString` 에 서명 URL 이
  그대로 들어가므로 `toString` 을 가리거나 로그에 쓰지 않는다.

**스토리지 서버에 필요한 설정 (사용자 인프라 MinIO — #9 D2, 설정은 사용자가 한다).** 코드가 대신 걸지 않는다 — 켜기 전에 아래가 되어 있어야 한다.

| 항목 | 필요 조건 | 이유 |
|------|-----------|------|
| 주소 | path-style(`{endpoint}/{bucket}/{key}`) 접근, endpoint · 공개 주소는 https | 브라우저가 https 어드민 화면에서 서명 URL 로 바로 올린다 — http 면 막힌다 |
| 버킷 CORS | 어드민 origin 으로 **좁혀** `PUT` 과 `Content-Type` 요청 헤더를 허용 | presigned PUT 은 브라우저 → 스토리지 교차 출처 요청이다. MinIO 서버 CORS 기본값은 모든 origin(`*`)이다. 버전에 따라 버킷 CORS 대신 서버 설정 `MINIO_API_CORS_ALLOW_ORIGIN` 으로 건다 |
| 수명 규칙 | `uploads/` 접두어 객체를 1일 뒤 삭제 (MinIO ILM 만료 규칙) | 올리고 저장하지 않은 임시 업로드를 치운다 — 상품에 붙인 이미지는 `products/` 로 복사해 두므로 지워지지 않는다 |
| 공개 읽기 | `products/` 접두어에 **익명 GetObject 만** 준다(예: `mc anonymous set download <alias>/<bucket>/products`). 또는 그 앞의 CDN | 고객 화면은 `STORAGE_PUBLIC_BASE_URL` + 키로 이미지를 바로 읽는다. `public` 이나 버킷 전체를 열면 익명 쓰기 · 목록과 `uploads/`(검증 전 업로드)가 노출된다 |
| 접근 키 | 루트 계정 · 루트의 service account 가 아닌 **별도 사용자**에, 그 버킷으로 한정한 정책: GetObject · PutObject · DeleteObject(객체) + ListBucket(버킷) | 서비스가 쓰는 동작만 준다(CopyObject 는 원본 Get + 대상 Put). ListBucket 이 없으면 S3 계열이 없는 키 HEAD 에 404 대신 403 을 줘서 "업로드 없음" 이 설정 오류(`ACCESS_DENIED`)로 바뀐다 |
| 버전 | 보안 패치가 반영된 최신 MinIO 릴리스 | 익명 읽기 · 서명 검증을 맡는 서버다 |
| 리버스 프록시 (TLS 종료를 nginx 등에서 하면) | 원래 `Host` 를 그대로 넘긴다. 본문 상한은 업로드 상한(10MB) 이상. 공개 도메인에 `X-Content-Type-Options: nosniff` | `Host` 를 바꾸면 서명 검증이 403 이다. nginx 기본 본문 상한(1m)이면 413 이고 브라우저에는 CORS 오류로 보인다. nosniff 는 위 판별 한계의 방어다 |

## Reason

- 스캔 범위를 `domainlayer`, `global` 로 제한하면 서비스 내부 구조가 명확해진다.
- 공통 설정을 명시적으로 import 하면 어떤 설정이 왜 로딩되는지 바로 보인다.
- `Beans / Properties / Features` 를 나누면 신규 서비스 추가 때 템플릿처럼 재사용하기 쉽다.
- 기본값을 닫힌 쪽에 두면 프로파일 누락 같은 배포 실수가 "열린 채 뜸" 이 아니라 "기동 실패" 나 "닫힌 채 뜸" 으로 끝난다.
