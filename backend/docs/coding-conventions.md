# Backend Coding Conventions

## 1. 줄 길이 / 시그니처

- 한 줄 **180자** 하드랩. 메서드 선언·호출·`record` 파라미터가 180자 이하면 한 줄로 둔다.
- 넘으면 **의미 단위로 파라미터를 묶어** 줄바꿈한다. 하나씩 한 줄로 나열하지 않는다.

```java
public ProductListInfo searchProducts(
    long categoryId, ProductSaleStatus saleStatus, ProductSortType sortType,
    Long lastProductId, int size
) {
}
```

- Swagger `@Parameter(...) @RequestParam ...` 조합은 180자 이내면 한 줄로 쓴다.

## 2. Primitive / Wrapper

- `long`, `int`, `boolean` 은 기본적으로 primitive 를 쓴다.
- Wrapper 는 `null` 자체가 의미를 가질 때만 — 선택적 검색 조건, 미설정과 기본값의 구분.

## 3. Port / Adapter 파라미터

- 단순 조회·수정·삭제는 개별 파라미터를 우선한다.
- 조건이 4개 이상이거나 `filter + sort + cursor + size` 처럼 함께 움직이면 `*Criteria` / `*Query` 로 묶는다.

## 4. Mapper

- Entity ↔ Domain 매핑은 MapStruct 를 우선한다.
- 외부 API·내부 서비스 응답은 adapter 에서 `QueryResult` 또는 domain/model 로 변환한다.
- `Info → Response` 변환은 Presenter 책임이다.

## 5. 네이밍

| 대상 | 이름 |
|------|------|
| Controller | `*WebController` · `*AdminController` · `*InternalController` · `*WebhookController` |
| Scheduler | `adapter/in/scheduler/*Scheduler` — `@Scheduled` 진입점. `*SchedulerUseCase` 만 부르고, 건별 트랜잭션은 Facade 프록시를 건마다 불러 나눈다 |
| Bootstrap | `adapter/in/bootstrap/*Runner` — 기동 진입점(`ApplicationRunner`). 설정을 커맨드로 옮겨 `*SeedUseCase` 만 부른다. 설정 오류는 `IllegalStateException` 으로 기동을 멈춘다 (#6) |
| UseCase | `*WebUseCase` · `*AdminUseCase` · `*InternalUseCase` · `*SchedulerUseCase` (스케줄러 진입점) · `*SeedUseCase` (기동 진입점) |
| Facade | `*WebFacade` · `*AdminFacade` · `*InternalFacade` · `*SchedulerFacade` · `*SeedFacade` |
| Processor / Presenter | `*Processor` / `*Presenter` (고객 응답은 `*WebPresenter`, 어드민 응답은 `*AdminPresenter`, 서비스 간 응답은 `*InternalPresenter`) |
| Feign interface | `*Client` |
| 조회 결과 / 조건 | `*QueryResult` / `*Criteria` · `*Query` |

### 5-1. Out port 이름 — 책임으로 짓는다

| 카테고리 | Port | Adapter |
|----------|------|---------|
| 서비스 간 읽기 / 쓰기 | `*QueryPort` / `*CommandPort` | `*ClientAdapter` (`adapter/out/client/`) |
| JPA 영속성 | `*RepositoryPort` | `*RepositoryAdapter` (`adapter/out/persistence/`) |
| 인프라 특화 | 도메인 의미 그대로 — `PaymentGatewayPort`, `StockReservationPort`, `ShipmentTrackingPort`, `ImageStoragePort` | |
| 관리자 감사 | `AdminAuditPort` — 행위별 메서드(`adminLoginSucceeded` 등). Facade 는 직접 주입하지 않고 `AdminAuditProcessor` 를 부른다(H11) | `*AuditLogAdapter` (`adapter/out/log/` — 로그 한 줄). auth 는 `AdminAuditLogRepositoryAdapter`(`adapter/out/persistence/` — 로그 줄 뒤 DB 행, #192) (`security-guide.md` §11-4) |

전송 기술(`Feign`, `Http`, `Toss`)을 포트 이름에 넣지 않는다. 토스는 adapter 이름(`TossPaymentGatewayAdapter`)에만 나온다.

## 6. Swagger

- `@Tag`, `@Operation`, `@Parameter`, `@Schema` 를 기본으로 작성한다. 설명은 한국어.
- 인증이 필요한 API 는 `@SecurityRequirement` 를 명시한다.
- `/internal/**` 은 `@Hidden` 을 붙인다.

## 7. Record DTO

- 각 component 의 `@Schema` 를 component 바로 위 줄에 두고, component 사이에는 빈 줄을 둔다.
- validation annotation 은 `@Schema` 다음 줄에 둔다. `@Builder` 와 record 선언 사이에는 빈 줄을 두지 않는다.

```java
@Builder
@Schema(description = "상품 상세 응답")
public record ProductDetailResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "판매가")
    MoneyItem salePrice
) {
}
```

### 7-1. 응답 DTO 의 식별자는 String 이다 (필수)

내부 PK 는 Snowflake 라 19자리에 이르는데, 자바스크립트 `Number` 의 안전 정수 상한은 16자리다.
그 위의 값은 **예외 없이 조용히 반올림되고**, 목록은 멀쩡한데 상세 조회만 404 가 나는 버그가 된다.

| 위치 | 타입 |
| --- | --- |
| `dto/response`, `dto/item` 의 모든 `*Id` | `String` |
| `@PathVariable`, `@RequestParam` | `long` / `Long` |
| `dto/request` 의 `*Id` | `Long` (JSON 문자열도 Jackson 이 변환한다) |
| `application/info`, `domain/model` | `long` / `Long` |

경계는 **Presenter** 다: `.productId(String.valueOf(info.productId()))`. nullable 식별자는 null 을 유지한다.
`@Schema` 의 `example` 도 따옴표 안의 문자열로 적는다.

## 8. 예외 / 로그

- 로그는 검색 가능한 영어 키 + 값 조합을 쓴다: `log.warn("payment confirm failed. orderId={}, code={}", ...)`
- **로그에 남기지 않는 것**: PG 시크릿 키, 결제키 전체, 카드 정보, 고객 이름·전화번호·주소, JWT 원문, AI 프롬프트 · 응답 원문(#215 — 기능 · 모델 · 토큰 수만).
  식별이 필요하면 마스킹하거나 내부 ID 만 남긴다. 토큰은 `jti` 와 memberId 만 남긴다 — refresh 토큰도 원문 대신 핑거프린트다.
- **DB 제약 위반 로그에 행 값을 남기지 않는다 (#173).** Hibernate 는 예외를 바꾸기 전에 `SQLException` 메시지를 WARN(`org.hibernate.orm.jdbc.error`)으로
  남기고, pgjdbc 기본값은 그 메시지에 행 값(DETAIL `Failing row contains (...)` · `Key (email)=(...)`)을 싣는다 — 어댑터가 나중에 원인을 떼도 이미 늦다.
  - 1차 방어는 드라이버 옵션 `logServerErrorDetail=false` 다 — persistence-core 자동 구성(`ServerErrorDetailAutoConfiguration`)이 모든 `HikariDataSource` 에
    넣는다(yml 이 `true` 여도 덮는다. 감싼 DataSource 도 풀어서 찾는다. `@DataJpaTest` 슬라이스 목록에도 등록해 리포지토리 테스트도 같다).
    서비스 yml 에 두지 않는다 — 서비스마다 두는 방식은 한 곳에서 빠져도 조용히 기본값(true)으로 돌았다(#269 · #279).
    이 자동 구성을 `spring.autoconfigure.exclude` 하지 않는다. DataSource 를 직접 만들면 `DataSourceBuilder` · 무인자 `HikariDataSource` 를 쓴다 —
    `new HikariDataSource(HikariConfig)` 는 만들자마자 풀을 열어 속성을 넣지 못하고 기동이 실패한다.
    `DB_URL` 에 이 파라미터를 넣지 않는다 — URL 값이 속성보다 이기므로 자동 구성이 URL 에서 이 이름을 찾으면 기동을 실패시킨다. 이 옵션은 메시지의 DETAIL 만 빼고
    서버 오류 필드(제약 이름)는 그대로 두므로 persistence-core `ConstraintViolations` 의 판정(§9-1)은 그대로다 — 서버 필드라 `lc_messages` 로케일과도 무관하다
    (모든 서비스 어댑터가 이 판정을 쓴다 — 운영 PostgreSQL 의 `lc_messages` 에 대한 전제는 없다, #247 · #159).
    `ServerErrorDetailAutoConfigurationTest` · `ServerErrorDetailDataJpaSliceTest` 가 자동 구성을, 서비스 `*ApplicationTests` 가 실제 커넥션 풀의 속성을, auth `ShippingAddressPersistenceAdapterTest` 가 PostgreSQL 의 로그 · 예외 메시지를 본다.
  - 2차 방어(새 어댑터에 권장)는 예상 밖 위반을 다시 던질 때 원인(cause)도, 원래 예외의 메시지(`[SQLException 메시지]` 가 들어 있다)도 싣지 않고
    제약 이름만 싣는 것이다. Hibernate WARN 줄은 막지 못하고(1차 방어만 막는다), 그 뒤 예외 메시지 · 스택(전역 핸들러의 500 로그 등)으로 퍼지는 것만 줄인다.
    지금 어댑터들은 원인을 그대로 던지며, 1차 방어로 메시지에 행 값은 없다.
  - 도메인 `*ExceptionHandler` 의 5xx 로그는 코드와 원인 예외 타입(`causeType`, 단순 클래스명)만 남기고 예외를 throwable 로 넘기지 않는다 (#277) —
    스택 · 원인 사슬에 원인 메시지(행 값 · Feign 대상 응답 본문)가 실린다. `*ExceptionHandlerLogTest` 가 `OutputCaptureExtension` 으로 원인 메시지 · 스택이 없음을 본다.
  - 로깅 레벨 · `hibernate.jdbc.log.errors` 로 끄지 않는다 — 다른 SQL 오류의 진단까지 사라진다.
  - dev · 운영에서 `org.hibernate.orm.jdbc.bind` · `org.postgresql` TRACE 와 show-sql 바인드 출력을 켜지 않는다 — 이 옵션과 상관없이 바인드 값 · DETAIL 이 남는다.
- 사용자 노출 메시지와 내부 로그 메시지는 분리한다.
- **5xx 의 원인을 로그에 남기는 기준 (#280).** 처리되지 않은 500 이든 도메인 핸들러가 번역한 5xx 든 같다.
  남기는 것: 오류 코드 · 상태 · 메서드 · 가린 경로(앞 3구간) · 원인 사슬의 **예외 타입과 스택 프레임** · `SQLException` 의 SQLState(표준 5자리 모양일 때만).
  남기지 않는 것: 원인 사슬 어디의 **예외 메시지**든 — DB 행 값(DETAIL) · Feign 대상 응답 본문 · 요청 값이 들어 있다.
  - 예외를 로그 인자로 넘겨야 하면 원래 예외 대신 common-core `RedactedThrowable.of(exception)` 을 넘긴다 — 타입 · 프레임 · SQLState 만 남은 사본이다.
    공통 폴백(`FallbackErrorResolver` · `FallbackErrorController`)이 이렇게 남긴다. 스택이 필요 없으면 원인 타입 이름만 남긴다(도메인 핸들러의 `causeType`).
  - 미지의 500 은 로그의 타입 · 프레임(어느 클래스 · 줄에서 났는지)과 코드 · 경로 · 시각 · 상관 id 로 좁힌다. 값이 필요하면 재현으로 확인한다 — 로그에 값을 남겨 풀지 않는다.
    요청 상관 id(#284)는 Micrometer Tracing 이 아니라 헤더 `X-Correlation-Id` 다. 추적 백엔드가 없고, W3C `traceparent` 는 외부 값을 그대로 믿게 된다.
    게이트웨이 `GatewayCorrelationIdFilter` 가 가장 앞에서 id 를 정하고 하류로 넘긴다. 서블릿 서비스 `CorrelationIdFilter` 가 MDC `correlationId` 에 넣고,
    로그 패턴 `logging.pattern.correlation`(`%X{correlationId}`)이 같은 요청의 줄에 싣는다. 에러 디스패치의 500 로그도 같은 값이다.
    외부 값은 소문자 UUID 하나일 때만 받고, 없거나 둘 이상이거나 형식이 틀리면 버리고 새로 만든다. 틀린 원문(줄바꿈 · 긴 값)은 로그에 남기지 않는다.
    internal Feign 은 그 MDC 값만 최종 요청에 싣는다(§10). 사용자 `Authorization` 은 복사하지 않는다. 응답 봉투 · 응답 헤더에는 싣지 않는다 — 클라이언트에 보이는 계약은 FE 와 따로 정한다.
  - 받아들인 손실: 값이 없는 JDK 메시지(Helpful NPE 의 "because ... is null" 등)도 함께 버린다 — 한 줄에 역참조가 여럿이면 줄 번호만으로 좁히지 못한다.
    스택을 끈 예외는 타입만 남는다. 로그 이벤트의 예외 클래스는 언제나 `RedactedThrowable` 이고 원래 타입은 메시지 자리에 있다 —
    예외 타입으로 묶는 수집 도구(Sentry · OTel appender 등)를 붙이면 원래 타입을 키로 매핑해야 한다.
  - 필터 단계 예외(#283): 필터 · `DispatcherServlet` 이 던진 예외가 컨테이너까지 올라가면 Tomcat(`StandardWrapperValve`)이 에러 디스패치 전에
    원래 예외를 메시지째 자기 로거로 남긴다. 그래서 가장 바깥 필터(common-core `UnhandledFilterExceptionFilter`, 최우선 순서)가 받아
    `ERROR_EXCEPTION` 속성 + `sendError(500)` 으로 `/error` 에 넘긴다 — 로그는 `FallbackErrorController` 가 위 기준으로 남기고 응답은 500 · `COMMON_000` 봉투다.
    응답이 이미 커밋됐으면 봉투를 못 쓰므로 메시지 없는 사본(원인은 `RedactedThrowable`)을 다시 던진다 — 컨테이너가 연결을 끊어 클라이언트가
    잘린 본문을 정상 응답으로 받지 않고, Tomcat 로그에는 그 사본만 남는다. 클라이언트 연결 끊김은 그대로 던진다(Tomcat 이 DEBUG 로만 남긴다).
    `VirtualMachineError` 는 삼키지 않는다. Tomcat 로거 레벨로 끄지 않는다 — 로거 이름이 서블릿 이름에 묶여 깨지기 쉽고, 다른 진단까지 사라진다.
    서비스 필터는 `Ordered.HIGHEST_PRECEDENCE` 를 쓰지 않는다 — 같은 순서면 이 필터 바깥에 놓일 수 있다.
    MockMvc 는 에러 디스패치를 하지 않으므로 `@SpringBootTest` + MockMvc 에서 필터 예외는 예외가 아니라 본문 없는 500 으로 보인다 — 원인은 컨테이너 테스트로 본다.
  - 게이트웨이 500 · 커밋 뒤 오류도 같은 기준이다(#285 · #291). 예외는 common-core `RedactedThrowable.of`, 경로는 서블릿에 의존하지 않는 `LogPathMask`(앞 3구간, 남기는 구간의 `;` 는 `;…`)를
    폴백 · `SecurityErrorResponder` · 게이트웨이가 같이 쓴다. 응답이 이미 커밋됐으면(하류가 본문 스트리밍 중 연결을 끊는 경우) 원래 예외를 다시 던지지 않고 그 사본을 흘린다.
    실측: 그 상황에서 `Operators.onErrorDropped` 기본 로그(`Operator called default onErrorDropped`)는 나지 않았다. reactor-netty `HttpServerOperations` 는
    `Error finishing response. Closing connection` 을 ERROR 로 남겼고, 원래 예외를 흘리면 스택 체크포인트에 원문 경로 · 쿼리가 붙었다. 사본을 흘리면 그 줄의 예외는
    `RedactedThrowable` 이고 경로 · 쿼리 · 원래 메시지는 없다. `Hooks.onErrorDropped` 는 이 로거를 바꾸지 못하므로 걸지 않는다.
    `HttpWebHandlerAdapter` 는 원래 예외 · 원문 경로 · 쿼리를 찍으므로 끈다.
  - 남은 경로: ASYNC 디스패치(비동기 MVC) 중의 필터 예외는 여전히 Tomcat 이 메시지째 남긴다 —
    지금 서비스는 비동기 MVC 를 쓰지 않는다. 쓰기 시작하면 이 필터의 ASYNC 디스패치 처리를 함께 정한다.

### 8-1. 예외 패턴 (필수)

공통 `BadRequestException` 같은 것을 쓰지 않는다. 도메인마다 3종 세트를 둔다.

| 파일 | 위치 | 내용 |
|------|------|------|
| `{Domain}ErrorCode` | `application/exception/` | enum — `code`, `message`, `HttpStatus` 3 필드 |
| `{Domain}Exception` | `application/exception/` | `extends RuntimeException`, `super(errorCode.getMessage())` |
| `{Domain}ExceptionHandler` | `adapter/in/web/exception/` | `@RestControllerAdvice(basePackages = "...domainlayer")`, `Response.fail()` 변환 |

진입 API 가 없는 컨텍스트(order-service `catalog`)는 `*ExceptionHandler` 를 두지 않고, 호출한 컨텍스트의 핸들러가 자기 코드로 번역한다 (`architecture-guide.md` §3).

```java
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode {

    NOT_FOUND_ORDER("ORDER_001", "존재하지 않는 주문입니다.", HttpStatus.NOT_FOUND),
    ORDER_AMOUNT_MISMATCH("ORDER_010", "주문 금액이 변경되었습니다. 다시 시도해주세요.", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
```

- 생성자는 `(errorCode)` 필수, 필요 시 `(errorCode, Object... args)`(메시지의 `%s` 채움), `(errorCode, Throwable cause)`.
  **임의 문자열을 잇는 `(errorCode, String detail)` 은 만들지 않는다.**
- 응답 헤더가 필요한 오류(429 `Retry-After` 등)는 `{Domain}Exception` 의 하위 클래스가 그 값을 타입으로 싣고(예: auth `LoginAttemptLimitedException` 의 `Duration retryAfter`),
  같은 `*ExceptionHandler` 의 더 좁은 `@ExceptionHandler` 가 헤더를 붙인다 — 본문 봉투는 상위 예외와 같다. 메시지에 값을 잇지 않는다.
  예외는 원값만 싣고, **초 변환 · 최소값 같은 헤더 형식은 핸들러가 한다**(예: `MemberExceptionHandler` 의 `Retry-After = max(1, ceil(ms/1000))`).
- **domain 은 application(예외 포함)을 import 하지 않는다. 업무 오류 코드는 Processor 가 붙인다** — 도메인 규칙 위반은 domain 소유 예외(현재 상태 · 시도한 동작을
  싣는다)나 결과 값으로 알리고, Processor 가 받아 `{Domain}Exception` 으로 바꾼다. 바꾸지 않고 흘리면 500 이다.
  예: product-service `StockReservationTransitionException` → `StockReservationProcessor` 가 `PRODUCT_024` · `PRODUCT_025` (#10).
- 한 서비스에 advice 가 둘 이상이면 좁은 범위 advice 에 `@Order` 를 붙여 우선순위를 확정한다.
- **요청 검증 예외(`MethodArgumentNotValidException` · `HttpMessageNotReadableException` · 타입 불일치 · 필수 파라미터)는 서비스당 advice 하나가 받는다** —
  검증 메시지가 코드(`AUTH_121:...`)를 싣고 오므로 컨텍스트를 몰라도 되고, 두 advice 가 같은 예외를 잡으면 순서에 따라 응답이 바뀐다.
  auth-service 는 `MemberExceptionHandler` 다. 다른 컨텍스트 advice(예: `ShippingAddressExceptionHandler`)는 자기 도메인 예외만 잡는다 (#7).
- 도메인 `*ExceptionHandler` 는 `Exception` · `RuntimeException` 을 통째로 잡지 않는다. 보안 예외(`@PreAuthorize` 거부)는
  security-core 의 advice 가 처리한다 — 통째로 잡으면 403 이 500 이 될 수 있다 (`security-guide.md` §5-2).
- 어느 advice 도 받지 않은 오류(매핑 없는 경로 404 · 메서드 405 · 미디어 타입 415 · 처리되지 않은 예외 500)는 common-core 의
  `FallbackErrorResolver` 가 `COMMON_00x` 봉투로 쓴다 (`api-design-guide.md` §2-1 공통 오류 코드). 서블릿 서비스에 자동 구성으로 걸리고,
  MVC 예외 처리 사슬에서 모든 advice **뒤**에 서므로 도메인 advice 에 `@Order` 를 달 필요가 없다. "남는 오류를 잡으려고" 도메인 핸들러에
  `Exception` 을 더하지 않는다.

### 8-2. 검증 에러코드 (필수)

- 검증 오류는 `{DOMAIN}_400` 처럼 뭉뚱그리지 않는다. **필드별 코드를 `1xx` 대역**에 둔다.

| 코드 | 정의 위치 | 용도 |
| --- | --- | --- |
| `{DOMAIN}_100` | ErrorCode `INVALID_REQUEST` | 접두어 없는 메시지 · 역직렬화 실패(깨진 JSON, enum 에 없는 값)의 폴백 |
| `{DOMAIN}_101~` | `{Domain}ValidationMessage` 상수 | 필드별 개별 코드 |
| 대역 끝 2개 | ErrorCode `PARAMETER_TYPE_INVALID`, `PARAMETER_REQUIRED` | 타입 불일치, 필수 파라미터 누락 |

- **`1xx` 대역이 차면 `200~` 을 이어 쓴다.** 대역 끝 `198` · `199` 는 옮기지 않는다(이미 나간 계약이다). 판정기(`ValidationErrorSupport`)는 세 자리 숫자면 대역을 가리지 않는다.
  처음 쓴 곳: product-service SKU 원가 `PRODUCT_200~208`(`SkuCostValidationMessage`, #361) — 101~197 이 상품 · 전시 · 사이즈표로 찼다.

```java
public final class ProductValidationMessage {

    public static final String NAME_REQUIRED = "PRODUCT_101:상품명은 필수입니다.";
    public static final String NAME_LENGTH_INVALID = "PRODUCT_102:상품명은 100자 이하만 가능합니다.";

    private ProductValidationMessage() {
    }
}
```

- DTO 는 상수를 참조한다: `@NotBlank(message = ProductValidationMessage.NAME_REQUIRED)`.
  문자열을 DTO 에 직접 적으면 오타가 조용히 폴백 코드로 샌다.
- 핸들러는 `common-core` 의 `ValidationErrorSupport` 에 위임한다. **핸들러가 없으면 Spring 기본 400 이
  `Response` 봉투 없이 나가 프론트 파서가 깨진다.**
- `@RequestParam` · `@PathVariable` 의 제약은 Spring MVC 의 메서드 검증이 `HandlerMethodValidationException` 으로 낸다.
  **컨트롤러 클래스에 `@Validated` 를 달지 않는다** — 달면 AOP 검증으로 바뀌어 `ConstraintViolationException` 이 나고,
  도메인 핸들러가 받지 않으면 봉투 없는 500 이 된다. 필수 파라미터 누락(`MissingServletRequestParameterException`)은
  `ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), {DOMAIN}_199)` 로 답한다.
- **제약 붙은 경로 변수 · 쿼리 파라미터와 `@RequestBody @Valid` 가 한 메서드에 함께 있으면** Spring 이 본문 검증까지 메서드 검증으로 묶어
  `HandlerMethodValidationException` 으로 낸다 — 그대로 `ValidationErrorSupport` 에 넘기면 본문 필드명이 파라미터 이름(`request`)으로 뭉개진다.
  도메인 핸들러가 bean 결과가 있을 때 `MethodArgumentNotValidException` 모양(필드명 · record 선언 순서)으로 옮기고 파라미터 오류를 함께 싣는다.
  예: product-service `ProductExceptionHandler` · `PATCH /api/v1/admin/stocks/{skuId}` (#10).
- 한 필드의 여러 오류는 순서를 고정한다: 필드 선언 순서 → 필수 → 길이 → 범위 → 형식.
  `ValidationErrorSupport` 는 표준 제약(`@NotBlank` · `@Size` · `@Email` …)의 자리만 이름으로 안다. **서비스가 만든 제약은
  애노테이션 위에 `@ConstraintPriority(Level.LENGTH)` 처럼 자기 자리를 밝힌다** — 밝히지 않으면 표준 제약 모두의 뒤에 서서,
  형식도 틀리고 정규화 뒤 길이도 넘을 때 대표 코드가 길이가 아니라 형식이 된다. common-core 에 서비스 제약 이름을 적지 않는다 (#261).
  예: auth `@ValidNormalizedEmailLength` · order `@NormalizedMaxLength`. 자리는 원본 `ConstraintViolation` 에서 읽으므로,
  핸들러가 오류를 새 `FieldError` 로 옮겨 담을 때는 `wrap(violation)` 으로 원본을 실어야 한다 — 싣지 않으면 이름으로만 판정해 맨 뒤에 선다.
- 컬렉션 원소는 `@NotNull` 과 값 제약을 쌍으로 건다 — `List<@Positive Long>` 만 걸면 `[null]` 이 통과한다.
- **검증 `1xx` 대역이 다 찬 서비스는 컨텍스트 접두어를 새로 연다** (order-service 의 `CART_` · `ORDER_` 선례). 새 컨텍스트는 업무 오류 `{CONTEXT}_001~` 과
  검증 `{CONTEXT}_101~` 을 자기 `ErrorCode` · `ValidationMessage` 에 두고, 폴백 · 타입 불일치 · 필수 파라미터(`_100` · 대역 끝 2개)는 서비스 공용 코드를 그대로 쓴다.
  서비스 공용 검증 핸들러는 메시지 앞의 코드를 읽으므로 접두어가 달라도 고칠 것이 없다. 예: product-service 룩(#62) — `PRODUCT_1xx` 소진, `LOOK_001~008` · `LOOK_101~122`.

### 8-3. 하드코딩된 문자열 금지

반복 사용되는 상태·구분 값은 enum 으로 정의한다 — `OrderStatus`, `PaymentStatus`, `PaymentMethod`,
`DeliveryStatus`, `ProductSaleStatus`, `ClaimType`. 응답으로 내보낼 때는 metadata 객체(§11)를 쓴다.

## 9. 엔티티 / 영속성

### 9-1. 기본 규칙

- **JPA 연관관계 어노테이션(`@ManyToOne` / `@OneToMany` / `@OneToOne` / `@ManyToMany` / `@JoinColumn` / `@JoinTable`)을
  쓰지 않는다.** 모든 관계는 raw FK 컬럼으로 표현하고, 그래프 탐색은 application 계층에서 별도 조회한다.
  서비스 경계를 흐리지 않고 지연로딩 N+1 을 구조적으로 없앤다.
- 필드 설명은 JPA 3.2 의 `@Column(comment = ...)`, 테이블 설명은 `@Table(comment = ...)` 로 단다.
  Hibernate `@Comment` 는 Hibernate 7 에서 deprecated 라 쓰지 않는다.
  FK 컬럼은 `(FK: target_table.id)` 를 명시한다: `@Column(name = "product_id", comment = "상품 아이디 (FK: product.id)")`.
  코멘트에 작은따옴표(`'`)를 쓰지 않는다 — Hibernate 가 DDL 의 `comment on ... is '...'` 안에서 이스케이프하지 않아 스냅숏 · 운영 DDL 이 깨진다(#63).
  다른 서비스 테이블을 가리키면 `(외부 FK: product-service product.id)` 로 적는다 — DB 제약은 걸 수 없다.
- 상태가 있는 엔티티(주문·결제·재고)는 **낙관적 락(`@Version`)** 을 기본으로 검토한다.
  - 버전은 **`Long`(wrapper)** 으로 두고 도메인 모델도 `Long version` 을 들고 다닌다. 쓰기는 `domain → mapper.toEntity → repository.save` 다 —
    새 행은 버전이 null 이라 Spring Data 가 `persist`(INSERT)로, 읽어 온 행은 버전이 있어 `merge`(버전 비교 · `UPDATE ... where version = ?`)로 간다.
  - 상태를 바꾼 저장은 `saveAndFlush` 로 바로 내보내고, 어댑터가 `ObjectOptimisticLockingFailureException` 을 도메인 409 로 바꾼다 —
    커밋 시점까지 미루면 트랜잭션 밖에서 터져 도메인 예외로 바꿀 수 없다. 유니크 키 위반(`DataIntegrityViolationException`)도 같은 자리에서 바꾼다.
  - 도메인은 생성 시각을 들고 다니지 않는다. `created_at` 이 `updatable = false` 라 merge 가 덮어쓰지 않는다.
  - 상태 저장(버전 검사)을 **먼저** 하고 그 결과에 딸린 쓰기(재고 복원 등)를 뒤에 한다 — 동시 요청에서 진 쪽이 딸린 쓰기까지 가지 않는다.
  - 예: product-service `StockReservationEntity` · `StockReservationRepositoryAdapter` (#10).
  - lease · 진행률처럼 **상태가 아닌 칸**의 잦은 갱신은 다시 읽어 merge 하지 않고 조건부 UPDATE 한 문장(§9-5)으로 한다 — 조건에 주인(워커 · 시도)을 넣고 영향 행 수로 "아직 주인인가" 를 판정한다.
    다른 쓰기가 버전 비교로 이 칸을 덮지 않게 해야 하면 그 UPDATE 도 버전을 올린다. 예: operations `JobRepository` 의 heartbeat — 올리지 않으면 lease 회수가 옛 버전으로 merge 에 성공해 방금 연장한 lease 를 빼앗는다 (#212).
- **비관적 잠금(`@Lock(PESSIMISTIC_WRITE)`)은 상태를 바꾸지 않는 쓰기가 같은 부모 행 기준으로 합계 · 상한을 판정할 때만 쓴다** — 상태 전이가 없으면
  `@Version` 이 경합을 잡지 못하고, 각자 읽은 합으로 함께 상한을 넘긴다. 정적 JPQL(`select ... where id = :id`)에 `@Lock` 을 달고 이름은 `findByIdForUpdate` 로 둔다
  (네이티브 `for update` 금지 — §9-5). PostgreSQL 에서는 `for no key update`(Hibernate 7) 다 — 같은 잠금 · 그 행의 UPDATE 와만 충돌하고,
  FK 검사의 KEY SHARE 는 막지 않는다. 잠금 순서는 다른 경로와 같은 순서(부모 행 → 자식 · 재고 행 아이디 오름차순)로 맞춰 교착을 막는다.
  - **잠금 대기 상한을 둔다** — `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = ...))`. 값은 상수로 두고 호출자 Feign read timeout 보다
    짧게 잡는다(길면 호출자가 먼저 끊고 재시도해 같은 요청이 또 줄을 선다). 넘으면 어댑터가 `PessimisticLockingFailureException`
    (`CannotAcquireLockException` 포함, PostgreSQL SQLState `55P03`)을 **재시도 가능한 도메인 409** 로 바꾼다.
  - 예: product-service 결제 후 재고 복원이 선점 헤더를 잠근다 (`StockReservationRepository#findByIdForUpdate` · 상한 2000ms · 초과 `PRODUCT_031`,
    #85 — `order-flow-guide.md` §3-2). SKU 원가 이력 추가는 SKU 행을 잠근 뒤 최신 행을 읽어 단조 규칙을 판정한다
    (`SkuRepository#findByIdForUpdate` · 상한 2000ms · 초과 `PRODUCT_078`, #361 — 합계가 아니라 "최신보다 뒤" 판정이지만 같은 이유다).
- **아이디(Snowflake · 다른 테이블의 PK)를 직접 넣고 `@Version` 이 없는 엔티티**는 `Persistable` 을 구현해 `isNew()` 를 `getCreatedAt() == null`
  ("감사 시각이 아직 없다") 로 둔다. 기본 `save` 는 아이디가 있으면 merge 로 가서 행마다 SELECT 를 한 번 더 보내고, PK 를 업무 키로 쓰는 행(`stock.sku_id`)은
  동시 첫 INSERT 가 먼저 커밋된 행을 조건 없이 덮어쓴다. persist 면 PK 충돌로 실패한다.
  예: auth `MemberAgreementEntity`, product `StockEntity` · `StockReservationItemEntity` · `StockAdjustmentHistoryEntity` · `StockRestorationEntity` · `StockRestorationItemEntity` · `CategoryEntity` · `SkuCostEntity`(append-only · `@Immutable`, 포트 `insert`, #361), delivery `ShippingPolicyHistoryEntity` · `ShippingPolicyHistoryAreaEntity`.
  - Snowflake 를 직접 넣는 Persistable 엔티티의 새 행 저장 포트 메서드는 `insert` 다 — persist 의미(아이디 충돌은 PK 위반)를 이름에 드러낸다 (예: `CategoryRepositoryPort.insert`, #9).
  - append-only 이력도 Persistable 이면 포트 메서드는 `insert` 다 (예: delivery `ShippingPolicyHistoryRepositoryPort.insert`, #69).
    product 재고 조정 이력의 `append` 는 이 규칙 전에 만든 이름이다.
- 유니크 키 위반 · 버전 충돌을 도메인 오류(409)로 바꿔야 하는 저장은 어댑터에서 `saveAndFlush` 로 바로 내보내고 그 자리에서 바꾼다 —
  커밋 때 터지면 예외가 Facade 밖(트랜잭션 프록시)에서 나 바꿀 자리가 없다. 제약은 예외 메시지가 아니라 persistence-core `ConstraintViolations` 로 가린다 —
  PostgreSQL 서버 오류 필드(`PSQLException#getServerErrorMessage().getConstraint()`) 우선(없을 때만 Hibernate `getConstraintName()`), 로케일 무관, 정확 일치
  (소문자 · 따옴표 · 스키마 접두사만 무시, 부분 일치 금지 — `payment_pkey` 는 `payment_cancel_pkey` 가 아니다). 예: auth `MemberRepositoryAdapter`, order `CartItemRepositoryAdapter`,
  payment `PaymentRepositoryAdapter`, delivery `ShippingPolicyRepositoryAdapter`, operations `JobRepositoryAdapter`. **판정 코드는 이 클래스 하나다** — 서비스에 복사본을 두지 않는다(#247).
  - H2 는 서버 필드가 없고 Hibernate 가 메시지 조각(`auth.uk_member_email INDEX auth.uk_member_email_INDEX_B`, auth H2 실측)을 넘겨 이름이 `uk_member_email_index_b` 가 되어 판정에 걸리지 않는다 — 허용하지 않는다. H2 는 컨텍스트 로딩용이라
    유니크 위반의 도메인 오류 변환은 Testcontainers(PostgreSQL) 리포지토리 테스트에서 본다.
  - persistence-core 를 쓰는 서비스는 postgresql 드라이버가 runtime 에 있어야 한다 — 없으면 제약 위반 시 `instanceof PSQLException` 에서 NoClassDefFoundError (지금은 서비스마다 `runtimeOnly`).
  - 서버 필드가 우선이라 로케일(`lc_messages`)이 한국어여도 같은 판정이 나온다 — 단위 테스트는 한국어 메시지 · Hibernate 이름 null 인 서버 오류를 꾸며 본다
    (persistence-core `ConstraintViolationsTest`, delivery `ShippingPolicyRepositoryAdapterCreateConflictTest`).

### 9-2. 필드 타입

| 필드 | 엔티티 | 도메인 모델 |
|------|--------|-------------|
| PK `id` | `Long` | `long` |
| FK | `Long` | `long` (nullable 의미가 있으면 `Long`) |
| 카운트·NOT NULL DEFAULT 0 | primitive | primitive |
| boolean | primitive | primitive |
| 시각 | `Instant` (`timestamptz`) | `Instant` |

- 시각은 `LocalDateTime` 이 아니라 **`Instant`** 로 저장한다. 해외 주문이 같은 테이블에 들어오므로 서버 시간대에 묶지 않는다.
  화면 시간대(KST 등) 변환은 Presenter 가 한다. 생성·수정 시각은 `persistence-core` 의 `BaseEntity` 를 상속한다.

### 9-3. 금액 (필수)

- **금액은 `long` 최소 화폐 단위 + `CurrencyCode` 로 저장한다.** KRW 는 원, USD 는 센트.
- `double` / `float` 로 금액을 다루지 않는다. `BigDecimal` 은 환산·비율 계산 중간값에만 쓰고 저장 전에 최소 단위로 확정한다.
- 금액 컬럼은 항상 통화 컬럼과 짝을 이룬다: `sale_price_amount` + `sale_price_currency`.
- 반올림 규칙(할인·환산)은 한 곳(`shared-commerce` 의 `Money`)에만 둔다.
  - 비율 계산(`Money.multiply` — 할인액·수수료)은 최소 단위 미만을 **버린다**(`RoundingMode.DOWN`). 할인액이 원가를 넘지 않는다.
  - 소수 표현 변환(`Money.ofMajorUnit` — PG 응답 `89.50`)은 **반올림하지 않는다.** 자릿수가 넘치면 실패한다.
  - 음수 `Money` 는 만들지 않는다. 할인·환불은 양수로 들고 `minus` 로 뺀다 — 결과가 음수면 계산 오류로 실패한다.
- `BigDecimal` 비율은 문자열로 만든다(`new BigDecimal("0.1")`). `new BigDecimal(0.1)` 은 이진 오차를 그대로 가져온다.

### 9-4. 인덱스 · 제약 명명

- 일반 `idx_{table}_{col1}_{col2}`, 유니크 `uk_{table}_{col1}_{col2}`. snake_case, **모든 컬럼을 이름에 담는다.**
- check 제약 `ck_{table}_{의미}` — 예: `ck_product_list_price_pair`, `ck_sku_additional_price_non_negative`.
  JPA 3.2 `@Table(check = @CheckConstraint(name = ..., constraint = ...))` 로 단다. 표현식은 H2(PostgreSQL 모드 — 컨텍스트 테스트)와
  PostgreSQL 이 둘 다 받는 표준 SQL 로 쓴다. `update` 는 이미 있는 테이블에 check 제약을 더하지 않는다 —
  기존 테이블에 더하면 운영 DDL 에 `alter table ... add constraint ... check (...)` 를 적고 dev 에도 직접 적용한다.
  기존 컬럼에 거는 제약이면 적용 전에 위반 행이 없는지 `select` 로 먼저 확인한다 — 있으면 `add constraint` 가 실패한다.
  - 문자 형식은 정규식(`~` · `REGEXP_LIKE`)이 두 엔진에서 달라 쓰지 않는다. 숫자만인지는 `ltrim(col, '0123456789') = ''` 로 본다
    (예: `ck_sku_hs_code_format`). `translate(col, '0123456789', '')` 는 H2 에서 문자를 지우지 않아(빈 치환 문자열을 무시) 맞는 값까지 거절한다.
- PostgreSQL 식별자 상한은 **63자**다. 넘으면 의미를 해치지 않는 범위에서 축약한다. 스냅숏 테스트가 접두어(`idx_` · `uk_` · `ck_` + 테이블명)와 길이를 검사한다.

### 9-5. 쿼리 작성 (필수)

위에서 해결되면 아래로 내려가지 않는다.

| 상황 | 수단 | 위치 |
|------|------|------|
| 조건이 고정된 단순 조회 | 파생 쿼리 | `repository/` |
| 조건이 고정된 복합 조회 · 조건부 UPDATE | 정적 JPQL `@Query` (`@Modifying`) | `repository/` |
| **조건이 동적으로 켜지고 꺼지는 조회, 조인** | **QueryDSL** (엔티티 조인 `.join(entity).on(...)`) | `repository/custom/{X}CustomRepository` + `Impl` |
| 네이티브 쿼리 | **쓰지 않는다** | 위 수단으로 안 되면 설계를 다시 본다 |

- **재고 차감은 조건부 UPDATE 한 문장으로 한다** — `update Stock s set s.available = s.available - :qty
  where s.skuId = :skuId and s.available >= :qty`. 조회 후 차감(read-modify-write)은 동시 주문에서 초과 판매를 만든다.
- 조건부 UPDATE · bulk DELETE 는 `@Modifying(flushAutomatically = true, clearAutomatically = true)` 로 둔다 — 앞서 쌓인 쓰기를 먼저 내보내고, 뒤이은 조회가 옛 값을 읽지 않게 한다.
  clear 뒤에는 앞서 읽은 엔티티가 분리되므로 다시 쓰지 말고 다시 읽는다 (예: delivery `ShippingPolicyRepositoryAdapter.replace` 의 `findById`).
  **bulk UPDATE 는 감사 리스너를 거치지 않으므로 `updatedAt` 을 인자로 받아 쿼리에서 직접 세팅한다** (`StockRepository`).
- 조건부 UPDATE 는 대상마다 한 문장이라 여러 SKU 를 루프에서 부르는 것은 §9-6 의 예외다. 대신 **SKU 아이디 오름차순**으로 부른다 — 행 잠금 순서를 맞춰 교착을 막는다.
- 상태 엔티티의 상태를 JPQL bulk UPDATE 로 바꾸지 않는다 — 도메인 전이 → merge(`@Version`) 로만 바꾼다 (§9-1).
- `@Param` 은 쓰지 않는다 (`-parameters` 가 켜져 있다).
- **커스텀 구현과 JPQL 은 컴파일로 검증되지 않는다.** `@DataJpaTest` 슬라이스 테스트로 실제 스키마에 질의한다.

### 9-6. N+1 금지 (필수)

- 루프·스트림 안에서 `Port.` / `Repository.` / `Client.` 단건 호출을 하지 않는다.
- DB 반복 → `in` 절 벌크 조회. 원격(Feign) 반복 → 대상 서비스에 **벌크 내부 엔드포인트**를 연다
  (예: 장바구니 상품 20개 가격 조회 → `GET /internal/v1/skus?ids=...` 한 번).
- 서비스 `application.yml` 은 `spring.jpa.properties.hibernate.query.in_clause_parameter_padding: true` 를 둔다. IN 절 파라미터 수를 2의 거듭제곱으로
  채워(3개 → 4개, 5개 → 8개) 개수마다 다른 SQL 이 생기지 않게 한다 — 그대로면 쿼리 계획 캐시와 PreparedStatement 캐시가 개수만큼 늘어난다 (#8 DB 검토).
- 한 요청에서 행을 수십~수백 개 INSERT 하는 서비스는 `spring.jpa.properties.hibernate.jdbc.batch_size: 50` · `order_inserts: true` · `order_updates: true` 를 둔다.
  아이디를 Snowflake 로 직접 넣으므로 IDENTITY 처럼 배치가 막히지 않는다. 지금은 delivery-service 만 둔다(#255 — 배송비 정책 교체 1회 607 → 19 왕복).
  다른 서비스는 경로를 측정한 뒤 서비스별로 더한다 — 공통 설정으로 두지 않는다.
  **설정값이 아니라 실측 왕복으로 확인한다** — 방언 기본값 때문에 `getJdbcBatchSize()` 가 0 이 아니어도 배치가 하나도 안 나갈 수 있다
  (datasource-proxy 로 `executeBatch` 를 세는 delivery `ShippingPolicyReplaceStatementCountTest`).
  배치가 켜지면 단건 `saveAndFlush` 도 `executeBatch` 로 나가 제약 위반이 `BatchUpdateException` 으로 감싸인다 — 제약 이름 판정은 원인 체인을 따라가야 한다.

### 9-7. Redis

**접속**

- 접속은 Boot 의 `spring.data.redis.*` 자동설정(Lettuce)을 그대로 쓰고 **standalone 만** 지원한다. 접속 프로퍼티나
  `LettuceConnectionFactory` 를 따로 만들지 않는다. 쓰는 서비스는 `redis-core` 를 의존하고 `*BeansConfig` 에서
  `RedisConfigurer` 를 `@Import` 한다 — yml 은 `bootstrap-conventions.md` 의 Redis 절.
- 환경(dev · stage · prod)과 고객사마다 Redis 인스턴스를 나눈다. 한 인스턴스를 나눠 써야 하면 최소한 `database` 번호를 나눈다.
  키에 환경·스토어 접두어가 없으므로 이것이 유일한 경계다.
- Vault 의 Redis 비밀번호에 `$` 를 쓰지 않는다 — Boot 가 `${...}` 를 플레이스홀더로 해석한다.
- 운영 Redis 는 `maxmemory-policy noeviction` 으로 둔다. 메모리가 차면 쓰기가 실패할 뿐, 블랙리스트·멱등 키가 조용히 퇴출되지 않는다.

**기동 가드** — `RedisConfigurer` 가 등록하는 `RedisStartupGuard` 가 접속 팩토리를 만들기 전에 아래를 검사해 기동을 멈춘다.
환경변수(`SPRING_DATA_REDIS_*`)도 Boot 와 같은 규칙으로 읽는다. 위반은 모아서 한 번에 알리고, 메시지에는 프로퍼티·환경변수 이름만 넣는다.

| 기동을 멈추는 조건 | 이유 |
|--------------------|------|
| `password` 가 비었거나 공백, `${REDIS_PASSWORD}` 가 풀리지 않았다 | 로컬 compose 도 `requirepass` 다. 인증 없는 Redis 에 붙지 않는다 |
| `host` 에 `${REDIS_HOST}` 가 풀리지 않고 남았다 | 그대로 두면 기동은 되고 첫 명령에서야 실패한다 |
| `timeout`(명령) · `connect-timeout`(연결)이 없거나 0 이하거나 형식이 틀렸다 | 타임아웃 없는 블로킹 호출 금지 (`architecture-guide.md` §8) |
| `url` 이 설정됐다 | url 이 있으면 Boot 가 host·port·password 를 무시한다 — 비밀번호 검사가 무력해진다 |
| `sentinel` · `cluster` · `masterreplica` 아래 값이 있다 | 다른 모드는 노드·비밀번호를 다른 프로퍼티에서 읽어 위 검사를 비켜 간다 |

**키** — `RedisKey.of(owner, domain, id...)` 로 만든다. 형식 `{owner}:{domain}:{id...}`, 어긋나면 `IllegalArgumentException`.

| 부분 | 규칙 | 예 |
|------|------|----|
| owner | 키의 **수명주기(생성·갱신·삭제)를 소유하는** 서비스. 소문자 kebab `[a-z][a-z0-9-]*` | 아래 표 |
| domain | 그 서비스 안의 용도. 소문자 kebab | `refresh-token`, `cart` |
| id | 하나 이상. ASCII `[A-Za-z0-9._~=+/-]` 1~128자 — glob(`* ? [ ] \`) · `{ }` · 공백 · 제어문자 · 비ASCII 는 거부 | Snowflake · UUID · base64(표준·url) |

| owner | 서비스 |
|-------|--------|
| `auth` · `product` · `order` · `payment` · `delivery` | 같은 이름의 `*-service` |
| `gateway` | `api-gateway` |

- owner 로 서비스별 네임스페이스를 나눠 같은 이름의 키가 부딪히지 않게 한다. 싱글 테넌트(`architecture-guide.md` §9)라 스토어 접두어는 붙이지 않는다.
- **서비스를 가로지르는 키**(auth 가 쓰고 gateway 가 읽는 등) — 다른 서비스는 그 키에 직접 쓰지 않고 owner 의 API 를 거친다.
  키는 양쪽이 의존하는 모듈(예: `security-core`)의 키 함수 하나로만 만든다. 형식이 두 곳에서 갈라지지 않게 한다.
  지금 있는 서비스 횡단 키는 하나다.

  | 키 | 키 함수 | 쓰는 곳 · 읽는 곳 | 값 · TTL |
  |----|---------|-------------------|----------|
  | `auth:revoked-access-token:{jti}` | `security-core` `RevokedAccessTokenKeys.of(jti)` | auth(로그아웃) · api-gateway(요청마다 존재 확인) | 값은 뜻 없음(존재만 본다). TTL `RevokedAccessTokenKeys.ttl(clockSkew)` = access 수명 상한 1시간 + `security.jwt.clock-skew` |
- **서비스 안의 키** — 한 서비스만 읽고 쓰는 키는 그 서비스의 키 함수 하나로 만든다.

  | 키 | 키 함수 | 값 · TTL |
  |----|---------|----------|
  | `auth:refresh-token:{fingerprint}` | auth `RefreshTokenRedisKeys.token` | HASH `{memberId, familyId, rotatedAt?, siblings?}`. TTL `min(refresh 토큰 TTL, family 상한까지 남은 시간)` — HSET 과 같은 Lua 에서 PEXPIRE (`security-guide.md` §2-3) |
  | `auth:refresh-family:{familyId}` | auth `RefreshTokenRedisKeys.family` | STRING 최초 로그인 시각(epoch ms) — 절대 수명(기본 30일)의 기준이라 회전해도 다시 쓰지 않는다(PEXPIRE 만). 정수 문자열이 아니거나 · 무한대 · 지금 + 60초보다 미래면 손상으로 보고 DEL(#143). TTL `min(refresh 토큰 TTL, 상한까지 남은 시간)`, 회전마다 다시 건다. 폐기 = DEL |
  | `auth:login-attempt:{emailHmac}` · `auth:admin-login-attempt:{emailHmac}` · `auth:sign-up-attempt:{emailHmac}` | auth `LoginAttemptRedisKeys.attempt` | STRING 창 안의 시도 수(고객 로그인 · 관리자 로그인 · 회원가입 따로). TTL 로그인 `auth.login-guard.window` · 가입 `auth.login-guard.sign-up.window` — INCR 과 같은 Lua 에서 첫 시도에만 PEXPIRE(이어지는 시도가 연장하지 않는다), TTL 이 없으면 다음 시도에서 다시 건다. 고객 로그인 성공 시 DEL(관리자 로그인 · 가입은 지우지 않는다 — `security-guide.md` §12-2). `emailHmac` 은 정규화 이메일(`Member.canonicalEmail`)의 HMAC-SHA256 앞 16바이트 base64url 22자 — 키 `AUTH_LOGIN_GUARD_HASH_KEY`, 이메일 원문은 키에 없다 (#66) |
- `:` 가 들어가는 값(IPv6 등)은 정규화한 뒤 인코딩해 넣는다(예: IPv6 → 16바이트 hex).
- id 에 원문 토큰·개인정보를 넣지 않는다. 해시(SHA-256 base64url 등)로 넣는다 — `RedisKey.toString()` 이 키 전체를 출력하고, 키는 `MONITOR`·슬로로그에도 남는다.
- 게이트웨이가 넘긴 `X-Client-Subject`(클라이언트 IP 의 HMAC, base64url 22자)는 이미 해시라 id 로 그대로 쓴다. 로그에는 앞 8자만 남긴다 (`security-guide.md` §7-3 하류 헤더 계약).
- `KEYS` · `SCAN` 패턴으로 지우지 않는다. 묶어서 지울 키는 색인 SET(예: 회원별 refresh 토큰 지문)에 모아 두고 그 SET 으로 지운다.

**TTL** (필수)

- 모든 키는 쓰는 **같은 명령**에서 TTL 을 준다 — `set(k, v, Duration)`, `setIfAbsent(k, v, Duration)`.
  `set` 뒤에 `expire` 를 따로 부르지 않는다. 그 사이에 실패하면 영구 키가 남는다.
- 덮어쓸 때도 TTL 을 다시 주거나 `KEEPTTL`(`Expiration.keepTtl()`)을 쓴다. TTL 없는 `SET` 은 기존 TTL 을 지운다.
- 컬렉션(`SADD` · `HSET` 등)은 `MULTI` 나 Lua 로 `EXPIRE` 와 함께 보낸다.
- 영구 키는 만들지 않는다. 예외는 아래 예외 표에 올린다.
- TTL 값은 프로퍼티로 두고 근거에 묶는다.

| 용도 | TTL |
|------|-----|
| refresh 토큰 | refresh 토큰 TTL 과 같게 (`security.jwt.issuance.refresh-token-ttl`) |
| access 토큰 차단 목록(로그아웃) | 토큰의 남은 수명(`exp − now`) + 시계 오차 허용(`security.jwt.clock-skew`) 이상. 로그아웃 시점엔 `exp` 를 모르므로 **수명 상한(`JwtIssuanceProperties.MAX_ACCESS_TOKEN_TTL`, 1시간) + 시계 오차**를 쓴다(`RevokedAccessTokenKeys.ttl`) — `access-token-ttl` 설정값을 쓰면 TTL 을 줄인 배포 직후 옛 긴 토큰보다 차단 항목이 먼저 사라진다 |
| 소셜 로그인 state | 짧게(분 단위). provider · 브라우저 verifier 해시 일치 검증과 삭제를 Lua 로 원자 실행해 한 번만 쓴다. 불일치는 삭제하지 않는다 |
| 로그인 · 가입 시도 계수 | 창 길이(로그인 `auth.login-guard.window` 기본 15분 · 가입 `auth.login-guard.sign-up.window` 기본 10분). 첫 시도부터 재고 이어지는 시도가 연장하지 않는다. 상한 1시간(`LoginAttemptPolicy.MAX_WINDOW`) — 이메일을 바꿔 가며 보내면 키 수 ≈ 처리량 × 창이고, noeviction 이라 메모리가 차면 refresh · 차단 목록 쓰기까지 실패한다 |
| 멱등 키 | 클라이언트 재시도 창보다 길게. 처리 중 마커는 최대 처리 시간보다 길게 |
| 캐시 | 짧게. 원본을 쓸 때 무효화한다 |

**직렬화**

- Boot 가 만드는 `StringRedisTemplate`(서블릿) · `ReactiveStringRedisTemplate`(게이트웨이)만 주입한다. 값이 객체면 JSON 문자열로 바꿔 넣는다.
- Boot 는 `redisTemplate` · `reactiveRedisTemplate`(`<Object, Object>`, JDK 직렬화)도 등록한다. **있지만 주입하지 않는다** —
  클래스가 바뀌면 읽지 못하고, `redis-cli` 로 값을 볼 수 없고, 역직렬화 취약점의 입구가 된다.
- 타입 정보(`@class`)를 값에 싣는 Generic Jackson 직렬화(`GenericJacksonJsonRedisSerializer` · `GenericJackson2JsonRedisSerializer`)도 같은 이유로 쓰지 않는다.
- Spring Cache(`@Cacheable`)는 쓰지 않는다. 기본 설정이 JDK 직렬화 · 무기한 TTL · `{cacheName}::{key}` 키다.
  필요해지면 redis-core 가 접두어 · String/JSON 직렬화 · TTL 을 강제하는 `RedisCacheConfiguration` 을 먼저 제공한다.

**형식 · TTL 예외** — 프레임워크가 만드는 키만 둔다. 새 예외는 이 표에 올리고 리뷰에서 근거를 확인한다.

| 키 | 만드는 곳 | 비고 |
|----|-----------|------|
| `request_rate_limiter.{<routeId>.<id>}.tokens` · `.timestamp` | Spring Cloud Gateway `RedisRateLimiter` (gateway) | 형식은 프레임워크가 정한다(쓰는 버전 5.0.3 의 `RedisRateLimiter.getKeys` 로 확인). TTL 은 Lua 스크립트가 같이 건다. `<routeId>` 자리는 정책 id, `<id>` 는 `<정책>:member:<memberId>` 또는 `<정책>:ip:<HMAC>` 다 — 예: `request_rate_limiter.{order-write.order-write:member:<memberId>}.tokens` (`RedisBucketRateLimiter`, `security-guide.md` §7-3). 개인정보 원문(IP 포함)을 넣지 않는다 |

### 9-8. 스키마 변경 · 운영 DDL (필수)

DB 마이그레이션 도구(Flyway · Liquibase)를 쓰지 않는다. 스키마의 정본은 엔티티이고, 운영 반영은 사람이 한다.

| 환경 | `spring.jpa.hibernate.ddl-auto` | 스키마를 만드는 쪽 |
|------|---------------------------------|--------------------|
| local · dev | `update` | 서비스 기동 시 Hibernate |
| 운영 (공통 `application.yml` 기본값) | `none` | 사용자가 DDL 적용 계정으로 직접 적용한다(앱 계정에는 DDL 권한이 없다 — `service-inventory.md` §4-2). staging 을 두면 그곳에 먼저 적용해 본다 |
| 컨텍스트 테스트 (H2) | `create-drop` | 테스트 컨텍스트 |
| 리포지토리 테스트 (Testcontainers PostgreSQL) | `validate` | DDL 스냅숏을 init script 로 적용 (`bootstrap-conventions.md` 리포지토리 테스트) |

- **엔티티를 추가 · 변경하는 PR 은 본문 `운영 DDL` 절에 운영에 적용할 PostgreSQL DDL 을 그대로 적는다.**
  테이블 · 컬럼 코멘트, 인덱스(`idx_` / `uk_`), 제약(`ck_`)까지 넣는다. 스키마(`auth` · `product` · `orders` · `payment` · `delivery` · `operations`)를 붙여 쓴다.
  아래 틀로 감싼다 — Windows `psql` 은 클라이언트 인코딩 기본값이 CP949 라 그대로 붙여 넣으면 한글 코멘트가 깨지고,
  트랜잭션으로 묶지 않으면 중간에 실패했을 때 일부만 적용된다.

  ```sql
  set client_encoding = 'UTF8';
  begin;
  -- 스냅숏 그대로 (create schema 줄 제외)
  commit;
  ```
- DDL 은 손으로 짓지 않는다. **Hibernate 가 만든 DDL** 을 아래 둘 중 하나로 뽑는다.
  손으로 쓰면 타입(`timestamptz`, `bigint`, `varchar(n)`)이나 인덱스 이름이 dev 와 갈라진다.
  - local · dev 에서 `update` 가 만든 결과 — 예: `pg_dump --schema-only -t product.product_option`
  - 엔티티에서 Hibernate 스키마 생성 스크립트로 만든 결과 — 서비스의 **DDL 스냅숏 테스트**(`*SchemaSnapshotTest`)가
    DB 없이 `build/ddl/<서비스>-schema.sql` 을 만들고, 커밋된 `src/test/resources/ddl/<서비스>-schema.sql` 과 비교한다.
    방언은 PostgreSQL, 네이밍 전략은 Boot 기본값이라 서비스가 `update` 로 만드는 것과 같은 이름이 나온다.
- 엔티티를 바꾸면 **스냅숏을 같은 PR 에서 갱신한다** — 스냅숏 테스트가 실패하며 `build/ddl/...` 을 복사하라고 안내한다.
  PR 본문 운영 DDL 은 스냅숏에서 바뀐 부분을 옮긴다 (새 테이블은 `create`, 기존 테이블 변경은 `alter` 로 고쳐 쓴다).
  스냅숏의 `create schema` 줄은 운영 DDL 로 옮기지 않는다 — 서비스 스키마는 운영 DB 에 이미 있다.
- 스냅숏 테스트는 방언 버전을 운영 PostgreSQL 메이저 버전(17)으로 고정한다(`jakarta.persistence.database-major-version`).
  DB 에 붙지 않으면 Hibernate 는 방언의 최소 지원 버전으로 DDL 을 만들기 때문이다. 운영 버전을 올리면 이 값과 스냅숏을 같이 바꾼다.
- 로컬 DB 가 없으면 **스냅숏이 정본**이다. 검증은 셋으로 나뉜다 — 어느 하나가 나머지를 대신하지 않는다.

  | 무엇을 | 어디서 |
  |--------|--------|
  | 테이블 · 컬럼이 있는가, 타입이 맞는가 | 리포지토리 테스트(Testcontainers)의 `ddl-auto=validate` — 스냅숏을 적용한 PostgreSQL 과 엔티티를 대조한다 |
  | NOT NULL · 길이 · `uk_` · `idx_` · check · 코멘트 | 스냅숏 테스트의 텍스트 비교 — `validate` 는 이것들을 보지 않는다 |
  | PostgreSQL 이 그 DDL 을 받는가 | 리포지토리 테스트가 스냅숏을 init script 로 적용한다 — 받지 못하면 컨테이너 기동에서 실패한다 |

  리포지토리 테스트는 CI 에서 Docker 가 없으면 건너뛰지 않고 실패한다 (`bootstrap-conventions.md` 리포지토리 테스트).
- **enum 값을 추가 · 삭제하면 check 제약도 바꾼다.** Hibernate 는 `@Enumerated(STRING)` 컬럼에
  `check (sale_status in ('ON_SALE','STOP_SELLING'))` 를 만드는데, `update` 는 이미 있는 check 제약을 고치지 않는다.
  운영 DDL 에 drop / add 를 적고 dev 에도 직접 적용한다. 이름 없는 컬럼 check 는 PostgreSQL 이 `<테이블>_<컬럼>_check` 로 이름을 붙인다
  (적용 전 `\d product.product` 로 확인).

  ```sql
  alter table product.product drop constraint product_sale_status_check;
  alter table product.product add constraint product_sale_status_check check (sale_status in ('ON_SALE','STOP_SELLING','PAUSED'));
  ```
- `update` 는 테이블 · 컬럼을 **추가만** 한다. 이름 변경 · 타입 변경 · 삭제 · 기존 행이 있는 컬럼의 `NOT NULL` 추가는 dev 에서도 저절로 되지 않는다.
  이런 변경은 PR 에 dev · 운영 공통 DDL 과 데이터 이관 SQL 을 적고 **dev 에도 직접 적용**한다.
- 운영 적용 순서는 **추가 DDL → 배포 → 정리 DDL(삭제)** 이다. 새 버전이 뜨기 전에 옛 버전이 쓰는 컬럼을 지우지 않는다.
- 운영에 적용했으면 PR 에 "운영 DDL 적용 완료" 코멘트를 남긴다. 적용 전 머지는 괜찮지만, 배포는 적용 뒤에 한다.

## 10. Internal / External Client

- Feign `name` 은 하드코딩하지 않는다: `name = "${feign-client.target-services.product-service:product-service}"`.
  Eureka 등록명은 환경마다 다를 수 있다.
- 같은 대상을 여러 인터페이스가 호출하면 `contextId` 를 반드시 지정한다.
- **internal 클라이언트는 서비스 토큰을 싣는다** (`security-guide.md` §9-5).
  - `@FeignClient(name = ..., contextId = ..., configuration = InternalFeignClientConfiguration.class)` 로 클라이언트마다 붙이고,
    서비스 `*BeansConfig` 에 `InternalTokenIssuerConfigurer` 를 import 한다. 설정이 Feign `Capability` 로 **최종 요청**의 `Authorization` 을 모두 지우고
    서비스 토큰 하나만 싣는다 — 다른 인터셉터가 뒤에 붙어도 남지 않는다.
  - Feign `name` 은 대상의 Eureka 서비스 ID(= 대상의 `spring.application.name`)다. 그대로 서비스 토큰의 `aud` 가 되므로
    `feign-client.target-services.*` 로 바꿀 때도 대상의 서비스 ID 와 같아야 한다 — 다르면 대상이 401 · `SECURITY_007` 로 거부한다.
  - **사용자 `Authorization` 헤더를 internal 로 넘기지 않는다.** `RequestContextHolder` 로 현재 요청의 헤더(사용자 토큰)를 꺼내 Feign 에 싣지 않는다.
    사용자 식별이 필요하면 memberId 를 요청 본문 · 파라미터로 넘긴다.
  - **상관 id 는 MDC 에서만 싣는다** (#284). `InternalServiceTokenCapability` 가 최종 요청의 `X-Correlation-Id` 를 지우고, 이 스레드 MDC 에 검증된 id 가 있을 때만 그 값을 하나 넣는다.
    요청에 실려 있던 값 · 뒤 인터셉터가 넣은 값은 남지 않는다. MDC 가 비어 있으면 헤더를 만들지 않고, 받는 서비스가 새로 만든다. 전역 `RequestInterceptor` 로 붙이지 않는다(위 금지와 같다).
  - **모든 클라이언트에 붙는 설정을 쓰지 않는다** — 전역 `RequestInterceptor` · `Capability` 빈, `@EnableFeignClients(defaultConfiguration = ...)`,
    `spring.cloud.openfeign.client.config.default.request-interceptors`. 외부 API(토스 등)에 사용자 토큰 · 서비스 토큰이 실리거나 그쪽 `Authorization` 을 덮어쓴다.
    외부 클라이언트에는 `InternalFeignClientConfiguration` 을 붙이지 않는다 — 이름이 kebab(`toss-payments`)이면 코드가 막지 못한다.
  - 대상의 401 · `SECURITY_007` 은 요청 내용이 아니라 키 · 설정 문제다. 토큰을 다시 발급해 재시도하지 않는다(재시도로 풀리지 않는다).
- Feign 파라미터가 `LocalDate`·`LocalDateTime` 등이면 `@DateTimeFormat(iso = ...)` 를 붙인다
  (없으면 요청 로케일 형식으로 문자열화되어 받는 쪽이 400 을 준다).
- 타임아웃은 `spring.cloud.openfeign.client.config` 공통 설정으로 관리한다. 기본 read timeout 60초를 그대로 두지 않는다.
- Resilience4j CircuitBreaker 는 `application.yml` 의 `configs.default` + `instances.<대상명>` 으로 관리한다.
  - 인스턴스명: 내부는 대상 서비스 논리명(`product-service`), 외부는 `toss`, `paypal`, `kakao`, `naver`, `tracker`.
  - 서킷 적용과 예외 변환은 `adapter/out/client/support/*InternalResponseSupport` 에서 한다.
    같은 단순 이름의 `@Component` 가 두 패키지에 있으면 기동이 실패하므로 대상(원격 자원) 접두사를 붙인다 —
    예: `ProductInternalResponseSupport`(SKU 조회) · `ShippingFeeInternalResponseSupport`. 한 서비스를 두 컨텍스트가 부르면 자원 이름으로 나눈다.
  - 4xx(`FeignClientException`)는 `ignore-exceptions` 로 제외한다 — 호출한 쪽의 요청 · 설정 문제이거나 업무 거절이다 — 대상 장애가 아니다.
  - `CallNotPermittedException`·`FeignException` 은 도메인 예외(`{DOMAIN}_xxx INTERNAL_SERVICE_UNAVAILABLE`, 503)로 바꾼다.
  - 대상의 4xx 업무 코드(`resultCode`)로 분기해야 하면 support 가 **서킷 밖에서**(서킷 호출을 감싼 `catch`) 응답 본문의 `dataHeader.resultCode` 만 파싱한다.
    서킷 안에서는 `FeignClientException` 을 그대로 두어 `ignore-exceptions` 를 지킨다 — 안에서 다른 예외로 바꾸면 업무 거절(배송할 수 없는 국가 등)이 서킷 실패로 세어져 서킷이 열린다.
    아는 코드만 자기 코드로 바꾸고, 모르는 코드 · 파싱 실패(본문 없음 · JSON 아님) · 401 `SECURITY_007` 은 error 로그를 남기고 503 이다.
    `ErrorDecoder` 는 쓰지 않는다 — 예외 타입이 바뀌면 `ignore-exceptions` 도 같이 바꿔야 한다. 본문은 서비스의 `JsonMapper` 빈(Jackson 3)으로 읽고, 로그에는 status · resultCode 만 남긴다.
    resultCode 는 코드 모양(`[A-Z][A-Z0-9_]*_[0-9]{3}`, 40자 이하)일 때만 그대로 남기고, 아니면 `<unrecognized>` 다 — 원격의 임의 문자열(CR/LF · 긴 값)을 로그에 넣지 않는다.
    성공 봉투가 아닌 응답의 resultCode 도 같다. 판정은 서비스의 `global/support` 한 곳에 둔다(order-service `RemoteResultCodes` — product · delivery 응답 support 가 함께 쓴다, #281).
    예: order-service `ShippingFeeInternalResponseSupport`(#13) — `DELIVERY_001/002/003/011` 만 `ORDER_012~015` 로 번역한다.
    Feign 예외 메시지에는 대상 응답 본문이 들어 있다 — 받는 핸들러는 원인 예외를 throwable 로 로그하지 않는다. Feign `logger-level` 을 `FULL` · `HEADERS` 로 두지 않는다(요청 본문 · 헤더가 로그에 남는다).
- **PG 호출에는 서킷을 걸되, 서킷 오픈을 승인 "실패" 로 다루지 않는다.** 서킷 오픈은 호출 전에 막힌 것이라 "보내지 않음"(`READY` 로 되돌림)이고,
  보냈는지 모르는 타임아웃 · 5xx 는 "결과 미확정"(`UNKNOWN`)이다 (`payment-guide.md` §4-1).
  PG 서킷은 `ignore-exceptions` 대신 `ignore-exception-predicate`(payment `TossCircuitBreakerIgnorePredicate`)로 429 가 아닌 4xx 만 무시한다 —
  두 설정은 OR 로 합쳐지므로 같은 인스턴스에 `ignore-exceptions: FeignClientException` 을 같이 두면 429 가 다시 빠진다.

## 11. Enum Metadata

- 응답에서 enum 은 raw 문자열 대신 `{code, name, description}` metadata 객체로 내린다.
- enum 의 UI 표시명은 `displayName`, 설명은 `description` 필드로 둔다.
- 예외: 화면이 분기에만 쓰고 표시하지 않는 제어 코드(예: 맞춤 제작 입력 형식 `inputType` — `NUMBER` · `TEXT`)는 문자열 + `@Schema(allowableValues)` 로 내릴 수 있다.

```json
{ "orderStatus": { "code": "PREPARING", "name": "상품 준비중", "description": "결제가 완료되어 출고를 준비하고 있습니다." } }
```

- 다국어 스토어(ko/en)에서 표시명은 `Accept-Language` 기준으로 고른다 — 구현 방식은 i18n 이슈에서 확정한다.

## 12. 메시징 (Kafka, #304 · #306)

일어난 사실(결제 승인됨 · 주문 만료됨 · 작업 등록됨)은 outbox → Kafka → 컨슈머로 전한다. 응답을 보고 분기하는 명령은 지금처럼 internal API(Feign)다 (#304 설계 A-1).
모듈 사용법 · 설정 키 · 서비스가 둘 테이블은 [`core/messaging-core/README.md`](../core/messaging-core/README.md) 가 정본이다.

- **발행은 `EventPublisher` 만 쓰고, 업무 트랜잭션 안에서 부른다.** Kafka 로 바로 보내지 않는다 — outbox 행이 업무와 같은 커밋으로 저장되고 릴레이가 보낸다.
  트랜잭션 밖에서 부르면 `NO_TRANSACTION`, 읽기 전용 트랜잭션이면 `READ_ONLY_TRANSACTION` 이다. 롤백되면 이벤트가 나가지 않고, 커밋되면 한 번 이상 나간다.
- **소비는 멱등이다.** `IdempotentEventProcessor.process(group, envelope, handler)` 가 처리 기록(`processed_event`)과 업무 처리를 한 트랜잭션으로 묶는다.
  원격 호출을 섞어 트랜잭션을 나누는 컨슈머는 `isProcessed` 로 거르고 상태를 쓰는 트랜잭션 안에서 `record` 한다. 상태 전이 멱등이 2차 방어다.
- 리스너는 `adapter/in/event/*EventListener` 이고 UseCase 만 부른다. 값은 문자열로 받아 `EventEnvelopeCodec.decode` 로 봉투를 읽고,
  `requireVersion` 으로 모르는 새 버전을 거른다.
- **이름은 `TopicNames` 로 만든다** — 토픽 `<ns>.<생산 서비스>.<aggregate>-<event>`, DLT `<topic>.dlt`, 그룹 `<ns>.<소비 서비스>.<목적>`.
  namespace(`commerce-foundry.<env>`)는 설정값이다. 코드에 namespace · 스토어 이름을 쓰지 않는다. **앱은 토픽을 만들지 않는다**(`spring.kafka.admin.auto-create=false`).
- **페이로드에는 아이디 · 금액(long)+통화 · 상태 · 사유 코드 · 시각 · SKU · 수량만** 싣는다. 이름 · 연락처 · 주소 · 이메일 · 카드 정보 · PG 키(`paymentKey`) · 오류 원문은
  싣지 않는다 — Kafka UI 로 본문이 열람되고 7일 보존된다. 세부는 컨슈머가 internal API 로 읽는다. 필드는 추가만 하고, 삭제 · 의미 변경은 `schemaVersion+1` 과 `.v2` 토픽이다.
  개인정보로 보이는 필드 이름은 `EventEnvelopeCodec` 이 `FORBIDDEN_FIELD` 로 막는다(실수를 잡는 장치이지 허용 목록이 아니다).
- **리스너 토픽 · 그룹은 `TopicNames` 로 만든 이름만 쓴다.** 켜져 있으면 namespace 로 시작하지 않는 토픽 · 그룹, 토픽 패턴 구독은 기동 실패다. `autoStartup` 은 적지 않는다.
- **이벤트는 진위가 확인되지 않은 알림이다.** 돈 · 재고 상태를 바꾸는 컨슈머는 원본 서비스 internal API 로 재조회해 확정한다(웹훅과 같은 원칙).
  - 예외: product-service 의 주문 만료 · 취소 → 선점 해제 컨슈머는 재조회하지 않는다 — RESERVED 를 푸는 일만 해 효과가 TTL 만료와 같고 CONFIRMED 는 건드리지 않아,
    위조 이벤트의 최대 피해가 "결제 대기 재고가 TTL 보다 일찍 풀림"(확정은 재선점으로 흡수)이다(`order-flow-guide.md` §3-5). SASL · ACL · 서명은 #308 전에 다시 본다.
    새 예외는 이 근거(효과 상한 · 되돌릴 수 있음)를 문서에 적고 사람이 승인한다.
- **재시도 · DLT** — 일시 오류는 컨슈머 안 지수 백오프(기본 5회) 뒤 `<topic>.dlt`. 다시 해도 같은 실패는 `NonRetryableEventException` 으로 던져 바로 DLT 로 보낸다.
  업무상 정상적인 거절(금액 불일치 등)은 예외가 아니라 결과 상태 · 보상 이벤트로 처리한다. `@RetryableTopic` 은 쓰지 않는다.
- 로그 · 예외 · outbox 실패 코드 · DLT 헤더에 봉투 · 페이로드 · 예외 메시지를 싣지 않는다 — eventId · 이벤트 종류 · 예외 타입만 (§8 과 같은 기준).
- 테스트 — 서비스 단위 테스트는 testFixtures 의 `InMemoryOutboxStore` · `InMemoryProcessedEventStore`(트랜잭션 커밋 · 롤백을 흉내 낸다)를,
  흐름 테스트는 내장 Kafka(`spring-kafka-test` `@EmbeddedKafka`, KRaft — Docker 없이 로컬에서도 돈다)를 쓴다 — 예: operations `JobWorkerFlowTest`,
  product `OrderStockReleaseFlowTest`. PostgreSQL 이 필요한 것(inbox 유니크 키 · 잠금)만 Testcontainers(`@EnabledWithDockerOrOnCi`)로 따로 둔다.
  실제 브로커 동작이 필요하면 `MessagingKafkaContainers`(Testcontainers)를 쓴다.

#66 회원가입 IP 카운터는 `auth:sign-up-ip-attempt:{clientSubject}`다. clientSubject는 게이트웨이가 만든 22자 base64url HMAC 값으로,
이메일 카운터와 별개다. 원문 IP·이메일은 키에 넣지 않는다. 대소문자를 보존하며 창은 기본 10분이고 첫 기록과 TTL을 같은 Lua 실행에서 설정한다.
