# Service Inventory

> 서비스별 책임 · 상태 · 의존의 요약이다. 서비스 책임이 바뀌면 이 문서를 같은 PR 에서 갱신한다.
> 포트는 확정했다(#3 · #36). 바꾸면 이 표와 모듈의 `application.yml`, 서비스의 `*ApplicationTests`, `local-run-guide.md` §5 를 같이 고친다.

## 1. 실행 모듈

| 모듈 | 포트 | 상태 | 책임 |
|------|------|------|------|
| `cloud/service-discovery` | 8761 | 구성됨 (#3) | Eureka 서버 |
| `cloud/api-gateway` | 8000 | 라우팅·CORS 구성됨 (#3), local·dev 헬스·Swagger 통합 (#36), JWT 검증·ADMIN 확인·로그아웃 차단 목록 조회 (#4) | 라우팅(`api-design-guide.md` §1-2), JWT 검증(전역 보안 체인 — `security-guide.md` §7), 로그아웃 차단 목록 조회(Redis, 어드민 경로만 fail-closed), `/api/v1/admin/**` ADMIN 확인, CORS, local·dev 헬스 체크·Swagger UI |
| `service/auth-service` | 8081 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 회원가입 · 내 정보 · 로그인 · 토큰 재발급 · 로그아웃 (#4) · 배송지 주소록 (#7) · 관리자 계정 (#6) | 회원가입·로그인, JWT 발급·재발급·로그아웃, 카카오·네이버 소셜, 관리자 계정, 배송지 주소록 |
| `service/product-service` | 8082 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 카탈로그 조회 (#8) · 재고 (#10) · 어드민 카테고리 관리 (#9) · 어드민 상품 목록 (#9) · 메인 전시 (#63) · 상품 사이즈표 (#216) | 카테고리, 상품, 옵션·SKU, 재고(선점/확정/해제/결제 후 복원), 상품 이미지, 다국어 상품정보, 통화별 가격, 메인 전시 섹션, 사이즈표(템플릿 · 상품별 치수) |
| `service/order-service` | 8083 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 장바구니 (#12) | 장바구니, 주문서 금액 계산, 주문, 주문 상태, 클레임(취소/반품) |
| `service/payment-service` | 8084 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) | 결제 준비·승인·취소, 웹훅, UNKNOWN 재조회, 주문 동기화 재전송 |
| `service/delivery-service` | 8085 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 국내 배송비 정책 (#11) · 해외 배송비 (#22) · 국내 배송비 정책 교체 이력 (#69) | 배송비 정책(국내/해외), 송장, 배송 상태 추적 |
| `service/operations-service` | 8086 | 골격 · Resource Server · 서비스 간 인증 (#212 PR-A) · 작업 엔진 — 등록 · 가져가기 · 워커 · 재시도 · lease (#212 PR-B) · 어드민 작업 목록 · 상세 · 취소 (#212 PR-C) · Kafka 실행 신호 컨슈머 · outbox · lease 만료 판정 (#307) · 어드민 알림 센터(#213) | 백그라운드 작업(등록 · 실행 · 재시도 · 진행률 · 결과 · 취소), 정기 작업, 어드민 작업 API(`/api/v1/admin/jobs/**`), 어드민 알림 센터(`/api/v1/admin/notifications/**`, #213). 전에 후순위로 두었던 batch · notification 을 흡수한다 (`architecture-guide.md` §1) |

"골격" 은 실행·Eureka 등록·헬스 체크·Swagger 그룹까지 갖추고 도메인 코드는 없는 상태다 (`bootstrap-conventions.md`).
"Resource Server" 는 JWT 검증 · `/api/v1/admin/**` ADMIN 규칙 · 보안 오류 봉투가 걸려 있고 인가 스모크가 도는 상태다 (`security-guide.md` §5).
"서비스 간 인증" 은 `/internal/**` 이 서비스 토큰(1단계, 공용 HS256 비밀)으로만 열리고 인가 스모크가 그것을 보는 상태다 (`security-guide.md` §9).
"회원가입 · 내 정보" 는 고객 회원가입(`POST /api/v1/auth/sign-up`)과 내 정보 조회 · 수정(`/api/v1/members/me`)이 있는 상태다 (`api-design-guide.md` §2-4).
테이블은 `auth.member`(고객 · 관리자 한 테이블, `role` CUSTOMER / ADMIN — #4 결정 4)와 `auth.member_agreement`(동의 이력, 추가만 한다)다.
"로그인 · 토큰" 은 고객 로그인 · 토큰 재발급 · 로그아웃(`/api/v1/auth/login` · `/token/reissue` · `/logout`)이 있고
refresh 토큰을 Redis 에 지문으로 두고 회전 · 재사용 감지(30초 유예)하는 상태다 (`security-guide.md` §2-3). 로그인은 최초 로그인부터 30일이 절대 상한이다(#67). 요청 제한은 #66 이다.
"배송지 주소록" 은 내 배송지 목록 · 단건 · 추가 · 전체 교체 · 삭제 · 기본 지정(`/api/v1/members/me/shipping-addresses/**`)이 있는 상태다 (`api-design-guide.md` §2-4).
테이블은 `auth.shipping_address`(회원당 상한 20, 기본 배송지는 `default_member_id` 유니크 키로 최대 하나)다.
주문은 배송지 값을 요청에 실어 스냅숏으로 저장하므로 배송지 internal API 는 두지 않는다 — 필요해지면 #13 에서 연다 (`order-flow-guide.md` §4).
"관리자 계정" 은 초기 관리자 시드(환경변수 `ADMIN_SEED_*`, 기동 시 멱등) · 관리자 로그인(`POST /api/v1/auth/admin/login`, access 만) ·
어드민 회원 조회(`GET /api/v1/admin/members`, 마스킹 · `PageResponse`) · 관리자 감사 로그(`admin.audit` 로거 한 줄)가 있는 상태다 (`security-guide.md` §11).
관리자 비밀번호 변경 · 관리자 추가 API 는 없다. 관리자 감사는 로그 한 줄과 `auth.admin_audit_log` 에 함께 남고 `GET /api/v1/admin/audit-logs` 로 본다 (#192).
응답에는 마스킹한 `actorDisplayName` · `targetDisplayName` 을 붙인다 (#322). 감사 로그를 거를 관리자 선택용 경량 목록은 두지 않는다.
서비스 간 회원 표시 이름은 `GET /internal/v1/member-display-names`(마스킹된 이름만, 허용 호출자 delivery-service)다.
보관 기간(기본 2년, 하한 1년)이 지난 행은 auth-service 스케줄러가 Snowflake 아이디 범위로 지운다 (#286, `security-guide.md` §11-4).
"카탈로그 조회" 는 고객 카테고리 트리 · 상품 목록 · 상품 상세(`/api/v1/categories` · `/api/v1/products/**`)와 서비스 간 SKU 벌크 조회(`/internal/v1/skus`)가 있는 상태다.
상품은 프리오더 여부 · 발송 예정일(`product.preorder` · `expected_ship_date`)을, 상품 미디어는 영상(`ImageType.VIDEO` + `poster_url`)을 가진다 — 플래그 `store.features.preorder` ·
`store.features.product-video`(기본 꺼짐, local · dev 켬)로 응답에서 숨긴다 (#61, `api-design-guide.md` §2-7). 입력은 어드민 상품 쓰기(#9)에서 연다. "재고" 는 SKU 재고 · 어드민 재고 조회 · 조정 · 조정 이력(`/api/v1/admin/stocks/**`, #10 · #86) · 서비스 간 재고 선점 · 확정 · 해제(`/internal/v1/stock-reservations/**`) · 결제 후 취소 · 반품 재고 복원(`/internal/v1/stock-restorations`, 클레임당 1회, #85) · 선점 만료 스케줄러가 있는 상태다 (`order-flow-guide.md` §3).
"어드민 카테고리 관리" 는 숨김 포함 트리 조회 · 생성 · 수정(이동 포함) · 삭제(`/api/v1/admin/categories/**`)가 있는 상태다 — 깊이 3단계 · 순환 금지, 하위나 연결 상품이 있으면 삭제 409 (`api-design-guide.md` §2-5).
"어드민 상품 목록" 은 숨김 포함 상품 페이지 목록(`GET /api/v1/admin/products` — 상품명 · 카테고리(하위 포함) · 판매 상태 · 노출 · 재고 반영 판매 가능 상태 조건,
최신순 · 이름순)이 있는 상태다 (`api-design-guide.md` §2-6). 어드민 상품 상세 · 쓰기 · 상태 변경 · 삭제 · 이미지는 #9 의 다음 PR 이다.
SKU 는 해외 배송비 · 통관용 배송 무게 · HS 코드(둘 다 nullable)를 가지고 서비스 간 SKU 벌크 조회가 그대로 내린다 — 해외 주문 거절은 order-service 몫이다 (#80, `order-flow-guide.md` §2-2).
"국내 배송비 정책" 은 고객 정책 안내(`/api/v1/shipping-fees/policy`) · 어드민 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/domestic`) · 서비스 간 배송비 견적(`/internal/v1/shipping-fees/quotes`)이 있는 상태다.
"국내 배송비 정책 교체 이력" 은 교체가 성공할 때 같은 트랜잭션에서 교체 전 · 후 값 · 관리자 · 시각을 `delivery.shipping_policy_history`(+ `shipping_policy_history_area`)에 남기고
어드민이 최신순 페이지로 조회(`GET /api/v1/admin/shipping-policies/domestic/histories`)하는 상태다 (`api-design-guide.md` §6-2).
항목의 `adminDisplayName` 은 auth-service 표시 이름 벌크 조회로 페이지당 한 번 붙인다 (#322). 해외 정책 이력은 아직 없다.
"해외 배송비" 는 고객 해외 정책 안내(`/api/v1/shipping-fees/international-policy`) · 어드민 해외 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/international`)가 있고, 같은 견적 API 가 KR 외 국가를 국가 그룹 · 무게 구간으로 계산하는 상태다. `store.features.international-shipping` 이 꺼지면(기본값) 고객 안내는 null, KR 외 견적은 `DELIVERY_002` 다(본문 검증 `118` · `119` · `120` 이 플래그 확인보다 먼저다). 견적 무게는 SKU 무게(#80) × 수량 합을 order-service 가 보내고(#13), 통관 신고 금액은 송장 · 출고(#18)다.
"장바구니" 는 회원 장바구니(`/api/v1/carts/me/**` — 조회 · 담기 · 수량 변경 · 삭제 · 로컬 장바구니 병합)가 있고, 가격 · 판매 상태를 product-service SKU 벌크 조회(Feign, 서비스 토큰 · 서킷)로 매번 읽는 상태다. 정책은 `order-flow-guide.md` §2-1.

## 2. 서비스 간 호출 (동기, Feign)

| 호출하는 쪽 | 대상 | 용도 |
|-------------|------|------|
| order-service | product-service | SKU 가격·판매 상태·무게·HS 코드 벌크 조회, 재고 선점·확정·해제·결제 후 복원 |
| order-service | delivery-service | 배송비 견적(`POST /internal/v1/shipping-fees/quotes` — 주문서 미리보기 · 주문 생성, #13). 업무 거절 `DELIVERY_001/002/003/011` 만 `ORDER_012~015` 로 번역하고 나머지 실패는 503 `ORDER_011`. 해외 주문에 무게 · HS 미입력 SKU 가 있으면 실제 견적 대신 같은 엔드포인트로 배송 가능 국가 확인용 견적(총 무게 1g)을 부른다 (`order-flow-guide.md` §2-3) |
| order-service | payment-service | 클레임 환불 실행 |
| payment-service | order-service | 결제 준비 시 주문 금액 조회, 결제 완료 알림 |
| delivery-service | order-service | 송장 등록 시 주문 상태 `SHIPPING` 전이, 배송 완료 반영, 해외 출고 통관 품목(주문 항목 스냅숏) 조회 (#18) |
| delivery-service | auth-service | 회원 표시 이름 벌크 조회(`GET /internal/v1/member-display-names`, #322). 배송비 정책 이력 한 페이지의 관리자 아이디를 한 번에 보낸다. 응답은 마스킹된 이름만이고 원문은 없다. 실패는 503 `DELIVERY_013` |

순환 호출(order ↔ payment, order ↔ delivery)이 있다. **같은 요청 안에서 왕복하지 않게** 설계한다 —
A 가 B 를 부르는 처리 안에서 B 가 다시 A 를 부르면 타임아웃이 겹친다.

호출은 모두 서비스 토큰을 싣고(`InternalFeignClientConfiguration`), 대상 internal 핸들러는 이 표의 호출하는 쪽을 허용 호출자로 선언한다
(`@PreAuthorize("hasAuthority('SERVICE_order-service')")`, `security-guide.md` §9-4). 호출 관계가 바뀌면 이 표와 허용 목록을 같이 고친다.

## 3. core 모듈 사용처

| core | 사용 서비스 |
|------|-------------|
| `ai-core` | operations(예정 — AI 호출은 작업 엔진(#212) 핸들러에서 돈다. 모듈만 있다(#215 PR-A). 사용량 원장 · 월 한도 · 연결은 이후 PR) |
| `common-core` | 전부 |
| `persistence-core` | auth · product · order · payment · delivery · operations |
| `redis-core` | auth(토큰·소셜 state) · api-gateway(로그아웃 차단 목록 읽기 — #4 적용). order 는 쓰지 않는다 — 장바구니(#12) · 주문 멱등 기록(#13)은 PostgreSQL `orders` 스키마다 |
| `security-core` | 서비스 전부(서블릿 Resource Server — #41 적용, 서비스 간 인증 — #51 적용, 테스트는 픽스처의 키 · 토큰 · 인가 스모크) · auth(발급 — #4) · api-gateway(리액티브 검증기 · 오류 코드 · 차단 목록 키 — #4 적용) |
| `storage-core` | product(상품 이미지 — 모듈만 있다(#9 PR-A). 연결은 이미지 업로드 발급 · 첨부(#9 PR-F)에서 한다) |
| `shared-commerce` | product · order · payment · delivery · auth(배송지 국내 국가 판정 — #144) |

## 4. 데이터 저장소

- PostgreSQL 한 database(`commerce_foundry`)에 **서비스별 스키마**를 둔다 (`auth`, `product`, `orders`, `payment`, `delivery`, `operations`).
  다른 서비스 스키마를 직접 조회하지 않는다 — 필요한 데이터는 내부 API 로 받는다.
- 운영 규모가 커지면 인스턴스를 나눌 수 있게 스키마 간 FK·조인을 만들지 않는다.
- 스키마 관리: 마이그레이션 도구 없음 — local · dev 는 `ddl-auto: update`, 운영은 `none` 이고 사용자가 DDL 을 직접 적용한다
  (`coding-conventions.md` §9-8).
- 환경을 어떻게 나누고 DB 계정을 어떻게 두는지는 §4-2 다.
- 오브젝트 스토리지(상품 이미지): **사용자 인프라의 S3 호환 MinIO** 를 path-style 로 쓴다 (#9 D2). 코드는 `storage-core` 의 S3 호환 클라이언트 하나라
  다른 S3 호환 제공자로 바꿔도 endpoint · region 설정만 바뀐다. 접속 값은 Vault, 버킷 CORS · 수명 규칙 설정은 사용자 몫이다 (`bootstrap-conventions.md` Storage).

### 4-1. 기술 버전

정본은 `backend/build.gradle` · `backend/gradle/wrapper/gradle-wrapper.properties` 다. 올릴 때 이 표도 고친다.

| 항목 | 버전 | 비고 |
|------|------|------|
| Java | 21 | Gradle toolchain |
| Gradle | 9.7.1 | Wrapper |
| Spring Boot | 4.0.8 | Spring Framework 7 · Hibernate 7.2 · **Jackson 3** (`tools.jackson`) |
| Spring Cloud | 2025.1.3 | Boot 4.0 대응 GA 트레인. Boot 4.1 은 트레인 2026.0 이 GA 된 뒤 함께 올린다 |
| Spring Security | 7.0.7 (Boot BOM) | JOSE 는 Nimbus JOSE + JWT 10.4. jjwt 를 쓰지 않는다 (`security-guide.md`) |
| springdoc-openapi | 3.0.3 | Boot 4 대응 라인 |
| QueryDSL | OpenFeign 포크 7.7 | `com.querydsl` 원본은 2024년 이후 릴리스가 없다 |
| MapStruct | 1.6.3 (+ lombok-mapstruct-binding 0.2.0) | Entity ↔ Domain 매퍼 (`coding-conventions.md` §4). Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정 |
| ArchUnit | 1.4.1 (core 라이브러리만) | 컨트롤러 인가 · Feign 전역 설정 정적 규칙 (`security-guide.md` §9-11). JUnit 엔진(archunit-junit5)은 쓰지 않는다. Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정 |
| WireMock | 3.13.2 (`wiremock-standalone`, 테스트 전용) | 외부 API(토스) 어댑터 테스트. Jetty · Jackson 을 shaded 로 품어 Boot 4 · Jackson 3 와 섞이지 않는다. Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정 |
| Testcontainers | 2.0.5 (Boot BOM) | 리포지토리 테스트의 PostgreSQL 17. Docker 가 없으면 로컬은 건너뛰고 CI 는 실패한다 (`bootstrap-conventions.md`) |
| datasource-proxy | 1.11.1 (delivery-service `testImplementation` 만) | JDBC 왕복 · 배치 실행 횟수 측정(`coding-conventions.md` §9-6, #255). Boot BOM 이 관리하지 않아 서비스 build.gradle 에서 고정 |
| AWS SDK for Java v2 | 2.55.11 (storage-core 만 · `s3` + `url-connection-client`) | Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정. `implementation` 이라 서비스는 SDK 타입을 보지 못한다. 딸려 오는 `apache5-client` · `netty-nio-client` 는 뺀다 (Boot BOM 이 HttpClient 5 를 낮춰 고정한다) |
| S3 호환 테스트 서버 | MinIO RELEASE.2025-07-23 (`bitnamilegacy/minio:2025.7.23-debian-12-r5`, digest 고정) | storage-core 왕복 테스트(CI 전용). MinIO 공식 이미지 · 바이너리 배포가 끝나 Bitnami 가 마지막으로 빌드한 이미지를 고정한다(갱신 없음). 태그는 다시 밀어 넣을 수 있어 멀티 아키텍처 index digest 로 고정하고 태그는 주석으로 남긴다 — 이미지를 바꾸면 테스트의 `MINIO_IMAGE` 상수와 이 행을 같이 고친다. Testcontainers MinIO 모듈은 공식 이미지 기동 방식이라 쓰지 않고 `GenericContainer` 로 띄운다 |
| Anthropic Java SDK | 2.34.0 (ai-core 만 · `anthropic-java`) | Claude API 공식 클라이언트. Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정. `implementation` 이라 서비스는 SDK 타입을 보지 못한다. OkHttp 4.12 · Jackson 2(`com.fasterxml`)를 끌고 온다 — Jackson 3 와 패키지가 달라 섞이지 않고, Boot BOM 은 Jackson 2 를 올려(2.21.x) 맞출 뿐 낮추지 않는다 |
| PostgreSQL / Redis (로컬) | 17 / 7.4 | `docker-compose-local.yml` |

### 4-2. 환경별 DB 분리 (#82)

**환경은 DB 서버(인스턴스)로 나누고, database · 스키마 이름은 모든 환경에서 같게 둔다.** 환경 차이는 접속 정보(`DB_URL` · `DB_USERNAME` · `DB_PASSWORD`)로만 갈린다.
`delivery_dev` · `auth_prod` 같은 환경 접미사 스키마를 만들지 않는다.

| 환경 | DB 서버 | database · 스키마 | `ddl-auto` | 접속 정보 출처 |
|------|---------|-------------------|------------|----------------|
| local | 개발자 PC 의 `docker-compose-local.yml` PostgreSQL | `commerce_foundry` · 서비스별 스키마 (init 스크립트) | `update` | `backend/.env` (`local-run-guide.md` §2) |
| test (컨텍스트) | H2 메모리 (PostgreSQL 모드) | 서비스 스키마 하나 (`INIT=CREATE SCHEMA ...`) | `create-drop` | `application-test.yml` (비밀 아님) |
| test (리포지토리) | Testcontainers PostgreSQL 17 | DDL 스냅숏(`src/test/resources/ddl/<서비스>-schema.sql`)이 만드는 스키마 | `validate` | 컨테이너가 준다 |
| dev | **dev 전용 DB 서버** | `commerce_foundry` · 서비스별 스키마 | `update` | Vault (`kv/commerce-foundry/backend/dev/env`) |
| prod | **prod 전용 DB 서버** | `commerce_foundry` · 서비스별 스키마 | `none` | Vault (`kv/commerce-foundry/backend/prod/env`) |
| staging (도입 시) | **prod 와 같은 구성의 별도 서버** | 같음 | `none` | Vault (`.../staging/env`) |

이렇게 정한 이유:

- **스키마 이름이 코드에 고정돼 있다.** 서비스 `application.yml` 의 `hibernate.default_schema` 가 리터럴이고, 엔티티 · JPQL 은 스키마를 따로 적지 않는다.
  환경마다 스키마 이름이 다르면 yml 을 환경별로 갈라야 하고, 한 곳만 빠뜨려도 다른 환경의 스키마에 쓴다.
- **DDL 이 환경과 무관해야 한다.** DDL 스냅숏과 PR 본문의 운영 DDL(`coding-conventions.md` §9-8)은 `auth.member` 처럼 스키마를 붙여 쓴다.
  스키마 이름이 같으면 같은 스크립트를 dev · staging · prod 에 그대로 적용하고 리허설할 수 있다.
- **dev 와 prod 가 한 서버를 쓰면 안 된다.** dev 의 부하 시험 · 잘못된 쿼리가 운영을 느리게 만들고, 권한을 잘못 주면 dev 계정이 운영 데이터를 읽는다.
  dev 에는 테스트 데이터만, 운영 개인정보는 운영 서버에만 둔다.

**차선책 — 서버를 나눌 예산이 없을 때**: 같은 인스턴스 안에서 **database** 를 `commerce_dev` · `commerce_prod` 로 나눈다(스키마 이름은 그대로).
PostgreSQL 은 database 사이를 조인 · 조회할 수 없어 데이터 경계는 지켜지지만, CPU · 메모리 · 디스크 · 연결 수는 나뉘지 않는다.
임시로만 쓰고, prod 트래픽이 생기기 전에 서버를 나눈다. database 이름이 달라지는 것은 `DB_URL` 뿐이라 코드 · yml 은 그대로다.

**staging** 을 도입하면 prod 와 같은 PostgreSQL 메이저 버전 · 같은 계정 구성 · `ddl-auto: none` 으로 두고, **운영 DDL 을 prod 보다 먼저 적용해 보는 곳**으로 쓴다.
prod 데이터를 복사해 쓰지 않는다(개인정보) — 필요하면 마스킹한 데이터만 넣는다.

#### DB 계정

| 계정 | 쓰는 쪽 | 권한 |
|------|---------|------|
| 서비스별 앱 계정 (예: `auth_app`) | 서비스 하나 | **자기 스키마만** — `USAGE` 와 테이블 `SELECT` · `INSERT` · `UPDATE` · `DELETE`. 다른 서비스 스키마에는 아무 권한이 없다. prod · staging 은 DDL 권한(`CREATE` · `ALTER` · `DROP`)이 없다 |
| DDL 적용 계정 (예: `commerce_ddl`) | 사람(운영 DDL 을 적용하는 사용자) | 서비스 스키마 소유자. 앱에는 주지 않는다 |
| 슈퍼유저 | 서버 관리 | 계정 · database 생성에만. 앱 · DDL 적용에 쓰지 않는다 |

- 앱 계정을 서비스마다 나누면 **"다른 서비스 스키마를 직접 조회하지 않는다"(§4)를 권한으로 강제**한다. 코드 리뷰가 놓쳐도 DB 가 거절한다.
- dev 는 `ddl-auto: update` 라 앱 계정이 **자기 스키마에 한해** `CREATE` 권한을 가져야 한다(테이블 · 컬럼 추가). 다른 스키마 권한은 dev 에서도 주지 않는다.
- prod 앱 계정에 DDL 권한이 없으므로 `ddl-auto` 를 실수로 `update` 로 켜도 DB 가 DDL 을 거절해 스키마가 바뀌지 않는다
  (Hibernate 는 기본값 `hibernate.hbm2ddl.halt_on_error=false` 라 경고 로그만 남기고 뜬다 — 기동 로그의 DDL 실패 경고를 신호로 본다). 운영 DDL 은 DDL 적용 계정으로 사람이 넣는다.
- DDL 적용 계정이 만든 테이블에 앱 계정 권한이 붙도록 `ALTER DEFAULT PRIVILEGES` 를 같이 건다. 아래는 형식 예다 — 비밀번호는 Vault 에서 만들고 여기 적지 않는다.

  ```sql
  -- 슈퍼유저로, 서비스마다 (auth 예)
  create role auth_app login;                           -- 비밀번호는 \password 로 따로
  grant connect on database commerce_foundry to auth_app;
  grant usage on schema auth to auth_app;
  grant select, insert, update, delete on all tables in schema auth to auth_app;
  alter default privileges for role commerce_ddl in schema auth
      grant select, insert, update, delete on tables to auth_app;
  -- dev 에서만: ddl-auto update 용
  -- grant create on schema auth to auth_app;
  ```

- 식별자는 Snowflake 라 시퀀스 권한은 필요 없다. 시퀀스를 쓰는 테이블이 생기면 `usage` · `select` on sequences 를 같이 준다.
- `auth.admin_audit_log` 만 예외다 (#286, `security-guide.md` §11-4). UPDATE 는 주지 않는다. DELETE 는 보관 정리 배치가 내는 문장뿐이다.
  로그인 분리는 #258 까지 미루므로, 그 전까지 앱 로그인이 이 테이블의 INSERT · SELECT · DELETE 를 함께 가진다.
- **현재 상태 (#82 시점)**: 배포 env 의 `DB_USERNAME` · `DB_PASSWORD` 는 서비스 공통 키라(`deploy-guide.md` §4-1) 한 환경 안에서 서비스들이 계정 하나를 같이 쓴다.
  서비스별 계정은 키를 서비스별로 나누는 설정 변경(compose · `.env.example` · Vault)이 필요해 #258 에서 한다. 그 전까지 공용 계정에는 서비스 스키마 전부의 DML(dev 는 `CREATE` 포함)만 주고,
  스키마 소유자 · 슈퍼유저 권한은 주지 않는다.

#### 새 서비스 · 새 환경을 더할 때

| 무엇을 | 어디에 |
|--------|--------|
| 스키마 이름 | 서비스의 짧은 이름 하나(`delivery`). SQL 예약어면 복수형(`orders`). 모든 환경에서 같다 — `application.yml` `hibernate.default_schema`, `docker/postgres/init/01-create-schemas.sql`, 이 절과 §4 의 목록, `coding-conventions.md` §9-8 의 스키마 목록 |
| 스키마 만들기 | local 은 init 스크립트(`local-run-guide.md` §3). dev · staging · prod 는 DDL 적용 계정으로 `create schema` 를 먼저 적용한다 — JPA 는 스키마를 만들지 않는다 |
| 앱 계정 | 위 SQL 로 그 환경 DB 에 만든다. 이름은 `<스키마>_app` |
| 접속 정보 | 그 환경의 Vault secret(`kv/commerce-foundry/backend/<env>/env`)에 키 이름 그대로 넣는다(`secret-management.md` §3). 새 환경은 secret 경로를 하나 더 만든다 |

## 5. 결정 대기

| 항목 | 선택지 | 결정할 이슈 |
|------|--------|-------------|
| 서비스 간 비동기 메시징 | **Kafka 로 확정**(2026-10-25 사용자 결정, Infra 공유 클러스터) · 발행 정합성은 transactional outbox + 폴링 릴레이(CDC 안 함). 규칙은 `architecture-guide.md` §8-1, 남은 세부(dev 접속 방식 · ShedLock · 지표 노출 · 토픽 prefix 등)는 #304 설계 댓글의 결정 항목 | #304 (구현 #306 ~ #310) |
| 비회원 주문 | 지원 / 미지원 | 주문 생성 |
| USD 가격 | SKU 별 통화 가격표 / 환율 환산 | 해외 판매 |
| 배포 환경 | 단일 VM + docker compose / 관리형 컨테이너 | 인프라 |
| 시크릿 주입 방식 | Spring Cloud Vault / Vault Agent → 환경변수 (저장소는 **Vault 로 확정**) | #32 |
| 서비스 간 인증 2단계 | 서비스별 RS256 키 / 플랫폼 mTLS — 1단계(공용 HS256, #51)의 호출자 "선언" 을 "증명" 으로 바꾼다 (`security-guide.md` §9-9) | 배포 환경 결정 후 |
