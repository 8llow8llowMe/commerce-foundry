# Service Inventory

> 서비스별 책임 · 상태 · 의존의 요약이다. 서비스 책임이 바뀌면 이 문서를 같은 PR 에서 갱신한다.
> 포트 번호는 스캐폴딩 이슈에서 확정하고 이 표를 고친다.

## 1. 실행 모듈

| 모듈 | 포트(안) | 상태 | 책임 |
|------|----------|------|------|
| `cloud/service-discovery` | 8761 | 미착수 | Eureka 서버 |
| `cloud/api-gateway` | 8000 | 미착수 | 라우팅(`api-design-guide.md` §1-2), JWT 검증, `/api/v1/admin/**` ADMIN 확인, CORS |
| `service/auth-service` | 8081 | 미착수 | 회원가입·로그인, JWT 발급·재발급·로그아웃, 카카오·네이버 소셜, 관리자 계정, 배송지 주소록 |
| `service/product-service` | 8082 | 미착수 | 카테고리, 상품, 옵션·SKU, 재고(선점/확정/해제), 상품 이미지, 다국어 상품정보, 통화별 가격 |
| `service/order-service` | 8083 | 미착수 | 장바구니, 주문서 금액 계산, 주문, 주문 상태, 클레임(취소/반품) |
| `service/payment-service` | 8084 | 미착수 | 결제 준비·승인·취소, 웹훅, UNKNOWN 재조회, 주문 동기화 재전송 |
| `service/delivery-service` | 8085 | 미착수 | 배송비 정책(국내/해외), 송장, 배송 상태 추적 |

## 2. 서비스 간 호출 (동기, Feign)

| 호출하는 쪽 | 대상 | 용도 |
|-------------|------|------|
| order-service | product-service | SKU 가격·판매 상태 벌크 조회, 재고 선점·확정·해제 |
| order-service | delivery-service | 배송비 계산 |
| order-service | payment-service | 클레임 환불 실행 |
| payment-service | order-service | 결제 준비 시 주문 금액 조회, 결제 완료 알림 |
| delivery-service | order-service | 송장 등록 시 주문 상태 `SHIPPING` 전이, 배송 완료 반영 |

순환 호출(order ↔ payment, order ↔ delivery)이 있다. **같은 요청 안에서 왕복하지 않게** 설계한다 —
A 가 B 를 부르는 처리 안에서 B 가 다시 A 를 부르면 타임아웃이 겹친다.

## 3. core 모듈 사용처

| core | 사용 서비스 |
|------|-------------|
| `common-core` | 전부 |
| `persistence-core` | auth · product · order · payment · delivery |
| `redis-core` | auth(토큰·소셜 state) · order(멱등 키·장바구니 캐시 검토) · api-gateway(블랙리스트) |
| `security-core` | auth(발급) · 나머지 서비스(Resource Server) |
| `storage-core` | product(상품 이미지) |
| `shared-commerce` | product · order · payment · delivery |

## 4. 데이터 저장소

- PostgreSQL 한 인스턴스에 **서비스별 스키마**를 둔다 (`auth`, `product`, `orders`, `payment`, `delivery`).
  다른 서비스 스키마를 직접 조회하지 않는다 — 필요한 데이터는 내부 API 로 받는다.
- 운영 규모가 커지면 인스턴스를 나눌 수 있게 스키마 간 FK·조인을 만들지 않는다.

### 4-1. 기술 버전

정본은 `backend/build.gradle` · `backend/gradle/wrapper/gradle-wrapper.properties` 다. 올릴 때 이 표도 고친다.

| 항목 | 버전 | 비고 |
|------|------|------|
| Java | 21 | Gradle toolchain |
| Gradle | 9.7.1 | Wrapper |
| Spring Boot | 4.0.8 | Spring Framework 7 · Hibernate 7.2 · **Jackson 3** (`tools.jackson`) |
| Spring Cloud | 2025.1.3 | Boot 4.0 대응 GA 트레인. Boot 4.1 은 트레인 2026.0 이 GA 된 뒤 함께 올린다 |
| springdoc-openapi | 3.0.3 | Boot 4 대응 라인 |
| QueryDSL | OpenFeign 포크 7.7 | `com.querydsl` 원본은 2024년 이후 릴리스가 없다 |
| PostgreSQL / Redis (로컬) | 17 / 7.4 | `docker-compose-local.yml` |

## 5. 결정 대기

| 항목 | 선택지 | 결정할 이슈 |
|------|--------|-------------|
| 서비스 간 비동기 메시징 | 없음(동기 + 재전송) / RabbitMQ / Kafka | 결제 연동 이후 |
| 비회원 주문 | 지원 / 미지원 | 주문 생성 |
| USD 가격 | SKU 별 통화 가격표 / 환율 환산 | 해외 판매 |
| 배포 환경 | 단일 VM + docker compose / 관리형 컨테이너 | 인프라 |
| 오브젝트 스토리지 | S3 / Cloudflare R2 / NCP Object Storage | 상품 이미지 |
| 시크릿 주입 방식 | Spring Cloud Vault / Vault Agent → 환경변수 (저장소는 **Vault 로 확정**) | #32 |
