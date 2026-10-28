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
│   ├── ai-core              Claude API 클라이언트(공식 Java SDK) · 개인정보 마스킹 · 동시 호출 상한 · 기동 가드 (#215)
│   ├── common-core          Response 래퍼, 오류 봉투, 검증 유틸, Swagger 공통
│   ├── persistence-core     JPA / QueryDSL / Snowflake ID / BaseEntity / Slice·PageResponse / 제약 위반 판정(ConstraintViolations)
│   ├── redis-core           Redis 연결 (Boot 자동설정 + 기동 가드) · 키 규칙
│   ├── security-core        JWT 발급(auth) · 검증(서비스 · 게이트웨이) · 서블릿 Resource Server · 인증 주체 · 보안 오류 코드
│   ├── storage-core         S3 호환 오브젝트 스토리지 클라이언트 (업로드 서명 · HEAD · 앞부분 읽기 · 복사 · 삭제) · 기동 가드 · 이미지 형식 판별
│   └── shared-commerce      서비스를 가로지르는 커머스 enum·값 (CurrencyCode, Money, ShippingScope, SalesChannel 등)
├── cloud/          (실행 모듈 — bootJar on)
│   ├── api-gateway          Spring Cloud Gateway — 라우팅, JWT 검증, admin 경로 보호
│   └── service-discovery    Eureka 서버
└── service/        (도메인 서비스 — bootJar on)
    ├── auth-service         회원 · 인증(일반/소셜) · 관리자 계정 · 배송지 주소록
    ├── product-service      카테고리 · 상품 · 옵션(SKU) · 재고 · 상품 이미지 · 다국어 상품정보
    ├── order-service        장바구니 · 주문 · 주문 금액 계산 · 클레임(취소/반품/교환)
    ├── payment-service      PG 결제 승인/취소 · 웹훅 · 대사 (토스페이먼츠: 카드·카카오페이·네이버페이·PayPal)
    ├── delivery-service     배송비 정책(국내/해외) · 송장 · 배송 상태 추적
    └── operations-service   백그라운드 작업(등록 · 실행 · 재시도 · 진행률 · 결과) · 정기 작업 · 어드민 알림 센터(#213)
```

후순위(필요해지면 추가): 쿠폰/프로모션 컨텍스트.
전에 후순위로 두었던 `batch-service`(자동 구매확정·결제 대사 배치·카페24 이관) · `notification-service`(알림)는 `operations-service` 가 흡수한다(#212) —
배치는 작업 엔진의 핸들러 · 정기 작업으로, 어드민 알림은 같은 서비스의 알림 센터(#213)로 붙인다.

### 1-1. core 모듈 포함 기준

| 모듈 | 넣는 것 | 넣지 않는 것 |
|------|---------|--------------|
| `ai-core` | 도메인을 모르는 Claude 클라이언트(`AiClient`) · 개인정보 마스킹(`PiiMasker`) · 이미지 바이트(base64, URL 없음) · 동시 호출 상한 · 설정 · 기동 가드. 공식 SDK 는 `implementation` 이라 서비스에 드러나지 않는다 | 프롬프트 문구 · 결과 해석 · 사용량 원장(엔티티) · 월 한도 정책 · 오류 번역 (쓰는 서비스 몫 — 원장 · 한도는 operations-service, #215 이후 PR) |
| `common-core` | 도메인 비의존 범용 인프라 | 특정 서비스의 도메인 개념 |
| `persistence-core` | DB 접근 공통 설정·유틸 | 엔티티 (엔티티는 각 서비스 소유) |
| `storage-core` | 도메인을 모르는 S3 호환 클라이언트(`ObjectStorageClient`) · 설정 · 기동 가드 · magic bytes 판별(`ImageFormat`). AWS SDK 는 `implementation` 이라 서비스에 드러나지 않는다 | 키 규칙(어느 prefix 에 무엇을 두는가) · 허용 형식 · 용량 상한 · 오류 코드 번역 (쓰는 서비스의 `adapter/out/client/storage` 몫) |
| `shared-commerce` | **두 서비스 이상이 같은 뜻으로 써야 하는** enum·값 객체 | 한 서비스만 쓰는 enum |

`shared-commerce` 가 필요한 이유: 같은 enum 을 서비스마다 복사하면 뜻이 갈라진다.
예를 들어 `CurrencyCode` 의 소수 자릿수를 order 와 payment 가 다르게 알면 USD 주문이 100배로 결제된다.
배송 범위 `ShippingScope`(`DOMESTIC` · `INTERNATIONAL`)와 국내 국가 판정 `ShippingScope.ofCountry`(`KR` 이면 국내)도 여기 둔다 —
delivery 는 배송비 정책을 범위로 고르고 order 는 해외 주문을, auth 는 배송지 국가별 규칙을 판정하는데, 서비스마다 `"KR"` 을 따로 비교하면 국내 규칙이 갈라진다.
해외 범위에 든다고 배송할 수 있는 것은 아니다 — 배송 가능 여부는 delivery 가 판정한다(스토어의 해외 배송 설정 · 해외 정책의 국가 그룹).
국내 국가(`KR`)는 지금 엔진 공통 코드에 고정한다 — 스토어별 국내 국가 설정이 필요해지면 별도 이슈로 `store.*` 설정 쪽으로 옮긴다(§9 멀티 스토어 기준).

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
  |  |  |  |- exception         (*ExceptionHandler — 그 컨텍스트에 in/web 이 없을 때만. 예: operations-service job)
  |  |  |  \- presenter
  |  |  |- in/internal          서비스 간 전용 — /internal/v1/** (게이트웨이가 라우팅하지 않는다)
  |  |  |  |- controller
  |  |  |  |- dto/{request,response,item}
  |  |  |  \- presenter
  |  |  |- in/support           진입 adapter 들이 같이 쓰는 변환 함수 (예: Money → MoneyItem). DTO 는 두지 않는다
  |  |  |- in/webhook           외부 시스템 콜백 — PG 웹훅 등 (payment-service)
  |  |  |- in/scheduler         시각이 부르는 진입점 (@Scheduled — 재고 선점 만료, 미결제 주문 정리 등)
  |  |  |- in/bootstrap         기동이 부르는 진입점 (ApplicationRunner — 초기 관리자 시드 #6). 설정을 커맨드로 옮겨 UseCase 만 부른다
  |  |  |- in/event             이벤트가 부르는 진입점 — 같은 서비스 안 ApplicationEvent(작업 종료 → 알림 #213)와 Kafka *EventListener(주문 만료 → 재고 해제 #309). UseCase 만 부른다
  |  |  \- out
  |  |     |- persistence
  |  |     |  |- entity
  |  |     |  |- repository
  |  |     |  |  \- custom      (*CustomRepository + Impl — QueryDSL 동적 쿼리·조인)
  |  |     |  \- *RepositoryAdapter
  |  |     |- log               (*AuditLogAdapter — 관리자 감사 로그 한 줄, security-guide.md §11-4)
  |  |     |- event             (*EventAdapter — Kafka 로 나갈 메시지를 outbox 에 쓴다, coding-conventions.md §12)
  |  |     |- lock              (*LockAdapter — 단일 실행 락 ShedLock, 예: 정기 작업 플래너)
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
  |  |  |- port/in              (*WebUseCase, *AdminUseCase, *InternalUseCase, *SchedulerUseCase, *EventUseCase)
  |  |  |- port/out             (+ query/*QueryResult)
  |  |  \- service
  |  |     |- *WebFacade / *AdminFacade / *InternalFacade / *SchedulerFacade / *EventFacade
  |  |     \- processor
  |  \- domain
  |     \- model
  \- global
     |- config                  (*BeansConfig, *PropertiesConfig, *FeaturesConfig, *SwaggerConfig)
     |- persistence             서비스 공용 영속성 도우미 (예: ConstraintViolations — 제약 이름 판정)
     |- support                 계층이 아닌 공용 값 · 도우미 (예: WarnThrottle, JobWorkerIdentity)
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
- 이름: `*WebUseCase`(고객) · `*AdminUseCase`(어드민) · `*InternalUseCase`(서비스 간) · `*SchedulerUseCase`(스케줄러, `in/scheduler`) ·
  `*EventUseCase`(Kafka 이벤트 리스너, `in/event` — 예: product `StockEventUseCase`). 구현 Facade 도 같은 접두로 짓는다(`*SchedulerFacade` · `*EventFacade`).

### Facade

- 유스케이스의 메인 오케스트레이터다. 여러 Processor 와 Presenter 를 조합한다.
- 읽기는 `@Transactional(readOnly = true)`, 쓰기는 `@Transactional` 을 기본으로 검토한다.
- **예외: 유스케이스에 외부 I/O(PG 승인·소셜 로그인·택배사 조회·다른 서비스 Feign 호출)가 섞이면
  트랜잭션을 Facade 에 걸지 않는다.** DB 커넥션을 잡은 채 원격 응답을 기다리게 되고, 원격 호출이
  성공한 뒤 롤백되면 "PG 는 승인했는데 우리 DB 에는 없는" 상태가 생긴다. 외부 호출을 트랜잭션 밖에
  두고 DB 구간만 Processor 단위로 `@Transactional` 을 건다. **왜 좁혔는지 메서드 주석으로 남긴다.**
- **의도적으로 느린 CPU 작업(비밀번호 해시 — BCrypt)** 도 같은 예외다. 트랜잭션 안에서 하면 해시 시간만큼 DB 커넥션을 쥔다.
  해시는 트랜잭션 밖에서 만들고 저장 구간만 Processor 에 `@Transactional` 을 건다 (예: auth `MemberWebFacade#signUp`).
- **같은 행을 워커 · 다른 요청과 다투는 낙관적 충돌 재시도** 도 예외다. 버전에서 지면 새 트랜잭션에서 다시 읽어야 바뀐 상태로 판정되므로
  트랜잭션은 시도마다 Processor 가 열고 Facade 는 재시도만 한다 (예: operations `JobAdminFacade#cancel`, 작업 등록 `JobRegisterFacade`).

### Processor

- 실제 애플리케이션 로직을 처리한다. `Info` / domain model / application model 을 반환한다.
- **Response DTO 를 직접 만들지 않는다.**
- Port 호출, 도메인 조합, 검증, ID 생성 책임을 가진다.

### Presenter

- `Info → Response`, `Info → Item` 변환만 담당한다. **식별자 `long → String` 변환 경계다.**
- 금액 `Money → MoneyItem`, enum → metadata(`toMetadata()`) 변환도 Presenter 경계다. Processor · Info 는 `Money` · enum 그대로 둔다.
- Processor 가 돌려준 domain model 을 조합·가공 없이 그대로 응답으로 바꿀 때는 Info 를 두지 않고 Presenter 가 domain model 을 받는다 (예: delivery-service `ShippingFeeWebPresenter`, #11).
  (같은 컨텍스트 domain 만 — 다른 컨텍스트 domain 은 Info 로 옮긴다, H8)

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

한 서비스에 컨텍스트가 여럿이면(product-service 의 `category` · `product`, order-service 의 `catalog` · `cart` · `order`) 다른 컨텍스트에서 쓸 수 있는 것은 **다섯이다** (허용 목록).

1. **그 컨텍스트의 Processor.** Facade 만 주입한다 — Processor 끼리는 컨텍스트를 넘어 주입하지 않는다.
2. **그 Processor 가 돌려준 domain model, 그리고 그 메서드가 돌려준 domain 값(예: `CategoryPlacementViolation`).** 읽기 전용이다 — Facade 가 자기 컨텍스트 Processor 의 인자로 넘길 수 있다.
3. **그 Processor 가 던지는 예외(`application.exception`).** 부른 쪽 컨텍스트의 `adapter/in/*/exception` 의 `*ExceptionHandler` 만 받아 번역한다
   (Processor 를 부르면 그 예외는 반드시 넘어온다). 그 밖의 계층은 다른 컨텍스트 예외를 쓰지 않는다.
4. **그 컨텍스트 domain 이 연 확장 지점 — domain 인터페이스 구현과 domain 입력 값.** 엔진형 컨텍스트가 기능을 받아들이는 자리다.
   예: operations-service 의 `job` 은 domain `JobHandler` 를 열어 두고, 기능 컨텍스트의 `*JobProcessor` 가 구현한다.
   기능 Facade 는 `JobRegistrationProcessor.register(JobRequest)` 로 등록한다 — `JobRegisterCommand`(`application.command`)는 넘지 않는다 (§10).
5. **`adapter/in/event` 가 다른 컨텍스트의 domain model 을 읽는 것.** 같은 서비스 안에서 발행된 종료 이벤트를 받기 위해서다 (#213).
   리스너는 자기 `port/in` UseCase 만 부른다. 다른 컨텍스트의 application · adapter 는 쓰지 않는다.

- 그 밖(UseCase · Facade · Presenter · Info · Port · Adapter · Mapper · Entity · Repository · Q 타입)은 쓰지 않는다.
  다른 컨텍스트의 포트 · 리포지토리를 직접 부르면 그 컨텍스트의 조회 규칙(숨김 · 정렬 · 캐시)을 비켜 간다.
- domain 은 다른 컨텍스트 domain 을 필드로 갖지 않고 아이디로만 가리킨다 (예: `Product.categoryId`).
- 의존은 한 방향으로만 둔다 (지금 product → category, cart → catalog, order → catalog). 역방향이 필요해지면 컨텍스트 경계를 다시 정한다.
- 트랜잭션은 호출한 Facade 가 건다.
- 다른 컨텍스트 Processor 가 던진 도메인 예외는 같은 서비스 · 같은 코드 접두어(예: auth 의 `AUTH_`)면 번역하지 않고 그대로 올린다 —
  예: auth 배송지 Facade 가 부르는 `MemberLockProcessor` 의 `AUTH_003`(회원 없음)은 그 컨텍스트의 advice 가 봉투로 만든다 (#7).
- 숨김 같은 조회 규칙은 그 컨텍스트가 정한다 — 다른 컨텍스트는 용도별 Processor 메서드만 부른다.
  예: `ProductWebFacade` 는 `CategoryProcessor.subtreeIdsOf(categoryId)`(목록 필터 — 숨김과 무관) · `visiblePathOf(categoryId)`(상세 경로 — 숨김 제외)만 부른다.
  예: 고객 룩(#62) `LookWebFacade` 는 상품 카드를 `ProductListProcessor.summarizeVisible`(숨김 제외), 상품별 룩의 상품 확인을 `ProductDetailProcessor.getVisibleProduct`(없거나 숨김 → 404 `PRODUCT_001`)로만 받는다.
- 다른 컨텍스트의 쓰기도 그 컨텍스트 Processor 로만 한다. 판정에 부른 쪽 데이터가 필요하면 API 를 부른 쪽에 둔다.
  예: 어드민 카테고리 관리(#9)는 삭제 판정에 "연결된 상품" 이 필요해 상품 컨텍스트에 둔다 — `CategoryAdminFacade` 가 `CategoryProcessor.getTreeForUpdate()`(잠근 트리)를 받아
  `CategoryAdminProcessor.validate*` 에 넘겨 판정하고, 쓰기는 다시 `CategoryProcessor`(`create` · `update` · `delete`)로 한다. 응답은 `CategoryAdminProcessor` 가 Info 로 옮겨 Presenter 에 넘긴다.
- 여러 컨텍스트가 같은 원격 조회를 쓰면 그 조회를 공용 컨텍스트 하나로 뽑는다 — 원격 계약 파싱을 두 벌 두지 않는다.
  예: order-service 의 `cart`(그리고 #13 의 `order`)가 `catalog`(`CatalogSkuProcessor` · `CatalogSku`)를 쓴다. `catalog` 는 SKU 조회(Feign · 서킷 · 응답 변환)만 하고,
  담기 가능 판정 · 수량 상한 같은 규칙은 부른 컨텍스트의 Processor 한 곳(`CartProcessor.rejectionOf` · `maxQuantityOf`)에 두고, `CartWebFacade` 는 그 결과를 오류 코드로 바꾸기만 한다.
  주문은 Facade 가 `findSkus` 결과(`Map<Long, CatalogSku>`)를 `OrderQuoteProcessor#quote` 의 인자로 넘기고, 주문 규칙(상품 단위 수량 상한 · 해외 판정)은 그 Processor 에 둔다.
- 다른 컨텍스트의 예외는 호출한 컨텍스트의 `*ExceptionHandler` 가 자기 코드로 번역한다 — advice 범위가 컨트롤러 패키지(`basePackages`)라서다.
  예: `CatalogException`(`CATALOG_001`, 503)은 advice 를 두지 않고 `CartExceptionHandler` 가 `CART_008`(503)로 번역한다. API 계약은 부른 컨텍스트의 코드다.
- 다른 서비스의 데이터는 지금처럼 internal API(Feign)로만 받는다 (§7). 다른 서비스 스키마를 직접 조회하지 않는다.

### 기계로 보는 것 / 사람이 보는 것 (#77)

계층 의존은 **빌드가 본다.** 서비스의 `<Svc>ArchitectureTest` 가 common-core 테스트 픽스처의 `HexagonalArchitectureRules`(JUnit 인터페이스)를
`implements` 하고, 서비스 main 클래스 위치만 읽어 `..domainlayer..` 아래를 검사한다(`./gradlew check`). 인가 규칙(`ControllerAuthorizationArchTestSupport`,
`security-guide.md` §9-11)과 같은 테스트 클래스에서 돈다. 보안과 무관한 규칙이라 security-core 가 아니라 common-core 에 둔다. 판정은 **패키지 이름 기준**이다(§2 구조를 지켜야 규칙이 본다).

| 규칙 | 금지 | 위반하면 이렇게 고친다 |
|------|------|------------------------|
| H1 Controller 는 UseCase 만 | 컨트롤러 → 아래 허용 밖의 application(Facade · Processor · out-port · Info · `*ValidationMessage` 가 아닌 예외 타입), domain, Presenter, out-adapter | UseCase 를 거친다. 허용: `port/in` UseCase, 입력 타입(`command` · `model` — 정렬 enum 같은 `@RequestParam`), 검증 메시지 상수 `*ValidationMessage`, 진입 DTO |
| H2 application → adapter 금지 | 아래 예외 셋 밖의 의존 | 예외: ① `port/in` → 진입 DTO ② Facade(`*Facade`) → Presenter · 진입 DTO ③ `application/mapper` → Entity. 그 밖은 port/out 계약 · QueryResult · domain 으로 바꾼다 |
| H3 Processor → adapter 금지 | Response DTO · out-adapter 타입 | Info · domain model 을 돌려주고 응답은 Presenter 가 만든다 |
| H4 domain 은 순수 | domain → application · adapter · Spring · JPA · Hibernate | 프레임워크 타입은 entity · adapter 로 옮긴다 |
| H5 out-port 는 Info 를 내지 않는다 | `port/out` → `application/info` | QueryResult · domain 을 반환한다 (§4) |
| H6 진입 간 타입 공유 금지 | `adapter/in/web` ↔ `admin` ↔ `internal` ↔ `webhook`, `adapter/in/support` → 진입 DTO | 진입마다 DTO 를 따로 둔다. support 에는 공용 변환 함수 · 공용 타입(`MoneyItem`)만 둔다 (§2) |
| H7 in-adapter → out-adapter 금지 | 진입(컨트롤러 · Presenter · 스케줄러 등)이 리포지토리 · Feign client · Entity 를 직접 씀 | UseCase → Facade → Processor → Port 로 간다 |
| H8 컨텍스트 간 허용 목록 | 위 허용 목록 밖의 다른 컨텍스트 의존, domain → 다른 컨텍스트 domain | 최상위 `*Processor`(Facade 에서 — Processor 안의 중첩 타입은 안 된다) · domain model(Facade · Processor · `adapter/in/event` 에서) · `application.exception`(`adapter/in/*/exception` 의 `*ExceptionHandler` 에서)만. domain 끼리는 아이디로 |
| H9 Presenter 는 변환만 | Presenter → `application/service` · `application/port`(UseCase · out-port · QueryResult) | Facade 가 조회해 Info · domain 을 넘긴다 (§4) |
| H10 진입은 UseCase 를 거친다 | `adapter/in` 전체(컨트롤러 · Presenter · ExceptionHandler · support · **스케줄러 · 웹훅**) → Facade · Processor · out-port · mapper | 스케줄러 · 웹훅도 `port/in` UseCase 를 둔다 — 트랜잭션 경계(Facade)를 비켜 가지 않는다 |
| H11 Facade 는 Processor 를 거친다 | Facade → `application/port/out`(QueryResult 포함) | 조회 · 저장은 Processor 가 한다. QueryResult 는 Facade · Presenter 로 번지지 않는다 (§4) |
| H12 전송 타입 누수 금지 | application · domain → `feign..` · `org.springframework.cloud.openfeign..` · `common.dto..` | 예외: `common.dto.metadata`(도메인 enum 의 `CodeNameDescribable`), port/in · Facade 의 `Response` 봉투. Feign 응답 래퍼 · `MoneyItem` 은 adapter 에서 QueryResult · `Money` 로 바꾼다. `SliceResponse` · `PageResponse` 는 persistence-core 라 대상 밖 |

**사람이 보는 것** (리뷰 · `/hexagonal-guard` — 의미 판단이라 규칙화하지 않는다)

- 트랜잭션 경계 — 외부 I/O(PG · Feign · 소셜 · 택배사)가 섞인 Facade 에 `@Transactional` 을 걸지 않았는지, Processor 단위로 좁힌 이유 주석이 있는지 (§3 Facade)
- 상태 전이가 도메인 메서드로만 일어나는지(setter · `status = X` 금지), 저장 흐름이 domain → entity → save → domain 인지 (§5)
- Processor 안의 진입 분기(`if (isAdmin)`), ID(Snowflake) 생성 위치
- out-port 이름이 책임 기준인지(`PaymentGatewayPort` ○, `TossClientPort` ✕) — `coding-conventions.md` §5-1
- 진입 DTO 의 필드 누출(고객 응답에 원가 · 재고 수량 · 내부 메모), 다른 컨텍스트 Processor 메서드가 용도별인지(숨김 규칙 비켜 가기)
- 패키지 구조(§2)를 비켜 간 클래스 — 규칙은 패키지 이름과 바깥 클래스 이름 접미사(`*Facade` · `*Processor` · `*ExceptionHandler` · `*ValidationMessage`)로 판정하므로 이름이 틀리면 보지 못한다.
  `global` 패키지(설정 · 프로퍼티)는 규칙 대상이 아니다 — application 이 `global.properties` · core 컴포넌트(`SnowflakeIdGenerator`, 토큰 발급기 등)를 쓰는 것은 허용된 상태다
- PG SDK 처럼 위 H12 패턴에 없는 외부 전송 타입이 application 으로 새지 않는지

## 4. Query / Model 경계

- 조회 결과가 복잡하면 `application/port/out/query/*QueryResult` 를 사용한다.
- `Processor` 는 필요 시 `QueryResult → application/model` 변환을 수행한다.
- facade, presenter 쪽에는 `QueryResult` 가 직접 번지지 않게 유지한다.
- 목록 out-port 는 `Slice<QueryResult>` · `Page<QueryResult>` 를 반환할 수 있다. 원소는 Processor 가 Info 로 바꾸고 Presenter 는 `SliceResponse.of(...).map(...)` 만 한다.
  같은 컨텍스트 domain 을 가공 없이 내리는 목록은 out-port 가 `Page<domain>` 을 돌려줘도 된다 — Processor 가 그대로 넘기고 Presenter 가 domain 을 받는다
  (예: delivery `ShippingPolicyHistoryRepositoryPort.findDomesticPage`, #69).

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
- 역할: `CUSTOMER`, `ADMIN`. 관리자 계정은 소셜 로그인 없이 별도 발급한다 — 같은 `auth.member` 테이블 · 같은 아이디 공간이고, 초기 관리자는 환경변수 시드,
  로그인은 `POST /api/v1/auth/admin/login`(access 만)이다. 관리자 행위는 감사 로그 한 줄(`admin.audit`)로 남긴다 — 정본은 [security-guide.md](security-guide.md) §11.
- 게이트웨이는 JWT 유효성 검증과 라우팅에 집중한다. **단 `/api/v1/admin/**` 은 게이트웨이에서도
  `ADMIN` 역할을 확인한다(이중 방어).** 서비스 안에서도 `@PreAuthorize("hasRole('ADMIN')")` 를 건다.
- 토큰이 없는 요청은 게이트웨이가 거부하지 않는다 — 상품 조회처럼 비로그인으로도 되는 API 가 있다.
  인증 필요 여부는 서비스가 판정한다.
- **로그아웃 차단 목록(Redis)은 게이트웨이만 본다(#4).** 저장소 장애 시 일반 경로는 통과(fail-open), `/api/v1/admin/**` 만 503 이다.
  서비스는 서명 · 만료만 보므로 사설망 직접 호출에는 로그아웃이 적용되지 않는다 — 정본은 [security-guide.md](security-guide.md) §7.
- `/internal/**` 은 게이트웨이 라우트에 넣지 않는다. 외부에서 닿지 않아야 한다.
- **서비스 간 internal API 는 서비스 토큰으로 인증한다(1단계, #43 · #51).** 서비스 전부가 공용 HS256 비밀로 짧은 토큰(`typ=svc+jwt`,
  `iss`=호출 서비스, `aud`=대상 서비스, 60초)에 서명하고, 대상은 `/internal/**` 전용 체인에서 검증한다 — 실패는 401 · `SECURITY_007`.
  엔드포인트마다 허용 호출자를 `@PreAuthorize("hasAuthority('SERVICE_<service-id>')")` 로 선언하고 403(`SECURITY_005`)으로 강제한다.
  1단계에서 호출자는 "선언" 이라 사설망 전제를 그대로 두고, 배포 환경이 정해지면 서비스별 키 · mTLS(2단계)로 "증명" 한다.
  정본은 [security-guide.md](security-guide.md) §9.
- 게이트웨이의 discovery locator 를 켜지 않는다 — 켜면 Eureka 에 등록된 서비스가 `/<service-id>/**` 로 자동 노출돼 내부 API 까지 열린다.
- **게이트웨이는 정규화되지 않은 경로를 라우팅 전에 거절한다** (`..` · `%2e` · `;` · `//` 등, `api-design-guide.md` §2-1).
  게이트웨이는 원문 경로로 라우트를 고르고 그대로 넘기는데, 하류 Tomcat 은 그 경로를 정규화한다 —
  `/api/v1/products/../../internal/...` 이 내부 API 로, `/api/v1/orders/../admin/...` 이 어드민 경로로 바뀌어 판정을 우회한다.
- **Eureka(8761) · 서비스 포트 · 게이트웨이 → 서비스 평문 HTTP 는 사설망 전용이다.** 외부에는 게이트웨이 포트만 연다.
  Eureka 레지스트리는 HTTP Basic(공유 비밀번호 `EUREKA_PASSWORD`)으로 등록 · 조회를 닫는다 — `health` 만 익명, 대시보드는 local 만 연다(#248, `security-guide.md` §12).
  평문이라 도청은 막지 못한다. 로컬은 `service-discovery` 의 `local` 프로파일이 루프백(127.0.0.1)에도 묶는다.
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
  메시지 브로커는 Kafka 로 정했고(§8-1) 결제 → 주문 반영의 이벤트 전환은 #308 이다 (`payment-guide.md` §6).

## 8. 외부 연동 기준

- PG(토스페이먼츠, PayPal), 택배사 추적, 소셜 로그인, 오브젝트 스토리지는 반드시
  `adapter/out/client` 뒤에 캡슐화하고, out-port 는 도메인 책임으로 명명한다
  (`PaymentGatewayPort`, `ShipmentTrackingPort`, `SocialLoginPort`).
- 외부 원본 응답은 adapter 밖으로 새지 않는다. adapter 에서 `QueryResult` 또는 domain model 로 변환한다.
- 모든 외부 호출은 connect/read timeout 을 명시한다. 타임아웃 없는 블로킹 호출을 금지한다.

### 8-1. 메시징 — Kafka (#304)

**서비스 간 비동기 메시징 · 큐 · 작업 분배의 미들웨어는 Kafka 다** (사용자 결정 2026-10-25). Infra 의 공유 Kafka 클러스터(KRaft 3노드, RF 3 / min ISR 2)를 쓴다 —
호스트 · 포트는 저장소에 쓰지 않고 `KAFKA_BOOTSTRAP_SERVERS`(Vault)로만 받는다. 설계 전문과 하위 이슈(#306 공통 모듈 · #307 작업 엔진 · #308 결제 → 주문 · #309 주문 만료 →
재고 해제 · #310 연결 · 토픽)는 #304 설계 댓글이 정본이고, 아래는 구현이 따라야 할 규칙이다. 구현 전까지 각 절(§10, `payment-guide.md` §6)의 현재 동작은 그대로다.

- **결과가 필요한 명령은 Feign, 일어난 사실은 Kafka.** 선점 확정 · 해제 · 금액 조회처럼 응답으로 분기하는 호출은 internal API(§7) 그대로다.
  결제 승인됨 · 주문 만료됨 · 작업 등록됨 같은 사실은 outbox → Kafka → 컨슈머로 전한다.
- **주기 조회는 두 곳에만 남는다** — ① outbox 릴레이 ② 시간 기준 만료 판정 트리거(주문 만료 · 재고 선점 안전망 · 작업 lease 회수 · 결제 재조회 등).
  그 밖의 업무 처리는 모두 Kafka 컨슈머가 한다. 만료 트리거는 판정 + 상태 전이 + outbox 발행만 하고(건별 트랜잭션 · `@Version`), 후속 처리는 컨슈머가 한다.
- **발행 정합성 = transactional outbox + 폴링 릴레이**(CDC · Debezium 은 쓰지 않는다 — 사용자 결정). 업무 트랜잭션과 같은 커밋으로 서비스 스키마의 `outbox` 에 행을 넣고,
  릴레이가 `published_at is null and available_at <= now` 를 id 순으로 읽어 발행한 뒤 `published_at` 을 조건부 UPDATE 로 기록한다. 발행은 트랜잭션 밖이다.
  다중 인스턴스는 단일 실행 락(ShedLock)으로 한 인스턴스만 릴레이를 돌려 같은 키의 발행 순서를 지킨다. 지연 발행(작업 예약 · 백오프)은 `available_at` 으로 한다.
- **컨슈머는 멱등이다** — 수동 ack, at-least-once 전제. 상태를 바꾸는 트랜잭션 안에서 `processed_event` 를 넣는다 — `(consumer_group, event_id)` 유니크다.
  처리 전에 `exists` 로 이미 처리한 이벤트를 건너뛰고, 동시에 두 번 받아 유니크 위반이 나면 그 트랜잭션이 롤백 → 컨슈머 재시도 → `exists` 로 건너뛴다.
  상태 전이 멱등(이미 PAID · RELEASED 면 no-op)이 2차 방어다. 일시 오류는 컨슈머 안 지수 백오프 뒤 DLT(`<topic>.dlt`), 종결 오류는 바로 DLT 다.
  `@RetryableTopic`(재시도 토픽 체인)은 쓰지 않는다. DLT 재처리는 사람이 한다.
  inbox 의 첫 구현은 product-service 의 주문 만료 · 취소 컨슈머다(`global/messaging/JpaProcessedEventStore`, `order-flow-guide.md` §3-5).
- **이름(공유 클러스터)** — namespace `commerce-foundry.<env>`(`KAFKA_NAMESPACE`, 마지막 토막 = 활성 프로파일이 아니면 기동하지 않는다).
  토픽 `<ns>.<생산 서비스>.<aggregate>-<event>`, 컨슈머 그룹 `<ns>.<소비 서비스>.<목적>`, 파티션 키는 애그리거트 아이디(orderId · jobId).
  다른 고객사가 같은 클러스터를 쓰면 namespace 만 바꾼다(§9).
- **토픽은 앱이 만들지 않는다** — `spring.kafka.admin.auto-create=false`, `NewTopic` 빈 금지. 토픽 목록 · 생성 스크립트 · 추가 절차는
  `deploy-guide.md` §4-4(로컬은 `local-run-guide.md` §3)이고, 앱은 기동 때 존재만 확인한다.
- **이벤트 봉투** — `{eventId, eventType, schemaVersion, occurredAt, producer, aggregateType, aggregateId, payload}`. 필드는 추가만 하고, 호환을 깨면 `schemaVersion` 과
  토픽 접미사 `.v2` 를 올린다. **payload 에 이름 · 연락처 · 주소 · 이메일 · 카드 정보 · PG 결제 키 · 오류 원문을 싣지 않는다** — 아이디 · 금액 · 상태 코드 · 시각만 두고
  세부는 컨슈머가 internal API 로 읽는다(Kafka UI 로 본문이 열람되고 7일 보존된다).
- 클러스터는 지금 PLAINTEXT · ACL 없음이다 — 이름으로 구분할 뿐 격리가 아니다. SASL · ACL 은 Infra 후속 이슈로 하고 앱은 `KAFKA_SASL_*` 키를 예약만 한다.

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
    preorder: true                # 프리오더(예약 판매) · 발송 예정일 안내
    product-video: true           # 상품 상세 갤러리 영상
    looks: true                   # 룩(Shop the Look) 고객 노출 — 어드민 룩 관리는 플래그와 무관
    international-shipping: true  # 해외 배송비·USD 결제
    reviews: false
```

- `store.*` 는 쓰는 서비스의 `global/properties/StoreProperties` 로 바인딩한다 — 처음 쓰는 곳은 product-service 의 `store.features.custom-order`(#8)이고, delivery-service 가 `store.features.international-shipping`(#22)을 쓴다.
  두 번째 서비스가 **같은 프로퍼티**를 쓰게 되면 common-core 로 올린다 — 두 서비스가 서로 다른 플래그를 읽는 지금은 각 서비스에 둔다. 플래그 기본값은 꺼짐(닫힌 쪽)이고 local · dev 프로파일 yml 만 켠다.
  플래그가 꺼지면 데이터는 그대로 두고 응답에서만 숨긴다 (맞춤 제작: 목록 `customOrder=false`, 상세 `customOrder=null`).
  product-service 는 `preorder`(목록 `preorder=false`, 상세 `preorder=null`) · `product-video`(상세 `images[]` 에서 VIDEO 제외)도 같은 방식으로 읽는다(#61).
  `looks`(#62)는 고객 룩 조회만 본다(판정은 고객 조회 `LookWebProcessor` 한 곳 — 꺼지면 목록 빈 쪽 · 상세 404 · 상품별 빈 목록) — 어드민 룩 관리는 플래그와 무관하게 동작해 켜기 전에 룩을 미리 만들어 둘 수 있다.
- 메인 화면 구성 · 문구는 코드가 아니라 **전시 데이터**(product-service 메인 전시, #63 — `api-design-guide.md` §2-9)다. 섹션 유형은 브랜드 무관한 블록 이름이고
  고객사 문구는 제목 값으로만 들어간다. 미디어 주소의 허용 범위는 `store.media.public-base-url`(스토리지 공개 주소, 비면 미디어 쓰기를 닫는다)로 정한다.
- 상호·대표자·사업자등록번호·통신판매업 신고번호 같은 **법정 표시 정보는 Vault 에 등록하고 주입**한다
  (`STORE_*` — `secret-management.md`). 저장소가 public 이므로 yml 에 실값을 쓰지 않는다.
- 브랜드 특화 요구가 생기면 먼저 "다른 쇼핑몰에도 의미가 있는 일반 기능인가" 를 묻는다. 그렇다면
  플래그가 있는 공통 기능으로, 아니라면 확장 지점(전략 인터페이스)으로 만든다.

## 10. 백그라운드 작업 실행 (operations-service, #212)

몇 초 ~ 몇 분 걸리는 어드민 작업(리포트 · 정산 대사 · 집계 · AI 생성 · 발송)은 요청 안에서 끝내지 않는다. 게이트웨이 응답 타임아웃(10s)에 끊긴다.
기능 API 가 작업을 등록하고 `202 + jobId` 로 바로 답하면(`api-design-guide.md` §7), operations-service 의 워커가 처리한다.

- **저장소는 PostgreSQL `operations.job` 하나다.** Redis Streams 는 쓰지 않는다 — 상태 조회 · 감사 · 멱등이 한 테이블에서 끝나고, 영구 키 금지 · TTL 필수(`coding-conventions.md` §9-7)와 맞지 않는다.
  상태 · 멱등 · 취소 · 진행률 · lease 의 정본은 이 테이블이고, Kafka 는 "이 시도를 실행하라" 는 신호만 나른다(#307, 설계 #304 · §8-1).
- **디스패치는 Kafka 실행 신호다(#307).** 작업이 QUEUED 가 되는 트랜잭션(등록 · 재시도 · lease 만료 회수 · 종료 때 돌려놓기 · 실행 거절)이 같은 커밋으로
  outbox 행(`available_at = scheduled_at`)을 넣고(`JobDispatchPort`), 릴레이가 `<ns>.operations.job-run`(키 jobId, 본문 `JobRunMessage` — jobId · type · attempt · scheduledAt 만)으로 보낸다.
  컨슈머(`JobRunEventListener`, 그룹 `<ns>.operations.job-runner`, 인스턴스당 스레드 1)가 가져가 실행 스레드에 넘긴 뒤 바로 ack 한다 — 실행이 길어도 파티션 · 리밸런스를 붙잡지 않는다.
  인스턴스 전체 실행 칸이 찼거나 종료 중이면 `nack(Duration)`(`worker.redelivery-delay`, 1m 이하)으로 늦춰 다시 받는다 — 이 리스너만 ack 모드가
  MANUAL_IMMEDIATE 다(`JobRunListenerConfig`). nack 은 컨슈머의 모든 파티션을 그동안 멈추므로, 종류별 칸만 찼거나 예약 시각 전(인스턴스 시계 차)이면
  같은 시도의 신호를 outbox 로 늦게 다시 내고(`available_at` = 지금 + `redelivery-delay` · 예약 시각) 받은 신호는 확인한다 — 다른 종류 신호를 막지 않는다.
  실행 순서는 파티션 도착 순서이고 `priority` 는 기록용이다.
- **가져가기는 신호의 시도 번호 + `@Version` 경합이다.** 신호가 가리킨 작업을 다시 읽어 `QUEUED` 이고 시도 번호 · 종류가 신호와 같을 때만 도메인 `Job.claim` 으로 바꿔 저장한다.
  다르면(이미 가져감 · 끝남 · 다른 시도의 늦은 신호) 버린다 — at-least-once 신호의 중복은 이것으로 거르고 inbox(`processed_event`)를 두지 않는다.
  같은 신호를 두 인스턴스가 받아도 버전에서 진 쪽은 `JOB_CONFLICT` 로 건너뛴다. 작업 가져가기에는 분산 락 · `SKIP LOCKED` 를 쓰지 않는다(재고 선점 만료 스케줄러 #10 과 같은 방식).
  outbox 릴레이는 단일 실행 락(ShedLock, `operations.shedlock`)을 쓴다.
- **신호 오류** — 읽을 수 없는 봉투 · 모르는 새 버전 · 아이디 형식 오류 · 이 인스턴스에 핸들러가 없는 종류는 바로 DLT(`<topic>.dlt`)다. DB 오류 같은 일시 오류는 컨슈머 안 재시도
  (`commerce.messaging.retry.*`) 뒤 DLT 다. DLT 로 간 신호의 작업은 QUEUED 로 남는다 — 사람이 DLT 메시지를 다시 보내 실행한다(롤링 배포 중 새 종류의 신호를 옛 인스턴스가 받은 경우도 같다).
- **메시징이 꺼져 있으면(`MESSAGING_ENABLED=false`) 작업은 등록만 되고 실행되지 않는다** — 신호는 outbox 에 쌓이고, 켜면 쌓인 것부터 나간다. 워커를 켠 인스턴스가 메시징이 꺼진 채 뜨면 WARN 을 남긴다.
  실행 신호 리스너는 만들지 않는다. 작업을 돌리는 환경(local 포함)은 `MESSAGING_ENABLED=true` 와 토픽(`docker/kafka/topics.txt`)이 필요하다.
- **등록** — 같은 job 컨텍스트의 진입(어드민 재등록)은 `JobRegisterUseCase` 에 `JobRegisterCommand` 를, 다른 컨텍스트의 기능 Facade 는
  `JobRegistrationProcessor.register(JobRequest)`(domain 입력 값)를 부른다 (§3 허용 목록 4).
  정기 플래너는 같은 컨텍스트의 Processor 라 `JobRegistrationProcessor` 를 바로 부른다(Processor 가 inbound port 를 부르지 않는다).
- **핸들러 계약** — `JobHandler { type(); options(); execute(JobExecution) }`. 각 기능 컨텍스트의 `*JobProcessor` 가 구현하고 빈으로 두면 엔진이 종류(`[A-Z0-9_]{1,64}`)로 찾는다
  (domain 인터페이스 구현 — §3 허용 목록 4).
  - **트랜잭션 밖에서 실행한다.** 핸들러 안의 DB 쓰기는 **다른 빈**의 `@Transactional` 메서드(자기 컨텍스트의 다른 Processor)로 한다 —
    핸들러 Processor 가 자기 `@Transactional` 메서드를 직접 부르면(self-invocation) 프록시를 거치지 않아 트랜잭션이 걸리지 않는다 (§3 Facade).
  - **시도(attempt) 단위로 멱등이어야 한다.** lease 를 잃은 옛 실행과 회수한 새 실행이 잠시 겹칠 수 있다 — 결과를 쓸 때 업서트 키를 작업 아이디 · 슬롯으로 잡는다.
    엔진은 끝낼 때 다시 읽어 아직 이 워커의 이 시도인지 확인하고, 아니면 늦게 끝난 결과를 버린다.
    lease 판정은 각 인스턴스의 시계로 한다 — 인스턴스 시계는 NTP 로 맞춘다(어긋난 만큼 회수가 일찍 · 늦게 일어난다).
  - 오래 걸리면 `checkpoint(percent, message)` 를 부른다 — 진행률을 남기고, 취소 요청 · lease 상실 · 실행 시간 초과면 멈춤 신호(`JobCancelledException`)를 받는다.
  - 실패는 `JobExecutionException(errorCode, retryable)` 로 던진다. 예상 밖 예외는 재시도할 수 있는 `HANDLER_ERROR` 다(메시지는 저장하지 않는다).
  - **payload · 결과 · 오류 · 진행 문구에 개인정보 원문을 두지 않는다.** payload 는 식별자 · 날짜 · 설정값만 담고, 핸들러가 실행 시점에 원본 서비스에서 읽는다.
  - 다른 서비스 데이터는 internal API 로만 읽고 쓴다(§7). 대상 서비스는 허용 호출자에 `SERVICE_operations-service` 를 더한다.
- **lease · heartbeat** — 가져갈 때 `lease_expires_at = now + lease`(기본 5m), 전용 스케줄러가 heartbeat(기본 1m, lease 의 1/3 이하)마다 연장한다.
  오프셋은 가져간 직후 커밋되므로 실행 중 크래시는 Kafka 가 모른다 — **lease 회수는 시간 기준 만료 판정(`JobLeaseExpiryScheduler`, `operations.jobs.lease-sweep.*`) 하나가 맡는다.**
  모든 인스턴스가 돌고(분산 락 없음) lease 가 끝난 RUNNING 을 `(lease_expires_at, id)` keyset 으로 읽어 건마다 트랜잭션으로 `Job.expireLease` 한다 — 시도가 남았으면 QUEUED + 실행 신호,
  상한이면 FAILED(`LEASE_EXPIRED`), 취소 요청이 있었으면 CANCELLED. 두 인스턴스가 같은 행을 판정하면 `@Version` 이 하나만 통과시킨다.
  heartbeat 예약이 거절되면 실행하지 않고 돌려놓는다.
  실행 시간 상한(기본 30m)을 넘으면 heartbeat 가 그 자리에서 FAILED(`RUN_TIMEOUT`, 재시도 없음)로 끝내고 멈춤 신호를 준다 —
  checkpoint 를 부르지 않는 핸들러도 작업은 끝나지만, 실행 스레드는 핸들러가 돌아올 때까지 칸을 쥔다.
- **재시도** — 재시도할 수 있는 실패는 지수 백오프(기본 30s × 4^(n−1), 상한 30m, jitter 0.2) 뒤 다시 QUEUED, 시도 상한(기본 3)이면 FAILED 다.
- **멱등 등록** — `dedup_key` 가 있으면 활성(QUEUED · RUNNING) 동안 유니크 키 `(type, active_dedup_key)` 가 같은 종류 · 같은 키의 중복 등록을 막고 기존 작업을 돌려준다.
  종류가 다르면 같은 키여도 막지 않는다. 끝나면 같은 키로 다시 등록할 수 있다.
  동시 등록에서 유니크 키에 지면(`JOB_DEDUP_CONFLICT`) 그 트랜잭션은 이미 롤백 대상이다 — 기능 트랜잭션 안에서 등록하면 이 예외를 잡아 이어 가지 말고 요청 전체를 다시 시도한다.
- **취소는 협조적이다** — QUEUED 는 바로 CANCELLED, RUNNING 은 플래그만 세우고 핸들러가 다음 checkpoint 에서 멈춘다. 플래그를 무시하는 핸들러는 실행 시간 상한까지 돈다.
- **종료 이벤트** — 끝난 상태(SUCCEEDED · FAILED · CANCELLED)를 저장하는 트랜잭션 안에서 `JobFinishedEvent`(domain)를 한 번 낸다.
  알림 센터(#213)의 `adapter/in/event` 가 받는다 — 다른 컨텍스트 domain 만 읽고 자기 UseCase 만 부른다 (§3 허용 목록 5).
  같은 트랜잭션에서 알림 행을 만들고, 커밋 뒤에 그 어드민의 SSE 연결로 민다. 요청한 어드민이 없으면(시스템 등록) 알림을 만들지 않는다.
- **실행 스레드** — 전용 `jobWorkerTaskExecutor`(core = max = `worker.concurrency`, 큐 0)와 heartbeat 전용 `jobLeaseTaskScheduler` 를 둔다. `@Async` 는 쓰지 않는다.
  두 빈은 `@Bean(defaultCandidate = false)` 다 — 일반 빈으로 두면 Boot 가 `applicationTaskExecutor` · `taskScheduler` 를 만들지 않는다.
  워커 아이디(`{앱}:{호스트}:{pid}:{난수}`)는 `JobWorkerIdentity` 빈 하나로 만든다.
- **graceful shutdown** — 워커 lifecycle(단계 `Integer.MAX_VALUE`)이 새 신호를 받지 않게 하고(받은 신호는 nack), 실행 중인 시도를 `shutdown-grace` 까지 기다린 뒤,
  남은 시도를 QUEUED 로 돌려놓고 실행 신호를 다시 낸다(시도 번호를 되돌린다). 실행기 · 스케줄러는 그 뒤 단계(`Integer.MAX_VALUE - 1`), outbox 릴레이는 `Integer.MAX_VALUE - 2`,
  Kafka 리스너 컨테이너는 그보다 늦게 멈춘다 — 그래서 nack 한 신호 · 다시 낸 신호는 `shutdown-grace` 와 리밸런스 뒤에 다른 인스턴스로 넘어간다.
  남은 실행 스레드는 더 기다리지 않고 interrupt 한다. 강제 종료면 lease 만료가 대신한다 — 컨테이너 종료 유예는 `shutdown-grace` 보다 길게 둔다.
- 설정은 `operations.jobs.*`(`JobProperties`)다. `operations.jobs.worker.enabled=false` 면 실행 신호 리스너를 만들지 않는다(API 전용 인스턴스 — 등록 · 만료 판정만 한다).
  결과 JSON 상한은 최대 1MiB 다.
- **정기 작업** (#212) — `operations.jobs.planner.schedules` 의 일 · 주 · 월 시각에 맞춰 `JobRegistrationProcessor` 로 등록한다.
  단일 실행 락은 ShedLock(`operations-job-planner`, 테이블 `operations.shedlock` — outbox 릴레이와 같은 `LockProvider` 빈 · 테이블)이다.
  슬롯 멱등 키는 `planner:{주기}:{존}:{로컬 시각}` 이고, 활성 작업만이 아니라 같은 키로 등록된 적이 있으면(`dedup_key`, `idx_job_type_dedup_key`) 다시 만들지 않는다.
  엔진의 활성 멱등(끝나면 같은 키를 다시 쓸 수 있음)과는 다르다. 요청한 어드민은 없다(시스템 등록 — 알림을 만들지 않는다).
  목록이 비어 있으면 락을 잡지 않는다. `catch-up`(기본 32일)보다 오래된 슬롯은 건너뛰고, 그 안의 가장 최근 슬롯만 등록한다. 결과 파일은 아직 없다.
  - 중복 방어는 락이 1차, `dedup_key` exists 가 2차다. 락의 `lock-at-most` 가 지나 두 인스턴스가 겹친 창에서는 활성 유니크 키만 막는다(진 쪽은 `JOB_DEDUP_CONFLICT` 로 건너뛴다).
  - 같은 종류 · 주기 · 시각(요일 · 일 · 존 포함)에는 스케줄 하나다 — payload 만 다른 스케줄은 기동에서 실패한다. 핸들러가 없는 종류도 기동에서 실패한다.
  - 처음 배포하거나 시각을 바꾸면 `catch-up` 안의 직전 슬롯 1건이 바로 등록된다. 존 표기를 바꾸면(`Asia/Seoul` → `+09:00`) 키가 달라져 직전 슬롯이 한 번 더 등록될 수 있다.
  - 끝난 작업 행을 정리하는 작업이 생기면 보존 기간을 `catch-up` 이상으로 둔다 — 짧으면 지난 슬롯의 키가 사라져 다시 등록된다.
