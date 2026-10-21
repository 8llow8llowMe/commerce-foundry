# Service Inventory

> 서비스별 책임 · 상태 · 의존의 요약이다. 서비스 책임이 바뀌면 이 문서를 같은 PR 에서 갱신한다.
> 포트는 확정했다(#3 · #36). 바꾸면 이 표와 모듈의 `application.yml`, 서비스의 `*ApplicationTests`, `local-run-guide.md` §5 를 같이 고친다.

## 1. 실행 모듈

| 모듈 | 포트 | 상태 | 책임 |
|------|------|------|------|
| `cloud/service-discovery` | 8761 | 구성됨 (#3) | Eureka 서버 |
| `cloud/api-gateway` | 8000 | 라우팅·CORS 구성됨 (#3), local·dev 헬스·Swagger 통합 (#36), JWT 검증·ADMIN 확인·로그아웃 차단 목록 조회 (#4) | 라우팅(`api-design-guide.md` §1-2), JWT 검증(전역 보안 체인 — `security-guide.md` §7), 로그아웃 차단 목록 조회(Redis, 어드민 경로만 fail-closed), `/api/v1/admin/**` ADMIN 확인, CORS, local·dev 헬스 체크·Swagger UI |
| `service/auth-service` | 8081 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 회원가입 · 내 정보 · 로그인 · 토큰 재발급 · 로그아웃 (#4) · 배송지 주소록 (#7) · 관리자 계정 (#6) | 회원가입·로그인, JWT 발급·재발급·로그아웃, 카카오·네이버 소셜, 관리자 계정, 배송지 주소록 |
| `service/product-service` | 8082 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 카탈로그 조회 (#8) · 재고 (#10) · 어드민 카테고리 관리 (#9) | 카테고리, 상품, 옵션·SKU, 재고(선점/확정/해제/결제 후 복원), 상품 이미지, 다국어 상품정보, 통화별 가격 |
| `service/order-service` | 8083 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 장바구니 (#12) · 주문서 미리보기 · 주문 생성 (#13) | 장바구니, 주문서 금액 계산, 주문, 주문 상태, 클레임(취소/반품) |
| `service/payment-service` | 8084 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) | 결제 준비·승인·취소, 웹훅, UNKNOWN 재조회, 주문 동기화 재전송 |
| `service/delivery-service` | 8085 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 국내 배송비 정책 (#11) · 해외 배송비 (#22) | 배송비 정책(국내/해외), 송장, 배송 상태 추적 |

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
관리자 비밀번호 변경 · 관리자 추가 API · 영속 감사 테이블은 없다.
"카탈로그 조회" 는 고객 카테고리 트리 · 상품 목록 · 상품 상세(`/api/v1/categories` · `/api/v1/products/**`)와 서비스 간 SKU 벌크 조회(`/internal/v1/skus`)가 있는 상태다. "재고" 는 SKU 재고 · 어드민 재고 조회 · 조정 · 조정 이력(`/api/v1/admin/stocks/**`, #10 · #86) · 서비스 간 재고 선점 · 확정 · 해제(`/internal/v1/stock-reservations/**`) · 결제 후 취소 · 반품 재고 복원(`/internal/v1/stock-restorations`, 클레임당 1회, #85) · 선점 만료 스케줄러가 있는 상태다 (`order-flow-guide.md` §3).
"어드민 카테고리 관리" 는 숨김 포함 트리 조회 · 생성 · 수정(이동 포함) · 삭제(`/api/v1/admin/categories/**`)가 있는 상태다 — 깊이 3단계 · 순환 금지, 하위나 연결 상품이 있으면 삭제 409 (`api-design-guide.md` §2-5). 어드민 상품 쓰기 · 이미지 · 목록은 #9 의 다음 PR 이다.
SKU 는 해외 배송비 · 통관용 배송 무게 · HS 코드(둘 다 nullable)를 가지고 서비스 간 SKU 벌크 조회가 그대로 내린다 — 해외 주문 거절은 order-service 몫이다 (#80, `order-flow-guide.md` §2-2).
"국내 배송비 정책" 은 고객 정책 안내(`/api/v1/shipping-fees/policy`) · 어드민 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/domestic`) · 서비스 간 배송비 견적(`/internal/v1/shipping-fees/quotes`)이 있는 상태다.
"해외 배송비" 는 고객 해외 정책 안내(`/api/v1/shipping-fees/international-policy`) · 어드민 해외 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/international`)가 있고, 같은 견적 API 가 KR 외 국가를 국가 그룹 · 무게 구간으로 계산하는 상태다. `store.features.international-shipping` 이 꺼지면(기본값) 고객 안내는 null, KR 외 견적은 `DELIVERY_002` 다(본문 검증 `118` · `119` · `120` 이 플래그 확인보다 먼저다). 견적 무게는 SKU 무게(#80) × 수량 합을 order-service 가 보내고(#13), 통관 신고 금액은 송장 · 출고(#18)다.
"장바구니" 는 회원 장바구니(`/api/v1/carts/me/**` — 조회 · 담기 · 수량 변경 · 삭제 · 로컬 장바구니 병합)가 있고, 가격 · 판매 상태를 product-service SKU 벌크 조회(Feign, 서비스 토큰 · 서킷)로 매번 읽는 상태다. 정책은 `order-flow-guide.md` §2-1.
"주문 생성" 은 주문서 금액 미리보기(`POST /api/v1/orders/quotes`) · 주문 생성(`POST /api/v1/orders`, `Idempotency-Key` 필수)이 있고, SKU 조회 → 배송비 견적 → 재고 선점 → 저장 순서로
주문을 결제 대기(PENDING_PAYMENT)로 만드는 상태다(새 주문 201 · 같은 키 재생 200). 테이블은 `orders.orders` · `order_shipping_address` · `order_item` · `order_item_custom_option` · `order_status_history` · `order_request`(멱등 요청 기록 · 처리 중 lease) 다.
결제 완료 · 장바구니 줄 제거는 #14, 만료 스윕은 #96, 주문 조회는 #16 이다 (`order-flow-guide.md` §2-4).

## 2. 서비스 간 호출 (동기, Feign)

| 호출하는 쪽 | 대상 | 용도 |
|-------------|------|------|
| order-service | product-service | SKU 가격·판매 상태·무게·HS 코드 벌크 조회, 재고 선점·확정·해제·결제 후 복원. 주문 생성(#13)은 선점만 부르고 실패해도 해제하지 않는다(해제는 만료 · 취소 #96 · 클레임, 확정은 #14). 선점 거절 `PRODUCT_021 · 022 · 026 · 023` 을 `ORDER_016 · 003 · 018 · 017(500)` 으로 번역하고(다시 보내지 않는다) 나머지 실패는 503 `ORDER_002` |
| order-service | delivery-service | 배송비 견적(`POST /internal/v1/shipping-fees/quotes` — 주문서 미리보기 · 주문 생성, #13). 업무 거절 `DELIVERY_001/002/003/011` 만 `ORDER_012~015` 로 번역하고 나머지 실패는 503 `ORDER_011`. 해외 주문에 무게 · HS 미입력 SKU 가 있으면 실제 견적 대신 같은 엔드포인트로 배송 가능 국가 확인용 견적(총 무게 1g)을 부른다 (`order-flow-guide.md` §2-3) |
| order-service | payment-service | 클레임 환불 실행 |
| payment-service | order-service | 결제 준비 시 주문 금액 조회, 결제 완료 알림 |
| delivery-service | order-service | 송장 등록 시 주문 상태 `SHIPPING` 전이, 배송 완료 반영, 해외 출고 통관 품목(주문 항목 스냅숏) 조회 (#18) |

순환 호출(order ↔ payment, order ↔ delivery)이 있다. **같은 요청 안에서 왕복하지 않게** 설계한다 —
A 가 B 를 부르는 처리 안에서 B 가 다시 A 를 부르면 타임아웃이 겹친다.

호출은 모두 서비스 토큰을 싣고(`InternalFeignClientConfiguration`), 대상 internal 핸들러는 이 표의 호출하는 쪽을 허용 호출자로 선언한다
(`@PreAuthorize("hasAuthority('SERVICE_order-service')")`, `security-guide.md` §9-4). 호출 관계가 바뀌면 이 표와 허용 목록을 같이 고친다.

## 3. core 모듈 사용처

| core | 사용 서비스 |
|------|-------------|
| `common-core` | 전부 |
| `persistence-core` | auth · product · order · payment · delivery |
| `redis-core` | auth(토큰·소셜 state) · api-gateway(로그아웃 차단 목록 읽기 — #4 적용). order 는 쓰지 않는다 — 장바구니(#12) · 주문 멱등 기록(#13)은 PostgreSQL `orders` 스키마다 |
| `security-core` | 서비스 5종(서블릿 Resource Server — #41 적용, 서비스 간 인증 — #51 적용, 테스트는 픽스처의 키 · 토큰 · 인가 스모크) · auth(발급 — #4) · api-gateway(리액티브 검증기 · 오류 코드 · 차단 목록 키 — #4 적용) |
| `storage-core` | product(상품 이미지 — 모듈만 있다(#9 PR-A). 연결은 이미지 업로드 발급 · 첨부(#9 PR-F)에서 한다) |
| `shared-commerce` | product · order · payment · delivery · auth(배송지 국내 국가 판정 — #144) |

## 4. 데이터 저장소

- PostgreSQL 한 인스턴스에 **서비스별 스키마**를 둔다 (`auth`, `product`, `orders`, `payment`, `delivery`).
  다른 서비스 스키마를 직접 조회하지 않는다 — 필요한 데이터는 내부 API 로 받는다.
- 운영 규모가 커지면 인스턴스를 나눌 수 있게 스키마 간 FK·조인을 만들지 않는다.
- 스키마 관리: 마이그레이션 도구 없음 — local · dev 는 `ddl-auto: update`, 운영은 `none` 이고 사용자가 DDL 을 직접 적용한다
  (`coding-conventions.md` §9-8).
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
| Testcontainers | 2.0.5 (Boot BOM) | 리포지토리 테스트의 PostgreSQL 17. Docker 가 없으면 로컬은 건너뛰고 CI 는 실패한다 (`bootstrap-conventions.md`) |
| AWS SDK for Java v2 | 2.55.11 (storage-core 만 · `s3` + `url-connection-client`) | Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정. `implementation` 이라 서비스는 SDK 타입을 보지 못한다. 딸려 오는 `apache5-client` · `netty-nio-client` 는 뺀다 (Boot BOM 이 HttpClient 5 를 낮춰 고정한다) |
| S3 호환 테스트 서버 | MinIO RELEASE.2025-07-23 (`bitnamilegacy/minio:2025.7.23-debian-12-r5`, digest 고정) | storage-core 왕복 테스트(CI 전용). MinIO 공식 이미지 · 바이너리 배포가 끝나 Bitnami 가 마지막으로 빌드한 이미지를 고정한다(갱신 없음). 태그는 다시 밀어 넣을 수 있어 멀티 아키텍처 index digest 로 고정하고 태그는 주석으로 남긴다 — 이미지를 바꾸면 테스트의 `MINIO_IMAGE` 상수와 이 행을 같이 고친다. Testcontainers MinIO 모듈은 공식 이미지 기동 방식이라 쓰지 않고 `GenericContainer` 로 띄운다 |
| PostgreSQL / Redis (로컬) | 17 / 7.4 | `docker-compose-local.yml` |

## 5. 결정 대기

결정됨: **비회원 주문은 v1 에서 지원하지 않는다** (#13, `api-design-guide.md` §5). 주문서 미리보기 · 주문 생성은 로그인 회원만이다.

| 항목 | 선택지 | 결정할 이슈 |
|------|--------|-------------|
| 서비스 간 비동기 메시징 | 없음(동기 + 재전송) / RabbitMQ / Kafka | 결제 연동 이후 |
| USD 가격 | SKU 별 통화 가격표 / 환율 환산 | 해외 판매 |
| 배포 환경 | 단일 VM + docker compose / 관리형 컨테이너 | 인프라 |
| 시크릿 주입 방식 | Spring Cloud Vault / Vault Agent → 환경변수 (저장소는 **Vault 로 확정**) | #32 |
| 서비스 간 인증 2단계 | 서비스별 RS256 키 / 플랫폼 mTLS — 1단계(공용 HS256, #51)의 호출자 "선언" 을 "증명" 으로 바꾼다 (`security-guide.md` §9-9) | 배포 환경 결정 후 |
