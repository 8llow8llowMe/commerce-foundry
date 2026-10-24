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
| 관리자 감사 | `AdminAuditPort` — 행위별 메서드(`adminLoginSucceeded` 등). Facade 는 직접 주입하지 않고 `AdminAuditProcessor` 를 부른다(H11) | `*AuditLogAdapter` (`adapter/out/log/`, `security-guide.md` §11-4) |

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
- **로그에 남기지 않는 것**: PG 시크릿 키, 결제키 전체, 카드 정보, 고객 이름·전화번호·주소, JWT 원문.
  식별이 필요하면 마스킹하거나 내부 ID 만 남긴다. 토큰은 `jti` 와 memberId 만 남긴다 — refresh 토큰도 원문 대신 핑거프린트다.
- **DB 제약 위반 로그에 행 값을 남기지 않는다 (#173).** Hibernate 는 예외를 바꾸기 전에 `SQLException` 메시지를 WARN(`org.hibernate.orm.jdbc.error`)으로
  남기고, pgjdbc 기본값은 그 메시지에 행 값(DETAIL `Failing row contains (...)` · `Key (email)=(...)`)을 싣는다 — 어댑터가 나중에 원인을 떼도 이미 늦다.
  - 1차 방어는 드라이버 옵션이다 — 서비스 `application.yml` 의 `spring.datasource.hikari.data-source-properties.logServerErrorDetail: false`
    (`DB_URL` 은 Vault 값이라 URL 파라미터로 넣지 않는다 — 거꾸로 `DB_URL` · 프로파일 yml 에 이 속성을 넣거나 덮지 않는다. URL 값이 이기고 테스트가 못 잡는다). 제약 이름은 메시지 본문에 남아 `getConstraintName()` 기반 번역은 그대로다 —
    Hibernate 가 영문 본문(`constraint "…"`)에서 이름을 꺼내므로 운영 PostgreSQL `lc_messages` 는 C/영문이어야 한다(아니면 이름이 null 이 되어 409 대신 500).
    `*ApplicationTests` 가 커넥션 풀의 속성을, auth `ShippingAddressPersistenceAdapterTest` 가 PostgreSQL 의 로그 · 예외 메시지를 본다.
  - 2차 방어(새 어댑터에 권장)는 예상 밖 위반을 다시 던질 때 원인(cause)도, 원래 예외의 메시지(`[SQLException 메시지]` 가 들어 있다)도 싣지 않고
    제약 이름만 싣는 것이다. Hibernate WARN 줄은 막지 못하고(1차 방어만 막는다), 그 뒤 예외 메시지 · 스택(전역 핸들러의 500 로그 등)으로 퍼지는 것만 줄인다.
    지금 어댑터들은 원인을 그대로 던지며, 1차 방어로 메시지에 행 값은 없다.
  - 로깅 레벨 · `hibernate.jdbc.log.errors` 로 끄지 않는다 — 다른 SQL 오류의 진단까지 사라진다.
  - dev · 운영에서 `org.hibernate.orm.jdbc.bind` · `org.postgresql` TRACE 와 show-sql 바인드 출력을 켜지 않는다 — 이 옵션과 상관없이 바인드 값 · DETAIL 이 남는다.
- 사용자 노출 메시지와 내부 로그 메시지는 분리한다.

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
- 컬렉션 원소는 `@NotNull` 과 값 제약을 쌍으로 건다 — `List<@Positive Long>` 만 걸면 `[null]` 이 통과한다.

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
  다른 서비스 테이블을 가리키면 `(외부 FK: product-service product.id)` 로 적는다 — DB 제약은 걸 수 없다.
- 상태가 있는 엔티티(주문·결제·재고)는 **낙관적 락(`@Version`)** 을 기본으로 검토한다.
  - 버전은 **`Long`(wrapper)** 으로 두고 도메인 모델도 `Long version` 을 들고 다닌다. 쓰기는 `domain → mapper.toEntity → repository.save` 다 —
    새 행은 버전이 null 이라 Spring Data 가 `persist`(INSERT)로, 읽어 온 행은 버전이 있어 `merge`(버전 비교 · `UPDATE ... where version = ?`)로 간다.
  - 상태를 바꾼 저장은 `saveAndFlush` 로 바로 내보내고, 어댑터가 `ObjectOptimisticLockingFailureException` 을 도메인 409 로 바꾼다 —
    커밋 시점까지 미루면 트랜잭션 밖에서 터져 도메인 예외로 바꿀 수 없다. 유니크 키 위반(`DataIntegrityViolationException`)도 같은 자리에서 바꾼다.
  - 도메인은 생성 시각을 들고 다니지 않는다. `created_at` 이 `updatable = false` 라 merge 가 덮어쓰지 않는다.
  - 상태 저장(버전 검사)을 **먼저** 하고 그 결과에 딸린 쓰기(재고 복원 등)를 뒤에 한다 — 동시 요청에서 진 쪽이 딸린 쓰기까지 가지 않는다.
  - 예: product-service `StockReservationEntity` · `StockReservationRepositoryAdapter` (#10).
- **비관적 잠금(`@Lock(PESSIMISTIC_WRITE)`)은 상태를 바꾸지 않는 쓰기가 같은 부모 행 기준으로 합계 · 상한을 판정할 때만 쓴다** — 상태 전이가 없으면
  `@Version` 이 경합을 잡지 못하고, 각자 읽은 합으로 함께 상한을 넘긴다. 정적 JPQL(`select ... where id = :id`)에 `@Lock` 을 달고 이름은 `findByIdForUpdate` 로 둔다
  (네이티브 `for update` 금지 — §9-5). PostgreSQL 에서는 `for no key update`(Hibernate 7) 다 — 같은 잠금 · 그 행의 UPDATE 와만 충돌하고,
  FK 검사의 KEY SHARE 는 막지 않는다. 잠금 순서는 다른 경로와 같은 순서(부모 행 → 자식 · 재고 행 아이디 오름차순)로 맞춰 교착을 막는다.
  - **잠금 대기 상한을 둔다** — `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = ...))`. 값은 상수로 두고 호출자 Feign read timeout 보다
    짧게 잡는다(길면 호출자가 먼저 끊고 재시도해 같은 요청이 또 줄을 선다). 넘으면 어댑터가 `PessimisticLockingFailureException`
    (`CannotAcquireLockException` 포함, PostgreSQL SQLState `55P03`)을 **재시도 가능한 도메인 409** 로 바꾼다.
  - 예: product-service 결제 후 재고 복원이 선점 헤더를 잠근다 (`StockReservationRepository#findByIdForUpdate` · 상한 2000ms · 초과 `PRODUCT_031`,
    #85 — `order-flow-guide.md` §3-2).
- **아이디(Snowflake · 다른 테이블의 PK)를 직접 넣고 `@Version` 이 없는 엔티티**는 `Persistable` 을 구현해 `isNew()` 를 `getCreatedAt() == null`
  ("감사 시각이 아직 없다") 로 둔다. 기본 `save` 는 아이디가 있으면 merge 로 가서 행마다 SELECT 를 한 번 더 보내고, PK 를 업무 키로 쓰는 행(`stock.sku_id`)은
  동시 첫 INSERT 가 먼저 커밋된 행을 조건 없이 덮어쓴다. persist 면 PK 충돌로 실패한다.
  예: auth `MemberAgreementEntity`, product `StockEntity` · `StockReservationItemEntity` · `StockAdjustmentHistoryEntity` · `StockRestorationEntity` · `StockRestorationItemEntity` · `CategoryEntity`, delivery `ShippingPolicyHistoryEntity` · `ShippingPolicyHistoryAreaEntity`.
  - Snowflake 를 직접 넣는 Persistable 엔티티의 새 행 저장 포트 메서드는 `insert` 다 — persist 의미(아이디 충돌은 PK 위반)를 이름에 드러낸다 (예: `CategoryRepositoryPort.insert`, #9).
  - append-only 이력도 Persistable 이면 포트 메서드는 `insert` 다 (예: delivery `ShippingPolicyHistoryRepositoryPort.insert`, #69).
    product 재고 조정 이력의 `append` 는 이 규칙 전에 만든 이름이다.
- 유니크 키 위반 · 버전 충돌을 도메인 오류(409)로 바꿔야 하는 저장은 어댑터에서 `saveAndFlush` 로 바로 내보내고 그 자리에서 바꾼다 —
  커밋 때 터지면 예외가 Facade 밖(트랜잭션 프록시)에서 나 바꿀 자리가 없다. 제약은 예외 메시지가 아니라 Hibernate 가 준 제약 이름으로 가린다 (예: auth `MemberRepositoryAdapter`).

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
- 조건부 UPDATE 는 `@Modifying(flushAutomatically = true, clearAutomatically = true)` 로 둔다 — 앞서 쌓인 쓰기를 먼저 내보내고, 뒤이은 조회가 옛 값을 읽지 않게 한다.
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
  | `auth:login-attempt:{emailHmac}` · `auth:sign-up-attempt:{emailHmac}` | auth `LoginAttemptRedisKeys.attempt` | STRING 창 안의 시도 수(로그인 · 회원가입 따로). TTL `auth.login-guard.window` — INCR 과 같은 Lua 에서 첫 시도에만 PEXPIRE(이어지는 시도가 연장하지 않는다), TTL 이 없으면 다음 시도에서 다시 건다. 성공 시 DEL. `emailHmac` 은 정규화 이메일(`Member.canonicalEmail`)의 HMAC-SHA256 앞 16바이트 base64url 22자 — 키 `AUTH_LOGIN_GUARD_HASH_KEY`, 이메일 원문은 키에 없다 (#66) |
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
| 소셜 로그인 state | 짧게(분 단위). 꺼낼 때 `GETDEL` 로 한 번만 쓴다 |
| 로그인 · 가입 시도 계수 | 창 길이(`auth.login-guard.window`, 기본 15분). 첫 시도부터 재고 이어지는 시도가 연장하지 않는다. 상한 1시간(`LoginAttemptPolicy.MAX_WINDOW`) — 이메일을 바꿔 가며 보내면 키 수 ≈ 처리량 × 창이고, noeviction 이라 메모리가 차면 refresh · 차단 목록 쓰기까지 실패한다 |
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
| 운영 (공통 `application.yml` 기본값) | `none` | 사용자가 DDL 을 직접 적용한다 |
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
    resultCode 는 코드 모양(`[A-Z][A-Z0-9_]*_[0-9]{3}`)일 때만 그대로 남기고, 아니면 `<unrecognized>` 다 — 원격의 임의 문자열(CR/LF · 긴 값)을 로그에 넣지 않는다.
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
