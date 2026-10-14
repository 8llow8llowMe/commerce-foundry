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
| UseCase | `*WebUseCase` · `*AdminUseCase` · `*InternalUseCase` |
| Facade | `*WebFacade` · `*AdminFacade` · `*InternalFacade` |
| Processor / Presenter | `*Processor` / `*Presenter` (어드민 응답은 `*AdminPresenter`) |
| Feign interface | `*Client` |
| 조회 결과 / 조건 | `*QueryResult` / `*Criteria` · `*Query` |

### 5-1. Out port 이름 — 책임으로 짓는다

| 카테고리 | Port | Adapter |
|----------|------|---------|
| 서비스 간 읽기 / 쓰기 | `*QueryPort` / `*CommandPort` | `*ClientAdapter` (`adapter/out/client/`) |
| JPA 영속성 | `*RepositoryPort` | `*RepositoryAdapter` (`adapter/out/persistence/`) |
| 인프라 특화 | 도메인 의미 그대로 — `PaymentGatewayPort`, `StockReservationPort`, `ShipmentTrackingPort`, `ImageStoragePort` | |

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
  식별이 필요하면 마스킹하거나 내부 ID 만 남긴다.
- 사용자 노출 메시지와 내부 로그 메시지는 분리한다.

### 8-1. 예외 패턴 (필수)

공통 `BadRequestException` 같은 것을 쓰지 않는다. 도메인마다 3종 세트를 둔다.

| 파일 | 위치 | 내용 |
|------|------|------|
| `{Domain}ErrorCode` | `application/exception/` | enum — `code`, `message`, `HttpStatus` 3 필드 |
| `{Domain}Exception` | `application/exception/` | `extends RuntimeException`, `super(errorCode.getMessage())` |
| `{Domain}ExceptionHandler` | `adapter/in/web/exception/` | `@RestControllerAdvice(basePackages = "...domainlayer")`, `Response.fail()` 변환 |

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
- 한 서비스에 advice 가 둘 이상이면 좁은 범위 advice 에 `@Order` 를 붙여 우선순위를 확정한다.

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

### 9-4. 인덱스 명명

- 일반 `idx_{table}_{col1}_{col2}`, 유니크 `uk_{table}_{col1}_{col2}`. snake_case, **모든 컬럼을 이름에 담는다.**
- PostgreSQL 식별자 상한은 **63자**다. 넘으면 의미를 해치지 않는 범위에서 축약한다.

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
- `@Param` 은 쓰지 않는다 (`-parameters` 가 켜져 있다).
- **커스텀 구현과 JPQL 은 컴파일로 검증되지 않는다.** `@DataJpaTest` 슬라이스 테스트로 실제 스키마에 질의한다.

### 9-6. N+1 금지 (필수)

- 루프·스트림 안에서 `Port.` / `Repository.` / `Client.` 단건 호출을 하지 않는다.
- DB 반복 → `in` 절 벌크 조회. 원격(Feign) 반복 → 대상 서비스에 **벌크 내부 엔드포인트**를 연다
  (예: 장바구니 상품 20개 가격 조회 → `GET /internal/v1/skus?ids=...` 한 번).

## 10. Internal / External Client

- Feign `name` 은 하드코딩하지 않는다: `name = "${feign-client.target-services.product-service:product-service}"`.
  Eureka 등록명은 환경마다 다를 수 있다.
- 같은 대상을 여러 인터페이스가 호출하면 `contextId` 를 반드시 지정한다.
- Feign 파라미터가 `LocalDate`·`LocalDateTime` 등이면 `@DateTimeFormat(iso = ...)` 를 붙인다
  (없으면 요청 로케일 형식으로 문자열화되어 받는 쪽이 400 을 준다).
- 타임아웃은 `spring.cloud.openfeign.client.config` 공통 설정으로 관리한다. 기본 read timeout 60초를 그대로 두지 않는다.
- Resilience4j CircuitBreaker 는 `application.yml` 의 `configs.default` + `instances.<대상명>` 으로 관리한다.
  - 인스턴스명: 내부는 대상 서비스 논리명(`product-service`), 외부는 `toss`, `paypal`, `kakao`, `naver`, `tracker`.
  - 서킷 적용과 예외 변환은 `adapter/out/client/support/*InternalResponseSupport` 에서 한다.
    같은 단순 이름의 `@Component` 가 두 패키지에 있으면 기동이 실패하므로 컨텍스트 접두사를 붙인다.
  - 4xx(`FeignClientException`)는 `ignore-exceptions` 로 제외한다 — 호출한 쪽의 요청 문제다.
  - `CallNotPermittedException`·`FeignException` 은 도메인 예외(`{DOMAIN}_xxx INTERNAL_SERVICE_UNAVAILABLE`, 503)로 바꾼다.
- **PG 호출에는 서킷을 걸되, 승인(confirm) 요청은 서킷 오픈 시 "실패" 가 아니라 "결과 미확정" 으로 다룬다**
  (`payment-guide.md` §4).

## 11. Enum Metadata

- 응답에서 enum 은 raw 문자열 대신 `{code, name, description}` metadata 객체로 내린다.
- enum 의 UI 표시명은 `displayName`, 설명은 `description` 필드로 둔다.

```json
{ "orderStatus": { "code": "PREPARING", "name": "상품 준비중", "description": "결제가 완료되어 출고를 준비하고 있습니다." } }
```

- 다국어 스토어(ko/en)에서 표시명은 `Accept-Language` 기준으로 고른다 — 구현 방식은 i18n 이슈에서 확정한다.
