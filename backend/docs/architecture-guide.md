# Backend Architecture Guide

## 1. 목표 구조

- 백엔드는 **Spring Cloud MSA + Hexagonal Architecture** 를 기준으로 설계한다.
- 기술 기준: Java 21 · Spring Boot 4.0 · Spring Cloud 2025.1 · Gradle 멀티모듈 · PostgreSQL · Redis
  (정확한 버전은 `backend/build.gradle` 이 정본이다. Boot 4 라 Jackson 은 3 — 패키지가 `tools.jackson` 이다).
- **commerce-foundry 는 재사용 가능한 쇼핑몰 엔진이다.** 첫 고객사는 MOKOYURI(생활한복)지만
  특정 브랜드의 규칙을 공통 코드에 고정하지 않는다 (§9).

```text
backend/
├── core/           (라이브러리 — jar, bootJar off)
│   ├── common-core          Response 래퍼, 오류 봉투, 검증 유틸, Swagger 공통
│   ├── persistence-core     JPA / QueryDSL / Snowflake ID / BaseEntity / Slice·PageResponse
│   ├── redis-core           Redis 연결 (Boot 자동설정 + 기동 가드) · 키 규칙
│   ├── security-core        JWT 발급(auth) · 검증(서비스 · 게이트웨이) · 서블릿 Resource Server · 인증 주체 · 보안 오류 코드
│   ├── storage-core         오브젝트 스토리지 (상품 이미지 업로드 / 삭제 / 키 생성 / 검증)
│   └── shared-commerce      서비스를 가로지르는 커머스 enum·값 (CurrencyCode, Money, SalesChannel 등)
├── cloud/          (실행 모듈 — bootJar on)
│   ├── api-gateway          Spring Cloud Gateway — 라우팅, JWT 검증, admin 경로 보호
│   └── service-discovery    Eureka 서버
└── service/        (도메인 서비스 — bootJar on)
    ├── auth-service         회원 · 인증(일반/소셜) · 관리자 계정 · 배송지 주소록
    ├── product-service      카테고리 · 상품 · 옵션(SKU) · 재고 · 상품 이미지 · 다국어 상품정보
    ├── order-service        장바구니 · 주문 · 주문 금액 계산 · 클레임(취소/반품/교환)
    ├── payment-service      PG 결제 승인/취소 · 웹훅 · 대사 (토스페이먼츠: 카드·카카오페이·네이버페이·PayPal)
    └── delivery-service     배송비 정책(국내/해외) · 송장 · 배송 상태 추적
```

후순위(필요해지면 추가): `batch-service`(자동 구매확정·결제 대사 배치·카페24 이관),
`notification-service`(알림톡·메일), 쿠폰/프로모션 컨텍스트.

### 1-1. core 모듈 포함 기준

| 모듈 | 넣는 것 | 넣지 않는 것 |
|------|---------|--------------|
| `common-core` | 도메인 비의존 범용 인프라 | 특정 서비스의 도메인 개념 |
| `persistence-core` | DB 접근 공통 설정·유틸 | 엔티티 (엔티티는 각 서비스 소유) |
| `shared-commerce` | **두 서비스 이상이 같은 뜻으로 써야 하는** enum·값 객체 | 한 서비스만 쓰는 enum |

`shared-commerce` 가 필요한 이유: 같은 enum 을 서비스마다 복사하면 뜻이 갈라진다.
예를 들어 `CurrencyCode` 의 소수 자릿수를 order 와 payment 가 다르게 알면 USD 주문이 100배로 결제된다.

## 2. 기본 패키지 구조

루트 패키지는 `com.commercefoundry` 이고, 서비스 내부는 `domainlayer` 와 `global` 로만 나눈다.

```text
com.commercefoundry
  |- domainlayer/<context>
  |  |- adapter
  |  |  |- in/web               고객(스토어프론트) API — /api/v1/**
  |  |  |  |- controller
  |  |  |  |- dto/{request,response,item}
  |  |  |  |- dto/validation    그 진입 요청 DTO 전용 커스텀 Bean Validation 제약 + Validator (다른 진입과 공유하지 않는다, 필요할 때만)
  |  |  |  |- exception         (*ExceptionHandler)
  |  |  |  \- presenter
  |  |  |- in/admin             어드민 콘솔 API — /api/v1/admin/** (ADMIN 전용)
  |  |  |  |- controller
  |  |  |  |- dto/{request,response,item}
  |  |  |  \- presenter
  |  |  |- in/internal          서비스 간 전용 — /internal/v1/** (게이트웨이가 라우팅하지 않는다)
  |  |  |  |- controller
  |  |  |  |- dto/{request,response,item}
  |  |  |  \- presenter
  |  |  |- in/support           진입 adapter 들이 같이 쓰는 변환 함수 (예: Money → MoneyItem). DTO 는 두지 않는다
  |  |  |- in/webhook           외부 시스템 콜백 — PG 웹훅 등 (payment-service)
  |  |  |- in/scheduler         시각이 부르는 진입점 (@Scheduled — 재고 선점 만료, 미결제 주문 정리 등)
  |  |  \- out
  |  |     |- persistence
  |  |     |  |- entity
  |  |     |  |- repository
  |  |     |  |  \- custom      (*CustomRepository + Impl — QueryDSL 동적 쿼리·조인)
  |  |     |  \- *RepositoryAdapter
  |  |     \- client
  |  |        |- feign          (내부 서비스 Feign *Client)
  |  |        |- pg             (PG 연동 — toss / paypal, payment-service)
  |  |        \- support        (InternalResponseSupport — 서킷·예외 변환)
  |  |- application
  |  |  |- command
  |  |  |- exception            (*ErrorCode, *Exception, *ValidationMessage)
  |  |  |- info
  |  |  |- mapper
  |  |  |- model
  |  |  |- port/in              (*WebUseCase, *AdminUseCase, *InternalUseCase)
  |  |  |- port/out             (+ query/*QueryResult)
  |  |  \- service
  |  |     |- *WebFacade / *AdminFacade / *InternalFacade
  |  |     \- processor
  |  \- domain
  |     \- model
  \- global
     |- config                  (*BeansConfig, *PropertiesConfig, *FeaturesConfig, *SwaggerConfig)
     \- properties
```

- 예외 3종 세트 구성은 `coding-conventions.md` §8 을 따른다.
- **고객 · 어드민 · 서비스 간 API 는 진입 패키지(`in/web` / `in/admin` / `in/internal`)부터 나누고 DTO 를 공유하지 않는다.**
  같은 상품이라도 고객 응답에는 원가·재고 수량·내부 메모가 없고, 어드민 응답에는 있다.
  서비스 간 SKU 조회는 숨긴 상품의 SKU 행과 `purchasable` 을 내리는데, 이것이 고객 응답에 나가면 안 된다.
  DTO 를 공유하면 언젠가 한쪽 필드가 다른 응답으로 샌다.
- Processor·Port·Domain 은 고객/어드민/서비스 간이 **공유한다.** 나누는 것은 진입·응답 모양까지다.

## 3. 계층 책임

```text
Controller → UseCase(port/in) → Facade → Processor → Port(out) / Adapter
Info → Presenter → Response
```

### Controller

- `*WebUseCase` / `*AdminUseCase` / `*InternalUseCase` 만 호출한다.
- 요청 바인딩, 인증 주체 해석, 응답 래핑까지만 담당한다.
- 응답은 `ResponseEntity<Response<T>>` 를 사용한다.

### UseCase (port/in)

- 진입점에서 필요한 유스케이스 계약을 정의한다. Controller 와 1:1 또는 매우 가까운 단위로 맞춘다.
- 이름: `*WebUseCase`(고객) · `*AdminUseCase`(어드민) · `*InternalUseCase`(서비스 간)

### Facade

- 유스케이스의 메인 오케스트레이터다. 여러 Processor 와 Presenter 를 조합한다.
- 읽기는 `@Transactional(readOnly = true)`, 쓰기는 `@Transactional` 을 기본으로 검토한다.
- **예외: 유스케이스에 외부 I/O(PG 승인·소셜 로그인·택배사 조회·다른 서비스 Feign 호출)가 섞이면
  트랜잭션을 Facade 에 걸지 않는다.** DB 커넥션을 잡은 채 원격 응답을 기다리게 되고, 원격 호출이
  성공한 뒤 롤백되면 "PG 는 승인했는데 우리 DB 에는 없는" 상태가 생긴다. 외부 호출을 트랜잭션 밖에
  두고 DB 구간만 Processor 단위로 `@Transactional` 을 건다. **왜 좁혔는지 메서드 주석으로 남긴다.**
- **의도적으로 느린 CPU 작업(비밀번호 해시 — BCrypt)** 도 같은 예외다. 트랜잭션 안에서 하면 해시 시간만큼 DB 커넥션을 쥔다.
  해시는 트랜잭션 밖에서 만들고 저장 구간만 Processor 에 `@Transactional` 을 건다 (예: auth `MemberWebFacade#signUp`).

### Processor

- 실제 애플리케이션 로직을 처리한다. `Info` / domain model / application model 을 반환한다.
- **Response DTO 를 직접 만들지 않는다.**
- Port 호출, 도메인 조합, 검증, ID 생성 책임을 가진다.

### Presenter

- `Info → Response`, `Info → Item` 변환만 담당한다. **식별자 `long → String` 변환 경계다.**
- 금액 `Money → MoneyItem`, enum → metadata(`toMetadata()`) 변환도 Presenter 경계다. Processor · Info 는 `Money` · enum 그대로 둔다.
- Processor 가 돌려준 domain model 을 조합·가공 없이 그대로 응답으로 바꿀 때는 Info 를 두지 않고 Presenter 가 domain model 을 받는다 (예: delivery-service `ShippingFeeWebPresenter`, #11).

### Port / Adapter

- `application/port/out` 은 외부 시스템에 대한 계약만 노출한다.
- `adapter/out/*` 는 JPA, Redis, PG, 택배사, 내부 서비스 호출의 세부를 숨긴다.
- `application` 은 `adapter` 구현 타입에 의존하지 않는다. 단 아래 셋은 **문서화된 예외**다.
  - `port/in`(`*WebUseCase`·`*AdminUseCase`·`*InternalUseCase`)의 반환 타입은 `adapter/in/*/dto` 의 Response DTO 를 쓴다.
  - Facade 는 Presenter 를 주입받아 조합한다 (Facade 의 책임 자체가 조합이다).
  - `application/mapper` 의 MapStruct 매퍼는 Entity ↔ Domain 변환이므로 Entity 를 참조한다.
- **out-adapter 타입(PG 응답 DTO, Feign 래퍼)이 application 으로 새는 것은 금지**한다.
  out-port 반환에는 `QueryResult` 또는 domain model 을 쓰고, `Info` 를 노출하지 않는다.

### 컨텍스트 간 의존 규칙 (같은 서비스 안)

한 서비스에 컨텍스트가 여럿이면(product-service 의 `category` · `product`, order-service 의 `catalog` · `cart`) 다른 컨텍스트에서 쓸 수 있는 것은 **둘뿐이다** (허용 목록).

1. **그 컨텍스트의 Processor.** Facade 만 주입한다 — Processor 끼리는 컨텍스트를 넘어 주입하지 않는다.
2. **그 Processor 가 돌려준 domain model.** 읽기 전용이다 — Facade 가 자기 컨텍스트 Processor 의 인자로 넘길 수 있다.
3. **그 Processor 가 던지는 예외(`application.exception`).** 부른 쪽 컨텍스트의 `adapter/in/*/exception` 의 `*ExceptionHandler` 만 받아 번역한다
   (Processor 를 부르면 그 예외는 반드시 넘어온다). 그 밖의 계층은 다른 컨텍스트 예외를 쓰지 않는다.

- 그 밖(UseCase · Facade · Presenter · Info · Port · Adapter · Mapper · Entity · Repository · Q 타입)은 쓰지 않는다.
  다른 컨텍스트의 포트 · 리포지토리를 직접 부르면 그 컨텍스트의 조회 규칙(숨김 · 정렬 · 캐시)을 비켜 간다.
- domain 은 다른 컨텍스트 domain 을 필드로 갖지 않고 아이디로만 가리킨다 (예: `Product.categoryId`).
- 의존은 한 방향으로만 둔다 (지금 product → category, cart → catalog). 역방향이 필요해지면 컨텍스트 경계를 다시 정한다.
- 트랜잭션은 호출한 Facade 가 건다.
- 숨김 같은 조회 규칙은 그 컨텍스트가 정한다 — 다른 컨텍스트는 용도별 Processor 메서드만 부른다.
  예: `ProductWebFacade` 는 `CategoryProcessor.subtreeIdsOf(categoryId)`(목록 필터 — 숨김과 무관) · `visiblePathOf(categoryId)`(상세 경로 — 숨김 제외)만 부른다.
- 여러 컨텍스트가 같은 원격 조회를 쓰면 그 조회를 공용 컨텍스트 하나로 뽑는다 — 원격 계약 파싱을 두 벌 두지 않는다.
  예: order-service 의 `cart`(그리고 #13 의 `order`)가 `catalog`(`CatalogSkuProcessor` · `CatalogSku`)를 쓴다. `catalog` 는 SKU 조회(Feign · 서킷 · 응답 변환)만 하고,
  담기 가능 판정 · 수량 상한 같은 규칙은 부른 컨텍스트의 Processor 한 곳(`CartProcessor.rejectionOf` · `maxQuantityOf`)에 두고, `CartWebFacade` 는 그 결과를 오류 코드로 바꾸기만 한다.
- 다른 컨텍스트의 예외는 호출한 컨텍스트의 `*ExceptionHandler` 가 자기 코드로 번역한다 — advice 범위가 컨트롤러 패키지(`basePackages`)라서다.
  예: `CatalogException`(`CATALOG_001`, 503)은 advice 를 두지 않고 `CartExceptionHandler` 가 `CART_008`(503)로 번역한다. API 계약은 부른 컨텍스트의 코드다.
- 다른 서비스의 데이터는 지금처럼 internal API(Feign)로만 받는다 (§7). 다른 서비스 스키마를 직접 조회하지 않는다.

### 기계로 보는 것 / 사람이 보는 것 (#77)

계층 의존은 **빌드가 본다.** 서비스의 `<Svc>ArchitectureTest` 가 common-core 테스트 픽스처의 `HexagonalArchitectureRules`(JUnit 인터페이스)를
`implements` 하고, 서비스 main 클래스 위치만 읽어 `..domainlayer..` 아래를 검사한다(`./gradlew check`). 인가 규칙(`ControllerAuthorizationArchTestSupport`,
`security-guide.md` §9-11)과 같은 테스트 클래스에서 돈다. 보안과 무관한 규칙이라 security-core 가 아니라 common-core 에 둔다. 판정은 **패키지 이름 기준**이다(§2 구조를 지켜야 규칙이 본다).

| 규칙 | 금지 | 위반하면 이렇게 고친다 |
|------|------|------------------------|
| H1 Controller 는 UseCase 만 | 컨트롤러 → application 중 `port.in` · UseCase 입력 타입(`command` · `model`) 밖(Facade · Processor · out-port · Info), Presenter, out-adapter | UseCase 를 거친다. 정렬 enum 같은 `@RequestParam` 입력 타입은 `application.model` 에 두면 된다 |
| H2 application → adapter 금지 | 아래 예외 셋 밖의 의존 | 예외: ① `port/in` → 진입 DTO ② Facade(`*Facade`) → Presenter · 진입 DTO ③ `application/mapper` → Entity. 그 밖은 port/out 계약 · QueryResult · domain 으로 바꾼다 |
| H3 Processor → adapter 금지 | Response DTO · out-adapter 타입 | Info · domain model 을 돌려주고 응답은 Presenter 가 만든다 |
| H4 domain 은 순수 | domain → application · adapter · Spring · JPA · Hibernate | 프레임워크 타입은 entity · adapter 로 옮긴다 |
| H5 out-port 는 Info 를 내지 않는다 | `port/out` → `application/info` | QueryResult · domain 을 반환한다 (§4) |
| H6 진입 간 타입 공유 금지 | `adapter/in/web` ↔ `admin` ↔ `internal` | 진입마다 DTO 를 따로 둔다. 공용 변환 함수는 `adapter/in/support` (§2) |
| H7 in-adapter → out-adapter 금지 | 컨트롤러 · Presenter 가 리포지토리 · Feign client · Entity 를 직접 씀 | UseCase → Facade → Processor → Port 로 간다 |
| H8 컨텍스트 간 허용 목록 | 위 허용 목록 1~3 밖의 다른 컨텍스트 의존, domain → 다른 컨텍스트 domain | Processor(Facade 에서) · domain model(Facade · Processor 에서) · 예외(ExceptionHandler 에서)만. domain 끼리는 아이디로 |
| H9 Presenter 는 변환만 | Presenter → `application/service` · `application/port`(QueryResult 포함) | Facade 가 조회해 Info · domain 을 넘긴다 (§4) |

**사람이 보는 것** (리뷰 · `/hexagonal-guard` — 의미 판단이라 규칙화하지 않는다)

- 트랜잭션 경계 — 외부 I/O(PG · Feign · 소셜 · 택배사)가 섞인 Facade 에 `@Transactional` 을 걸지 않았는지, Processor 단위로 좁힌 이유 주석이 있는지 (§3 Facade)
- 상태 전이가 도메인 메서드로만 일어나는지(setter · `status = X` 금지), 저장 흐름이 domain → entity → save → domain 인지 (§5)
- Processor 안의 진입 분기(`if (isAdmin)`), ID(Snowflake) 생성 위치
- out-port 이름이 책임 기준인지(`PaymentGatewayPort` ○, `TossClientPort` ✕) — `coding-conventions.md` §5-1
- 진입 DTO 의 필드 누출(고객 응답에 원가 · 재고 수량 · 내부 메모), 다른 컨텍스트 Processor 메서드가 용도별인지(숨김 규칙 비켜 가기)
- 패키지 구조(§2)를 비켜 간 클래스 — 규칙은 패키지 이름으로 판정하므로 이름이 틀리면 보지 못한다

## 4. Query / Model 경계

- 조회 결과가 복잡하면 `application/port/out/query/*QueryResult` 를 사용한다.
- `Processor` 는 필요 시 `QueryResult → application/model` 변환을 수행한다.
- facade, presenter 쪽에는 `QueryResult` 가 직접 번지지 않게 유지한다.
- 목록 out-port 는 `Slice<QueryResult>` · `Page<QueryResult>` 를 반환할 수 있다. 원소는 Processor 가 Info 로 바꾸고 Presenter 는 `SliceResponse.of(...).map(...)` 만 한다.

## 5. Write 경계

- 저장 흐름은 `domain → entity → repository.save → entity → domain` 을 유지한다.
- ID 생성(Snowflake)은 Processor 또는 상위 오케스트레이션에서 수행한다.
- 상태가 바뀌는 도메인(주문·결제·배송·재고)은 **상태 전이를 도메인 모델 메서드로만** 바꾼다.
  setter 로 상태를 바꾸지 않는다 (`order-flow-guide.md`).

## 6. 보안 기준

- `auth-service` 가 토큰을 발급한다. 소셜 로그인은 카카오·네이버를 기본으로 한다.
- access 토큰은 **RS256 JWT**(`typ=at+jwt`)다. auth 만 개인키로 서명하고, 나머지 서비스·게이트웨이는 공개키로 검증하는
  **Resource Server** 다 (`security-core`). refresh 토큰은 JWT 가 아닌 불투명 난수이고 auth 가 저장·회전한다.
  토큰 사양 · 오류 코드 · 키 배포와 교체의 정본은 [security-guide.md](security-guide.md) 다.
- **게이트웨이는 검증 결과를 헤더(`X-Member-Id` 등)로 넘기지 않고, 서비스가 JWT 를 직접 검증한다.** 서비스가 헤더를 믿으면
  게이트웨이를 거치지 않은 요청이 그 헤더를 위조해 아무 회원이나 될 수 있다.
- 역할: `CUSTOMER`, `ADMIN`. 관리자 계정은 소셜 로그인 없이 별도 발급한다.
- 게이트웨이는 JWT 유효성 검증과 라우팅에 집중한다. **단 `/api/v1/admin/**` 은 게이트웨이에서도
  `ADMIN` 역할을 확인한다(이중 방어).** 서비스 안에서도 `@PreAuthorize("hasRole('ADMIN')")` 를 건다.
- 토큰이 없는 요청은 게이트웨이가 거부하지 않는다 — 상품 조회처럼 비로그인으로도 되는 API 가 있다.
  인증 필요 여부는 서비스가 판정한다.
- **로그아웃 차단 목록(Redis)은 게이트웨이만 본다(#4).** 저장소 장애 시 일반 경로는 통과(fail-open), `/api/v1/admin/**` 만 503 이다.
  서비스는 서명 · 만료만 보므로 사설망 직접 호출에는 로그아웃이 적용되지 않는다 — 정본은 [security-guide.md](security-guide.md) §7.
- `/internal/**` 은 게이트웨이 라우트에 넣지 않는다. 외부에서 닿지 않아야 한다.
- **서비스 간 internal API 는 서비스 토큰으로 인증한다(1단계, #43 · #51).** 서비스 5종이 공용 HS256 비밀로 짧은 토큰(`typ=svc+jwt`,
  `iss`=호출 서비스, `aud`=대상 서비스, 60초)에 서명하고, 대상은 `/internal/**` 전용 체인에서 검증한다 — 실패는 401 · `SECURITY_007`.
  엔드포인트마다 허용 호출자를 `@PreAuthorize("hasAuthority('SERVICE_<service-id>')")` 로 선언하고 403(`SECURITY_005`)으로 강제한다.
  1단계에서 호출자는 "선언" 이라 사설망 전제를 그대로 두고, 배포 환경이 정해지면 서비스별 키 · mTLS(2단계)로 "증명" 한다.
  정본은 [security-guide.md](security-guide.md) §9.
- 게이트웨이의 discovery locator 를 켜지 않는다 — 켜면 Eureka 에 등록된 서비스가 `/<service-id>/**` 로 자동 노출돼 내부 API 까지 열린다.
- **게이트웨이는 정규화되지 않은 경로를 라우팅 전에 거절한다** (`..` · `%2e` · `;` · `//` 등, `api-design-guide.md` §2-1).
  게이트웨이는 원문 경로로 라우트를 고르고 그대로 넘기는데, 하류 Tomcat 은 그 경로를 정규화한다 —
  `/api/v1/products/../../internal/...` 이 내부 API 로, `/api/v1/orders/../admin/...` 이 어드민 경로로 바뀌어 판정을 우회한다.
- **Eureka(8761) · 서비스 포트 · 게이트웨이 → 서비스 평문 HTTP 는 사설망 전용이다.** 외부에는 게이트웨이 포트만 연다.
  Eureka 레지스트리는 인증이 없어 닿기만 하면 인스턴스를 등록·삭제할 수 있다. 운영 Eureka 인증은 배포 이슈 · #32 에서 정한다.
  로컬은 `service-discovery` 의 `local` 프로파일이 루프백(127.0.0.1)에만 묶는다.
- 게이트웨이의 개발용 라우트(서비스 헬스 체크 · Swagger 문서, `api-design-guide.md` §1)는 local · dev 에서만 켠다 (`gateway.dev-routes.enabled`).
  서비스마다 **정확한 경로만** 열고 하류 경로를 고정한다(`/actuator/health`, `/v3/api-docs/{customer|admin}`).
  `/docs/{service-id}/**` 처럼 접두어만 떼는 라우트를 만들지 않는다 — `/internal/**` 에 닿고 어드민 경로 판정을 우회한다.
  dev 게이트웨이를 외부에 열면 이 경로들이 인증 없이 보이므로 dev 는 접근 제한(IP 허용 목록 · VPN 등) 뒤에 둔다.
- 카드번호 등 결제수단 정보는 **우리 서버를 지나가지 않는다** (PG 결제창/위젯이 받는다).

## 7. 내부 서비스 호출 기준

- Spring 서비스 간 동기 HTTP 호출은 `FeignClient` 를 기본으로 한다.
  - 예: order-service 가 주문 생성 시 product-service 에서 가격·재고를 조회/선점
  - 예: payment-service 가 승인 전 order-service 에서 주문 금액을 조회
- 내부 호출 계약은 `application/port/out` 뒤의 `adapter/out/client` 에서만 캡슐화한다.
  `FeignClient → Adapter → QueryResult` 흐름을 유지한다.
- internal Feign 클라이언트는 `configuration = InternalFeignClientConfiguration.class` 로 서비스 토큰을 싣는다 (`security-guide.md` §9-5).
  **사용자 `Authorization` 을 internal 로 넘기지 않는다** — 설정의 Feign Capability 가 최종 요청에서 지우고 서비스 토큰 하나로 바꾼다.
  사용자 식별이 필요하면 memberId 를 요청 본문 · 파라미터로 넘긴다. 이 설정은 전역이 아니다 — 외부 API(PG 등) 클라이언트에는 붙지 않는다.
- 내부 호출에는 공통 타임아웃과 Resilience4j CircuitBreaker 를 적용한다. 서킷 오픈·전송 실패는
  `adapter/out/client` 안에서 도메인 예외(503)로 변환하고 `FeignException` 을 상위로 노출하지 않는다
  (`coding-conventions.md` §10).
- **서비스 간 정합성** — 결제 승인처럼 "외부에서 이미 일어난 일" 을 다른 서비스에 반영할 때는
  실패해도 잃어버리지 않게 한다. 기본은 동기 호출 + 재시도 가능한 상태 기록 + 대사(reconciliation)이며,
  메시지 브로커(Kafka/RabbitMQ) 도입은 별도 이슈에서 결정한다 (`payment-guide.md` §6).

## 8. 외부 연동 기준

- PG(토스페이먼츠, PayPal), 택배사 추적, 소셜 로그인, 오브젝트 스토리지는 반드시
  `adapter/out/client` 뒤에 캡슐화하고, out-port 는 도메인 책임으로 명명한다
  (`PaymentGatewayPort`, `ShipmentTrackingPort`, `SocialLoginPort`).
- 외부 원본 응답은 adapter 밖으로 새지 않는다. adapter 에서 `QueryResult` 또는 domain model 로 변환한다.
- 모든 외부 호출은 connect/read timeout 을 명시한다. 타임아웃 없는 블로킹 호출을 금지한다.

## 9. 멀티 스토어(재사용) 기준

commerce-foundry 는 **싱글 테넌트 템플릿**이다 — 고객사마다 별도 배포·DB·PG 계약을 쓰고 코드는 공유한다.
멀티 테넌트 SaaS 로 만들지 않는다.

- 브랜드별 차이는 **설정(`store.*` 프로퍼티)과 기능 플래그**로 표현한다. 코드에 `if (mokoyuri)` 를 쓰지 않는다.

```yaml
store:
  code: mokoyuri
  default-locale: ko
  supported-locales: [ko, en]
  default-currency: KRW
  features:
    custom-order: true            # 맞춤 제작 옵션 (치수 입력·제작 단계)
    international-shipping: true  # 해외 배송비·USD 결제
    reviews: false
```

- `store.*` 는 쓰는 서비스의 `global/properties/StoreProperties` 로 바인딩한다 — 처음 쓰는 곳은 product-service 의 `store.features.custom-order`(#8)이고, delivery-service 가 `store.features.international-shipping`(#22)을 쓴다.
  두 번째 서비스가 **같은 프로퍼티**를 쓰게 되면 common-core 로 올린다 — 두 서비스가 서로 다른 플래그를 읽는 지금은 각 서비스에 둔다. 플래그 기본값은 꺼짐(닫힌 쪽)이고 local · dev 프로파일 yml 만 켠다.
  플래그가 꺼지면 데이터는 그대로 두고 응답에서만 숨긴다 (맞춤 제작: 목록 `customOrder=false`, 상세 `customOrder=null`).
- 상호·대표자·사업자등록번호·통신판매업 신고번호 같은 **법정 표시 정보는 Vault 에 등록하고 주입**한다
  (`STORE_*` — `secret-management.md`). 저장소가 public 이므로 yml 에 실값을 쓰지 않는다.
- 브랜드 특화 요구가 생기면 먼저 "다른 쇼핑몰에도 의미가 있는 일반 기능인가" 를 묻는다. 그렇다면
  플래그가 있는 공통 기능으로, 아니라면 확장 지점(전략 인터페이스)으로 만든다.
