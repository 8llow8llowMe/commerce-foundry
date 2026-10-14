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
- Feign 이 필요한 서비스만 `@EnableFeignClients` 를 더한다.
- 스케줄러가 있는 서비스만 `@EnableScheduling` 을 더한다 (`global.config.*FeaturesConfig` 에 두는 것을 우선).

## Global Config

서비스 전역 설정은 `global.config` 아래에서 역할별로 나눈다.

| 파일 | 담는 것 |
|------|---------|
| `*BeansConfig` | 공통 Configurer `@Import` (Snowflake, Querydsl, SwaggerSecurity, ResourceServerSecurity) |
| `*PropertiesConfig` | 공통 프로퍼티 `@Import` + 서비스 도메인 프로퍼티 `@EnableConfigurationProperties` |
| `*FeaturesConfig` | JPA Auditing(`BaseEntity` 시각), Scheduling, Async 같은 기능 스위치 |
| `*SwaggerConfig` | 서비스 Swagger 그룹 (고객 / 어드민 그룹 분리) |

```java
@Configuration
@Import({
    SwaggerPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    JwtResourceServerPropertiesConfig.class
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
| `application.yml` | 포트, 서비스 이름, DB 플레이스홀더, 스키마, Eureka 주소, actuator, Snowflake 플레이스홀더, Swagger 기본값 |
| `application-local.yml` | `backend/.env` import, `ddl-auto: update`, Snowflake 고정값, Swagger 열기 |
| `application-dev.yml` | Swagger 열기. 값은 Vault 에서 환경변수로 받는다 (`secret-management.md`) |
| `src/test/resources/application-test.yml` | 컨텍스트 로딩 테스트용 H2 · Eureka 끄기 (아래 "컨텍스트 로딩 테스트") |

**기본값은 닫힌 쪽이다.** 프로파일이 빠진 채 배포돼도 열리거나 조용히 틀린 값으로 뜨지 않게, 여는 설정은 프로파일 yml 에만 둔다.

- `ddl-auto` 는 `validate`, local 만 `update`. 마이그레이션 도구는 첫 엔티티 이슈 전에 정한다 (`service-inventory.md` §5).
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
  따로 두고 CI 에서 돌린다.
- 단언: 공통 빈(Snowflake · `JPAQueryFactory` · JPA Auditing · Swagger 그룹), 서비스 이름 · 포트 · 스키마,
  `/actuator/health` 는 상태만(`components` · `details` 없음) · 다른 actuator 경로 404,
  기본 프로파일에서 Swagger 경로 404, Swagger 를 연 설정에서 그룹 문서 200 · 기본 문서 404.
- 새 서비스를 더하면 게이트웨이의 dev 라우트(`GatewayDevRouteConfig` 는 `ServiceRoute` 를 따른다)와 Swagger UI 목록
  (`api-gateway` 의 `application.yml` `springdoc.swagger-ui.urls`)도 같이 늘린다. `GatewayDevRouteTest` 가 어긋나면 잡는다.

## Reason

- 스캔 범위를 `domainlayer`, `global` 로 제한하면 서비스 내부 구조가 명확해진다.
- 공통 설정을 명시적으로 import 하면 어떤 설정이 왜 로딩되는지 바로 보인다.
- `Beans / Properties / Features` 를 나누면 신규 서비스 추가 때 템플릿처럼 재사용하기 쉽다.
- 기본값을 닫힌 쪽에 두면 프로파일 누락 같은 배포 실수가 "열린 채 뜸" 이 아니라 "기동 실패" 나 "닫힌 채 뜸" 으로 끝난다.
