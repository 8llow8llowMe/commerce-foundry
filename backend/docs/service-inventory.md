# Service Inventory

> 서비스별 책임 · 상태 · 의존의 요약이다. 서비스 책임이 바뀌면 이 문서를 같은 PR 에서 갱신한다.
> 포트는 확정했다(#3 · #36). 바꾸면 이 표와 모듈의 `application.yml`, 서비스의 `*ApplicationTests`, `local-run-guide.md` §5 를 같이 고친다.

## 1. 실행 모듈

payment-service의 #308 C3a-2는 주문 미회신 지연 건수·최대 나이·거절 잔존·관측 실패 지표를 제공한다.
세 Gauge는 독립 읽기이며 기존 #616 A4a API와 별도다. 생산 회신 소비나 게이트 활성화는 포함하지 않는다(`payment-guide.md` §6-2).

payment-service C3b는 `OrderPaymentReplyEventUseCase`의 원천/로컬 대조·payment/inbox 원자 기록과 커밋 후 비PG 정책 훅을 제공한다.
listener/실제 PG 취소/게이트 활성화는 후속이며 FE·HTTP 계약은 바뀌지 않는다. 신규 시험의 실제 실행 결과는 main/CI 검증 대기다.

payment-service #591은 기본 OFF 재대조와 DB 거절 재시도를 제공한다. payment.shedlock의 독립 이름과 두 lane 영속 cursor로
제한된 ID 순회를 수행하고 C3b 원자 기록을 재사용한다. 정책은 여전히 비PG no-op이며 복원 완료를 추정하지 않는다.
운영 추가 DDL·seed·lease와 처리 예산·실행 대기 시험은 `payment-guide.md` §6-3을 따른다. operations-service는 주문 결제 회신 DLT 두 토픽을 기본 OFF 관리자 이상 알림에 포함한다(`anomaly-notifications.md`).

| 모듈 | 포트 | 상태 | 책임 |
|------|------|------|------|
| `cloud/service-discovery` | 8761 | 구성됨 (#3) | Eureka 서버 |
| `cloud/api-gateway` | 8000 | 라우팅·CORS 구성됨 (#3), local·dev 헬스·Swagger 통합 (#36), JWT 검증·ADMIN 확인·로그아웃 차단 목록 조회 (#4) | 라우팅(`api-design-guide.md` §1-2), JWT 검증(전역 보안 체인 — `security-guide.md` §7), 로그아웃 차단 목록 조회(Redis, 어드민 경로만 fail-closed), `/api/v1/admin/**` ADMIN 확인, CORS, local·dev 헬스 체크·Swagger UI |
| `service/auth-service` | 8081 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 회원가입 · 내 정보 · 로그인 · 토큰 재발급 · 로그아웃 (#4) · 배송지 주소록 (#7) · 관리자 계정 (#6) · 로그인 · 가입 시도 제한 (#66) | 회원가입·로그인, JWT 발급·재발급·로그아웃, 카카오·네이버 소셜, 관리자 계정, 배송지 주소록 |
| `service/product-service` | 8082 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 카탈로그 조회 (#8) · 재고 (#10) · 어드민 카테고리 관리 (#9) · 어드민 상품 목록 · 상세 (#9) · 메인 전시 (#63) · 상품 사이즈표 (#216) · 룩 — 어드민 관리 · 고객 조회 (#62) · 주문 만료 · 취소 이벤트 → 선점 해제 컨슈머 (#309) · SKU 원가 이력 어드민 (#361 PR-A) · 시점 원가 internal 조회 (#361 PR-B) · 운영자 온보딩 상품 사실 (#402) · AI 상품 초안 — 저장 · 조회 · 거절 (#359 PR-1, 생성은 operations 작업 후속) · 어드민 쓰기 감사 — 카테고리 · 사이즈표 (#163 PR-B) | 카테고리, 상품, 옵션·SKU, 재고(선점/확정/해제/결제 후 복원 · 주문 만료 · 취소 이벤트 소비), 상품 이미지, 다국어 상품정보, 통화별 가격, 메인 전시 섹션, 사이즈표(템플릿 · 상품별 치수), 룩(Shop the Look), SKU 원가 이력(매입 · 제작 원가 · 포장 변동비, append-only · 어드민 전용, 서비스 간에는 operations-service 의 시점 원가 조회 `POST /internal/v1/sku-costs/query` 만), 운영자 온보딩 상품 사실(`GET /internal/v1/onboarding-facts/product-registration`, 허용 호출자 operations-service) |
| `service/order-service` | 8083 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 장바구니 (#12) · 주문 스냅숏 기반 (#13 C1) · 멱등 요청 기반 (#13 C2a) · 재고 선점 (#13 C2b) · CUSTOMER 주문 생성/멱등 재생 API (#13 C3) · 미결제 만료 스윕/transactional outbox (#96) · 출고 · 배송 완료 반영 internal (#18 PR-A) · 고객 부분 반품 접수/멱등/수량 예약 (#16) · 어드민 클레임 목록/상세 조회 (#17) | 장바구니, 주문서 금액 계산, 주문, 주문 상태, 클레임(취소/반품) |
| `service/payment-service` | 8084 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 결제 준비 · 승인 · 조회(#14) · 결제 승인 이벤트 `payment-approved` transactional outbox (#308 PR-A1) · 회신 도메인·inbox 저장소 기반 (#308 C3a-1, 생산 소비 후속) · 어드민 결제 목록 조회 (#15 읽기 범위) | 결제 준비·승인·취소, 웹훅, UNKNOWN 재조회, 결제 승인 → 주문 반영 이벤트 · 주문 회신 수신(#308) |
| `service/delivery-service` | 8085 | 골격 (#36) · Resource Server (#41) · 서비스 간 인증 (#51) · 국내 배송비 정책 (#11) · 해외 배송비 (#22) · 국내 배송비 정책 교체 이력 (#69) · 운영자 온보딩 사실 internal (#357 PR-B1) · 송장 등록 · 출고 반영 (#18 PR-B1) · 배송 상태 변경 · 배송 완료 반영 · 어드민 송장 조회 · 고객 배송 조회 (#18 PR-B2) | 배송비 정책(국내/해외), 송장, 배송 상태 추적, 운영자 온보딩의 국내 배송비 정책 저장 사실(`GET /internal/v1/onboarding-facts/shipping-policy`, 허용 호출자 operations-service) |
| `service/operations-service` | 8086 | 골격 · Resource Server · 서비스 간 인증 (#212 PR-A) · 작업 엔진 — 등록 · 가져가기 · 워커 · 재시도 · lease (#212 PR-B) · 어드민 작업 목록 · 상세 · 취소 (#212 PR-C) · Kafka 실행 신호 컨슈머 · outbox · lease 만료 판정 (#307) · 어드민 알림 센터(#213) · 운영자 온보딩 투어 진행 (#357 PR-A) · 온보딩 업무 완료 tasks — 배송 (#357 PR-B2) · 상품 (#402) 사실 Feign 조회 · AI 사용량 원장 · 월 한도 (#215 PR-B · #403) · ai-core 연결 — 계량 인터셉터 · 원장 정리 스케줄러 (#435) | 백그라운드 작업(등록 · 실행 · 재시도 · 진행률 · 결과 · 취소), 정기 작업, 어드민 작업 API(`/api/v1/admin/jobs/**`), 어드민 알림 센터(`/api/v1/admin/notifications/**`, #213), 운영자별 온보딩 안내 투어 진행(`/api/v1/admin/onboarding/**`, #357 — 실제 업무 완료 판정 `tasks` 는 담당 서비스 사실로만, 배송 정책 저장은 PR-B2 · 상품 등록은 PR-C). 전에 후순위로 두었던 batch · notification 을 흡수한다 (`architecture-guide.md` §1) |
| `service/support-service` | 8087 | 골격 · Resource Server · 서비스 간 인증 (#470 PR-A) · 지식 문서 버전/게시 영속성 (#469 PR-A) · 지식 ADMIN 관리 API/쓰기 감사 (#469 PR-B) · 현재 게시 지식 내부 키워드 검색 (#469 PR-C) · 비회원 세션 자격 증명 영속성 (#470 PR-B1a) · 회원/비회원 대화 생성·소유 조회 기반 (#470 PR-B1b) · 고객 메시지 저장·멱등·순번 (#470 PR-B1c) · 기존 대화/메시지 고객 REST 및 필수 게이트웨이 support-write 제한 (#470 PR-B2a, 발급·생성·WS 후속) | 고객 상담 — 대화(`conversations`, 회원 · 비회원) · AI 상담 응답 · 상담원 이관 · 어드민 상담 큐 · 실시간 전달(WebSocket, #470-C), 상담 지식 문서/불변 게시 버전과 ADMIN 관리 API(`/api/v1/admin/support/knowledge`), 정확한 언어·유효기간·카테고리의 검색 근거(`support-knowledge-guide.md`). 고객 `/api/v1/support/**` · 어드민 `/api/v1/admin/support/**`. 설계 정본은 이슈 #470 "설계 확정 (2026-11-02)" 댓글 — 장기 WS 연결 · 고객 공개 트래픽을 operations 작업 엔진과 다른 JVM 으로 격리한다 |

product-service는 전시 · 룩 공용 이미지 업로드 발급/확정도 맡는다(#508, `domainlayer/mediaupload`).
`/api/v1/admin/displays/image-uploads`(`CATALOG_MANAGE`)에서 storage-core를 통해 직접 PUT 서명과 검증된 공개 복사를 제공한다.
기존 전시 · 룩 저장은 공개 URL 계약을 유지한다. DB/참조 원장/파일 GC는 추가하지 않으며 보관과 운영 한계는
[display-media-upload-guide.md](display-media-upload-guide.md)를 따른다.

"골격" 은 실행·Eureka 등록·헬스 체크·Swagger 그룹까지 갖추고 도메인 코드는 없는 상태다 (`bootstrap-conventions.md`).
"Resource Server" 는 JWT 검증 · `/api/v1/admin/**` ADMIN 규칙 · 보안 오류 봉투가 걸려 있고 인가 스모크가 도는 상태다 (`security-guide.md` §5).
"서비스 간 인증" 은 `/internal/**` 이 서비스 토큰(1단계, 공용 HS256 비밀)으로만 열리고 인가 스모크가 그것을 보는 상태다 (`security-guide.md` §9).
"회원가입 · 내 정보" 는 고객 회원가입(`POST /api/v1/auth/sign-up`)과 내 정보 조회 · 수정(`/api/v1/members/me`)이 있는 상태다 (`api-design-guide.md` §2-4).
테이블은 `auth.member`(고객 · 관리자 한 테이블, `role` CUSTOMER / ADMIN — #4 결정 4)와 `auth.member_agreement`(동의 이력, 추가만 한다)다.
"로그인 · 토큰" 은 고객 로그인 · 토큰 재발급 · 로그아웃(`/api/v1/auth/login` · `/token/reissue` · `/logout`)이 있고
refresh 토큰을 Redis 에 지문으로 두고 회전 · 재사용 감지(30초 유예)하는 상태다 (`security-guide.md` §2-3). 로그인은 최초 로그인부터 30일이 절대 상한이다(#67).
"로그인 · 가입 시도 제한" 은 고객 로그인 · 관리자 로그인 · 회원가입을 같은 이메일(HMAC) 단위 고정 창(Redis)으로 세어 넘으면 429 `AUTH_040` · `AUTH_041` + `Retry-After` 를 내는 상태다
(기본 켬, 로그인 fail-closed · 가입 fail-open — `security-guide.md` §12).
"배송지 주소록" 은 내 배송지 목록 · 단건 · 추가 · 전체 교체 · 삭제 · 기본 지정(`/api/v1/members/me/shipping-addresses/**`)이 있는 상태다 (`api-design-guide.md` §2-4).
테이블은 `auth.shipping_address`(회원당 상한 20, 기본 배송지는 `default_member_id` 유니크 키로 최대 하나)다.
주문은 배송지 값을 요청에 실어 스냅숏으로 저장하므로 배송지 internal API 는 두지 않는다 — 필요해지면 #13 에서 연다 (`order-flow-guide.md` §4).
"관리자 계정" 은 초기 관리자 시드(환경변수 `ADMIN_SEED_*`, 기동 시 멱등) · 관리자 로그인(`POST /api/v1/auth/admin/login`, access 만) ·
어드민 회원 조회(`GET /api/v1/admin/members`, 마스킹 · `PageResponse`) · 관리자 감사 로그(`admin.audit` 로거 한 줄)가 있는 상태다 (`security-guide.md` §11).
가게 운영자 계정 추가 · 비활성화 · 본인 비밀번호 변경이 있다 (`POST /api/v1/admin/admins`, `PATCH /api/v1/admin/admins/{memberId}`, `PATCH /api/v1/admin/me/password`, #193).
운영자 목록 · 역할 변경 · 사용 여부 변경이 있다 (`GET /api/v1/admin/admins`, `PATCH /api/v1/admin/admins/{memberId}/role`, `PATCH /api/v1/admin/admins/{memberId}/status`, #236 PR-B1).
역할을 지정한 추가 · 역할 변경은 `auth.operator-roles.issuance-enabled`(기본 꺼짐)가 켜져야 받는다. 본문 없는 `PATCH /admins/{memberId}` 는 deprecated 이고 #237 뒤 지운다.
고객 이메일은 관리자로 올리지 않고, 마지막 활성 대표 관리자(ADMIN · OWNER)는 끄지 못한다. 관리자 감사는 로그 한 줄과 `auth.admin_audit_log` 에 함께 남고 `GET /api/v1/admin/audit-logs` 로 본다 (#192).
응답에는 마스킹한 `actorDisplayName` · `targetDisplayName` 을 붙인다 (#322). 감사 로그를 거를 관리자 선택용 경량 목록은 두지 않는다.
관리자 본인 OTP 상태 · 등록 시작 · 확인 · 해제 · 복구 코드 재발급이 있다 (`/api/v1/admin/me/otp/**`, #238 PR-A2). OTP 를 등록한 관리자는 로그인 2단계(`POST /api/v1/auth/admin/login/otp`, TOTP 또는 복구 코드)를 거쳐야 토큰을 받는다 (#238 PR-B). 동료 OTP 복구는 행위자의 재인증 뒤 대상 자격을 새 ID·비밀·복구 코드로 교체한다 (`POST /api/v1/admin/admins/{memberId}/otp/recovery`, PR-C).
서비스 간 회원 표시 이름은 `GET /internal/v1/member-display-names`(마스킹된 이름만, 허용 호출자 delivery-service)다.
보관 기간(기본 2년, 하한 1년)이 지난 행은 auth-service 스케줄러가 Snowflake 아이디 범위로 지운다 (#286, `security-guide.md` §11-4).
"카탈로그 조회" 는 고객 카테고리 트리 · 상품 목록 · 상품 상세(`/api/v1/categories` · `/api/v1/products/**`)와 서비스 간 SKU 벌크 조회(`/internal/v1/skus`)가 있는 상태다.
상품은 프리오더 여부 · 발송 예정일(`product.preorder` · `expected_ship_date`)을, 상품 미디어는 영상(`ImageType.VIDEO` + `poster_url`)을 가진다 — 플래그 `store.features.preorder` ·
`store.features.product-video`(기본 꺼짐, local · dev 켬)로 응답에서 숨긴다 (#61, `api-design-guide.md` §2-7). 입력은 어드민 상품 쓰기(#9)에서 연다. "재고" 는 SKU 재고 · 어드민 재고 조회 · 조정 · 조정 이력(`/api/v1/admin/stocks/**`, #10 · #86) · 서비스 간 재고 선점 · 확정 · 해제(`/internal/v1/stock-reservations/**`) · 결제 후 취소 · 반품 재고 복원(`/internal/v1/stock-restorations`, 클레임당 1회, #85) · 선점 만료 스케줄러가 있는 상태다 (`order-flow-guide.md` §3).
"어드민 카테고리 관리" 는 숨김 포함 트리 조회 · 생성 · 수정(이동 포함) · 삭제(`/api/v1/admin/categories/**`)가 있는 상태다 — 깊이 3단계 · 순환 금지, 하위나 연결 상품이 있으면 삭제 409 (`api-design-guide.md` §2-5).
"어드민 상품 목록" 은 숨김 포함 상품 페이지 목록(`GET /api/v1/admin/products` — 상품명 · 카테고리(하위 포함) · 판매 상태 · 노출 · 재고 반영 판매 가능 상태 조건,
최신순 · 이름순)이 있는 상태다 (`api-design-guide.md` §2-6). 상품 soft delete · 옵션 은퇴 · 낙관적 락 버전 · 등록자 컬럼과 그 조회 규칙(삭제 상품은 고객 · 어드민 목록에서 빠지고
서비스 간 SKU 조회에는 판매 불가로 남는다)이 있다. 상품 삭제 API(`DELETE /api/v1/admin/products/{productId}`)는 `AdminAuthority.CATALOG_MANAGE` 이며 이미 삭제된 상품도 200이다.
읽은 버전과 merge 시점이 충돌하면 409 `PRODUCT_010`이며 자식·재고·기존 선점은 유지한다. 옵션 은퇴 쓰기는 `POST /option-retirements`(#9 PR-C2)다 (`api-design-guide.md` §2-13). 어드민 상품 상세(`GET /api/v1/admin/products/{productId}`)는 숨김 · 은퇴 옵션 · 판매 종료 SKU 와 저장된 설정을 포함하며 삭제 상품은 404 다.
재고 · 원가는 기존 별도 API 로 조회한다. 상품 등록(E1)·이미지 업로드 발급(F1)·등록 시 첨부(F2)·정지 이미지 전체 편집(F3)·상품 삭제와 기본 정보/상태·카테고리·가격 수정이 구현됐다. 정지 이미지 편집은 VIDEO와 기존 객체 속성을 보존하고 신규 복사만 트랜잭션 밖에서 수행한다. 기존 옵션 이름·노출 순서 편집은 `/options`, 기존 SKU 필드 편집은 `/skus`, 맞춤 제작 설정(사용 여부 · 안내 · 소요일 · 입력 항목) 전체 교체는 `/custom-order` API로 제공하고 등록 본문도 `customOrder` 를 받는다(#9 PR-B). 옵션 값 · 새 조합 SKU 추가는 `POST /skus`(#9 PR-C1)이고, 옵션 · 값 은퇴는 `POST /option-retirements`(#9 PR-C2)다. 새 옵션 그룹 추가 · 은퇴 되돌리기는 v1 에 없다.
옵션 그룹·값의 ko/en 이름은 기존 `/options`에서 편집한다(#20). 영문명 미지정/null은 보존하고 빈 값은 삭제하며 어드민 상세는 저장된 두 언어를 반환한다.
고객 상세의 옵션 이름은 기존 locale 정책으로 en을 선택하고 누락 번역은 필드별 한국어로 대체한다. 공개 원문 필드·SKU 조합·재고와 internal 한국어 이름은 유지한다.
SKU 는 해외 배송비 · 통관용 배송 무게 · HS 코드(둘 다 nullable)를 가지고 서비스 간 SKU 벌크 조회가 그대로 내린다 — 해외 주문 거절은 order-service 몫이다 (#80, `order-flow-guide.md` §2-2).
"국내 배송비 정책" 은 고객 정책 안내(`/api/v1/shipping-fees/policy`) · 어드민 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/domestic`) · 서비스 간 배송비 견적(`/internal/v1/shipping-fees/quotes`)이 있는 상태다.
"국내 배송비 정책 교체 이력" 은 교체가 성공할 때 같은 트랜잭션에서 교체 전 · 후 값 · 관리자 · 시각을 `delivery.shipping_policy_history`(+ `shipping_policy_history_area`)에 남기고
어드민이 최신순 페이지로 조회(`GET /api/v1/admin/shipping-policies/domestic/histories`)하는 상태다 (`api-design-guide.md` §6-2).
항목의 `adminDisplayName` 은 auth-service 표시 이름 벌크 조회로 페이지당 한 번 붙인다 (#322). 해외 정책 이력은 아직 없다.
"해외 배송비" 는 고객 해외 정책 안내(`/api/v1/shipping-fees/international-policy`) · 어드민 해외 정책 조회·전체 교체(`/api/v1/admin/shipping-policies/international`)가 있고, 같은 견적 API 가 KR 외 국가를 국가 그룹 · 무게 구간으로 계산하는 상태다. `store.features.international-shipping` 이 꺼지면(기본값) 고객 안내는 null, KR 외 견적은 `DELIVERY_002` 다(본문 검증 `118` · `119` · `120` 이 플래그 확인보다 먼저다). 견적 무게는 SKU 무게(#80) × 수량 합을 order-service 가 보내고(#13), 통관 신고 금액은 송장 · 출고(#18)다.
"송장" 은 어드민 송장 일괄 등록(`POST /api/v1/admin/deliveries`, 1~20건 · 요청 처리 마감 8초 · 건별 결과)과 order-service 출고 반영(Feign, 서킷 `order-service`)이 있는 상태다 (#18 PR-B1) —
`delivery.shipment`(주문당 1건 · `order_synced` · `completion_pending` · `previous_status`) · `delivery.shipment_status_history`(사유 포함) 에 남는다.
배송 상태 변경(`PATCH /api/v1/admin/deliveries/{deliveryId}/status` — `IN_TRANSIT` 은 delivery 안, `DELIVERED` 는 order 배송 완료 반영) · 어드민 송장 상세(`GET .../{deliveryId}`) ·
목록(`GET /api/v1/admin/deliveries?orderId=&status=&synced=`) · 고객 배송 조회(`GET /api/v1/deliveries?orderId=`, 송장 회원 스냅숏으로 로컬 소유 판정)가 있다 (#18 PR-B2). 택배사 추적은 후속 이슈다
(`order-flow-guide.md` §4, `api-design-guide.md` §2-19).
"장바구니" 는 회원 장바구니(`/api/v1/carts/me/**` — 조회 · 담기 · 수량 변경 · 삭제 · 로컬 장바구니 병합)가 있고, 가격 · 판매 상태를 product-service SKU 벌크 조회(Feign, 서비스 토큰 · 서킷)로 매번 읽는 상태다. 정책은 `order-flow-guide.md` §2-1.

## 2. 서비스 간 호출 (동기, Feign)

| 호출하는 쪽 | 대상 | 용도 |
|-------------|------|------|
| order-service | product-service | SKU 가격·판매 상태·무게·HS 코드 벌크 조회, 재고 단일 선점 (#13 C2b). C3는 같은 orderId로 복구하며 자동 재시도·보상 해제하지 않는다. 만료는 `order-expired` outbox → Kafka → product 컨슈머로 선점을 해제한다 (#96·#309). 결제 반영 전 선점 확정(`POST /internal/v1/stock-reservations/{id}/confirmation`) · 해제 · 만료 뒤 같은 orderId 재선점 → 즉시 확정(#308 PR-B1 `OrderStockProcessor#confirmOrReReserve`, 컨슈머 연결은 PR-B2, `order-flow-guide.md` §3-4). 결제 후 복원 호출은 #308 PR-C · #16 |
| order-service | delivery-service | 배송비 견적(`POST /internal/v1/shipping-fees/quotes` — 주문서 미리보기 · 주문 생성, #13). 업무 거절 `DELIVERY_001/002/003/011` 만 `ORDER_012~015` 로 번역하고 나머지 실패는 503 `ORDER_011`. 해외 주문에 무게 · HS 미입력 SKU 가 있으면 실제 견적 대신 같은 엔드포인트로 배송 가능 국가 확인용 견적(총 무게 1g)을 부른다 (`order-flow-guide.md` §2-3) |
| order-service | payment-service | 결제 승인 재조회(`GET /internal/v1/payments/{paymentId}`, #308) — `payment-approved` 이벤트는 서명이 없어 컨슈머가 상태 · 금액 · 통화 · 주문 · 회원을 다시 확인한다. 받는 API 는 #308 PR-A2, 호출 구현은 PR-B1(`PaymentClient` · `PaymentQueryPort`, 서킷 `payment-service`, 404 `PAYMENT_001` = 없는 결제, `payment-guide.md` §6). 결제 후 취소 클레임 환불 실행(`POST /internal/v1/payments/{paymentId}/cancellations`, `Idempotency-Key` = 클레임 아이디, #16 PR-2) — 고객 취소 요청 직후와 재시도 스케줄러(`ClaimCancellationRetryScheduler`)가 클레임에 저장된 결제 아이디 · 환불액만 보낸다. `paymentCancelClient` · 서킷 `payment-cancellation`(조회와 분리) · read timeout 10초. 200 → 환불 확정, `PAYMENT_016/017/019/001` · 400 `PAYMENT_1xx` → 종결 거절(REFUND_FAILED), 그 밖(`PAYMENT_018` · 타임아웃 · 5xx · 서킷 · 인증 설정) → 미확정 재시도(`payment-guide.md` §8, `order-flow-guide.md` §5) |
| payment-service | order-service | 결제 준비 시 주문 요약 조회(`GET /internal/v1/orders/{orderId}/payment-summary?memberId=`, #14 — 서버 총액 · 주문명 · 상태 · 결제 기한, `order-flow-guide.md` §2-5). 저장된 최초 반영 결과 조회 원천 API(`GET /internal/v1/order-payments/{paymentId}`, #308 C1a; 호출자 연동은 후속). 결제 완료 반영은 `payment-approved` 이벤트 → order 컨슈머(#308, `payment-guide.md` §6) |
| delivery-service | order-service | 송장 등록 시 출고 반영(`POST /internal/v1/orders/{orderId}/shipment` — `PREPARING → SHIPPING`, `shipped_at`), 배송 완료 반영(`POST /internal/v1/orders/{orderId}/delivery-completion` — `SHIPPING → DELIVERED`, `delivered_at`). 둘 다 멱등이고 응답 `memberId` · `countryCode` 를 송장에 스냅숏한다 (#18, `order-flow-guide.md` §4 · `api-design-guide.md` §2-18). 404 · 409 `ORDER_020` · 400 시각이면 등록은 대기 송장을 지우고 배송 완료는 송장을 직전 상태로 되돌린다. `ORDER_025` · 타임아웃 · 5xx 면 남겨(등록 `order_synced=false` · 완료 `completion_pending=true`) 같은 요청 재시도로 수렴한다(자동 재전송 없음). order 쪽 받는 API 는 #18 PR-A, delivery 출고 호출은 PR-B1(`OrderShipmentClient` · 서킷 `order-service` · read timeout 3초), 배송 완료 호출은 PR-B2(같은 클라이언트 · 서킷). order → delivery 역방향 호출은 두지 않는다 — 어드민 수동 배송 완료로 갈라진 송장은 운영자가 배송 완료를 보내 맞춘다. 해외 출고 통관 품목(주문 항목 스냅숏) 조회는 후속 이슈 |
| delivery-service | auth-service | 회원 표시 이름 벌크 조회(`GET /internal/v1/member-display-names`, #322). 배송비 정책 이력 한 페이지의 관리자 아이디를 한 번에 보낸다. 응답은 마스킹된 이름만이고 원문은 없다. 실패는 503 `DELIVERY_013` |
| operations-service | auth-service | 시스템 알림 수신자 ID keyset 조회(`GET /internal/v1/admin-notification-recipients`, #477 A1). auth가 활성 대표 관리자(ADMIN · OWNER, #236) 판정을 소유하고 ID만 숫자 오름차순으로 최대 100개 반환한다. 허용 호출자는 operations 하나이며 호출 어댑터 · 이상 알림 생성은 후속 범위다 |
| operations-service | delivery-service | 운영자 온보딩 업무 사실 — 국내 배송비 정책 저장 여부 · 운영자별 최초 저장 시각(`GET /internal/v1/onboarding-facts/shipping-policy`, #357). memberId 는 operations 가 access 토큰으로 확인한 운영자. 호출 구현 #357 PR-B2 (`ShippingPolicyFactClient`, 서킷 `delivery-service`) — 장애면 그 업무만 `UNAVAILABLE`, 조회는 200 |
| operations-service | product-service | 운영자 온보딩 상품 등록 사실 — 구매 가능 상품 존재 여부 · 운영자별 미삭제 상품 최초 등록 시각(`GET /internal/v1/onboarding-facts/product-registration`, #402). `ProductRegistrationFactClient`, 서킷 `product-service` — 장애면 상품 업무만 `UNAVAILABLE`, 조회는 200. 등록자 기록을 쓰는 상품 등록 API는 #9 의존 |
| operations-service | product-service | 시점 원가 일괄 조회(`POST /internal/v1/sku-costs/query`, #361 PR-B) — 공헌이익 집계가 주문 항목의 (SKU, 주문 시각) 쌍을 100개씩 보내 그 시각의 유형별 원가를 받는다. 없는 SKU 는 빠지고 원가 미입력은 빈 costs 다. 호출 구현은 #362 |
| operations-service | product-service | 품절 임박 커서 조회(`GET /internal/v1/stocks/low`, #229 PR-2). operations-service만 허용하는 조회 기반이며 소비자 클라이언트·스케줄러·알림 연결은 후속 |
| operations-service | auth-service | 마케팅 동의 `POST /internal/v1/marketing-consents/query` (#230). PR-B의 ADMIN 수신자 미리보기에서 실제 Feign 호출(`campaignConsentClient`, 서킷 `auth-service`). 최대100개 ID, 이름·연락처 없음. 실패·응답 계약 위반은503. 실제 발송 연결은 후속. |

operations-service의 정상 승인 집계 읽기 권한(#550): payment-service가
`GET /internal/v1/payments/approval-summary?from=&to=`를 operations 전용으로 제공한다. 승인일 기준 최대 31일의 통화 · 수단별 건수와 원금을
단일 질의로 반환하며 식별자 · 결제키는 없다. 취소 뒤에도 최초 승인액을 유지하고 늦은 승인은 제외한다. 호출 구현 · 리포트 연결은 #220 후속이다.

operations-service의 payment outbox 원천 관측 권한(#477 A3a): payment-service의
`GET /internal/v1/outbox-observations/ready`는 `SERVICE_operations-service`만 허용한다. 미래 예약을 제외한 발행 가능 미발행 건수·최소시각을
단일 집계로 제공하며, operations 호출자·주기 수집·알림·해소 연결은 후속이다. [관측 계약](outbox-observation-guide.md)을 따른다.

`GET /internal/v1/payment-observations/anomalies`는 operations 전용 결제 이상 후보 여섯 버킷을 단일 집계로 제공한다.
payment 소유 Processor의 domain 관측값을 paymentobservation Facade가 조합한다. operations notification 컨텍스트는 A4b-1에서
엄격 원천 소비와 다섯 사건의 불변 순수 정책을 소유한다. A4b-2는 `operations.payment_anomaly_alert_state` 다섯 행과
최대 1,000명·5,000건 알림의 원자 저장 Processor를 추가한다. 결제·주문 업무 상태와 PG 처리는 변경하지 않는다.
E3a는 잠금 후 필수 HTTP timing 검증과 미연결 단일 worker·대기 16배치 전달 기반을 추가한다. 생성 시 worker는 0개다.
자동 수집·활성화·커밋 후 알림 전달 연결과 PG 생산 중 포화 검증은 E3b 후속이며 [관측 계약](payment-observation-guide.md)과 [저장 경계](anomaly-notifications.md)를 따른다.

순환 호출(order ↔ payment, order ↔ delivery)이 있다. **같은 요청 안에서 왕복하지 않게** 설계한다 —
A 가 B 를 부르는 처리 안에서 B 가 다시 A 를 부르면 타임아웃이 겹친다.

호출은 모두 서비스 토큰을 싣고(`InternalFeignClientConfiguration`), 대상 internal 핸들러는 이 표의 호출하는 쪽을 허용 호출자로 선언한다
(`@PreAuthorize("hasAuthority('SERVICE_order-service')")`, `security-guide.md` §9-4). 호출 관계가 바뀌면 이 표와 허용 목록을 같이 고친다.

## 3. core 모듈 사용처

| core | 사용 서비스 |
|------|-------------|
| `ai-core` | operations (#435 — `AiUsageMeteringInterceptor` 가 모든 호출을 사용량 원장 · 월 한도에 예약 · 정산한다. 기본 꺼짐 `AI_ENABLED`. 기능은 작업 엔진(#212) 핸들러가 부른다 — 첫 기능 #359). 대화형(고객 상담)은 support 요청 경로 — 원격 계량 #222-A |
| `common-core` | 전부 |
| `commerce-support` | product · order · payment · delivery — Money 응답·금액 컬럼 변환. common-core와 shared-commerce를 조합하며 서비스별 DTO·엔티티는 포함하지 않는다 (#68) |
| `messaging-core` | product(inbox — `order-expired` · `order-canceled` 컨슈머, #309) · order(outbox — `order-expired` 발행, #96) · operations(outbox · 컨슈머 — 작업 실행 신호, #307) · payment(outbox — `payment-approved` 발행, #308 PR-A1; inbox 저장소 기반 — C3a-1, 생산 회신 소비 후속). 테스트는 픽스처의 가짜 outbox · inbox · `OutboxStoreContract` |
| `persistence-core` | auth · product · order · payment · delivery · operations · support |
| `redis-core` | auth(토큰·소셜 state · 로그인 · 가입 시도 계수기 — #66) · api-gateway(로그아웃 차단 목록 읽기 — #4 적용) · support(연결만 — WS 1회용 티켓 · 대화 이벤트 pub/sub 은 #470-C). order 는 쓰지 않는다 — 장바구니(#12) · 주문 멱등 기록(#13)은 PostgreSQL `orders` 스키마다 |
| `security-core` | 서비스 전부(서블릿 Resource Server — #41 적용, 서비스 간 인증 — #51 적용, 테스트는 픽스처의 키 · 토큰 · 인가 스모크) · auth(발급 — #4) · api-gateway(리액티브 검증기 · 오류 코드 · 차단 목록 키 — #4 적용) |
| `storage-core` | product(상품 이미지 — 모듈만 있다(#9 PR-A). 연결은 이미지 업로드 발급 · 첨부(#9 PR-F)에서 한다), operations(비공개 작업 결과 파일 저장·서명 다운로드·만료 삭제, #212 PR-E) |
| `shared-commerce` | product · order · payment · delivery · auth(배송지 국내 국가 판정 — #144) |

## 4. 데이터 저장소

- PostgreSQL 한 database(`commerce_foundry`)에 **서비스별 스키마**를 둔다 (`auth`, `product`, `orders`, `payment`, `delivery`, `operations`, `support`).
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
| OpenAI Java SDK | 4.96.0 (ai-core, `openai-java`) | Responses API, explicit provider selection, shared concurrency and metering (#468). See [ai-provider-guide.md](ai-provider-guide.md). |
| Anthropic Java SDK | 2.34.0 (ai-core 만 · `anthropic-java`) | Claude API 공식 클라이언트. Boot BOM 이 관리하지 않아 루트 `ext` 에서 고정. `implementation` 이라 서비스는 SDK 타입을 보지 못한다. OkHttp 4.12 · Jackson 2(`com.fasterxml`)를 끌고 온다 — Jackson 3 와 패키지가 달라 섞이지 않고, Boot BOM 은 Jackson 2 를 올려(2.21.x) 맞출 뿐 낮추지 않는다. SDK 가 끄는 javax 판 `swagger-annotations` 는 빼고 같은 클래스의 `swagger-annotations-jakarta` 를 둔다 — springdoc 판과 한 모듈로 합쳐지게(둘 다 오르면 `/v3/api-docs` 가 500, #435) |
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
| USD 가격 | **관리자 수동 고정 KRW/USD 가격으로 확정**. 상품×통화 기본가·정가 + SKU×통화 추가금, 환산 없음. [정책·단계별 계획](usd-pricing-guide.md)은 #20 A이며 가격행·명시 통화·nullable SKU는 구현 전 목표다. 현재 단일 통화/internal 조회·주문 추론·KRW 결제 계약은 유지한다 | #20 B~E 구현·검증 및 USD 개방은 별도 승인 |
| 원가 통화 ↔ 판매 통화 불일치 | SKU 원가 이력은 통화를 (SKU, 유형) 첫 행으로만 고정하고 판매가 통화와 맞추지 않는다(#361). 마진 계산 때 환산 / 거절 / 표시만 중 고른다 | #362 |
| 배포 환경 | 단일 VM + docker compose / 관리형 컨테이너 | 인프라 |
| 시크릿 주입 방식 | Jenkins 배포 agent의 AppRole → 환경별 Vault KV v2 → 임시 `.env.runtime` → Compose 환경변수로 확정. 환경별 AppRole 분리는 prod 배포 전 운영 조건(`secret-management.md` §3) | #32 |
| 서비스 간 인증 2단계 | 서비스별 RS256 키 / 플랫폼 mTLS — 1단계(공용 HS256, #51)의 호출자 "선언" 을 "증명" 으로 바꾼다 (`security-guide.md` §9-9) | 배포 환경 결정 후 |

### 소셜 로그인 구현 범위 (#5)

auth-service가 고객 Kakao/Naver 소셜 인가 URL 발급과 code 교환 로그인을 제공한다
(GET /api/v1/auth/oauth/{provider}/authorize-url · POST /api/v1/auth/oauth/{provider}/login).
auth.social_identity 테이블과 auth:oauth-state:{SHA-256 지문} Redis 키를 소유한다.
회원/identity/약관 신규 저장은 원자적이며 이메일 자동 연결은 제공하지 않는다.
전체 및 제공자 플래그는 기본 false이며 활성화·Vault 키·배포 compose 규칙은 deploy-guide.md 소셜 로그인 절을 따른다.

기존 고객 계정의 소셜 연결·목록도 auth-service가 소유한다 (#355):
`GET /api/v1/members/me/social-accounts`, `POST .../{provider}/authorize-url`, `POST .../{provider}`.
CUSTOMER JWT와 기존 비밀번호 재인증으로 기존 member_id에만 연결한다. 회원·프로필·약관 변경이나 토큰 발급은 없다.
목록은 제공자 제어 코드만 반환하고 FE #356이 표시 이름·아이콘과 목적별 콜백 분기를 맡는다.
기존 LOGIN 시도 정책·HMAC 카운터를 재사용하며 guard가 꺼졌거나 Redis가 실패하면 연결 시작을 차단한다.
`auth:oauth-link-state:{SHA-256 지문}`과 LINK/member/provider/verifier 바인딩을 소유한다. 로그인 state 형식은 유지한다.
회원별 잠금·5초 DB 상한과 identity의 두 유니크 키로 제공자별 단일 연결과 경합을 보장한다.
운영 제약 추가 SQL·중복 사전 점검과 FE 계약은 `api-design-guide.md`의 #355 절을 따른다.

### 결제 outbox 운영 관측 (#477 A3b)

operations-service는 auth 활성 관리자 목록과 payment-service의 읽기 전용 A3a 관측 API(PR #548)를 수집해 발행 지연 CRITICAL·ready 대기 해소 INFO를 알린다. 신규 `operations.outbox_alert_state`는 PAYMENT 단일 소스의 사고 상태·관측 워터마크를 소유한다. 결제/주문 업무 상태·PG·downstream 건강 판정은 소유하지 않는다. 기본 비활성 플래그·배포·실패 보존·시계 제약은 [anomaly-notifications.md](anomaly-notifications.md)를 따른다. FE 스키마 변경과 새 Vault 비밀은 없다.

operations-service는 payment-service의 읽기 전용 결제 이상 후보 관측 API도 기본 비활성 주기 수집으로 읽는다(#477 E3b).
활성 관리자 전체 수신자와 다섯 사건의 상태·알림을 원자 저장하고 정상 커밋 뒤 SSE로 최선 전달한다. 결제·주문 상태를
바꾸거나 후보 소멸을 복구 완료로 판정하지 않는다. 설정·억제·실패 보존·종료 한계는
[anomaly-notifications.md](anomaly-notifications.md)의 E3b 절을 따른다. 새 공개 API·FE 계약·Vault 비밀·DDL은 없다.

### DLT 이상 징후 알림 (#477 A2)

operations-service는 기본 비활성 기능으로 기존 네 DLT를 구독하고 auth A1 internal 수신자 API의 활성 관리자에게 ANOMALY 알림을 저장한다. 결제 승인 DLT는 CRITICAL, 나머지는 WARNING이다. 토픽별 억제·파티션별 워터마크로 상태를 제한하며 업무 상태는 변경하지 않는다. 신규 operations 테이블은 `dlt_alert_state`·`dlt_partition_checkpoint`다. 배포·ACL·실패/복구·FE 계약은 [anomaly-notifications.md](anomaly-notifications.md)를 따른다.

## 쇼핑몰 공개 정보 (#566)

operations-service `storeprofile`이 배포당 단일 쇼핑몰 이름·공개 사업자·CS 정보와 낙관적 버전을 소유한다.
ADMIN 전체 교체 저장은 즉시 공개된다. 익명 공개 조회는 표시 필드만 반환하며 내부 버전·행위자는 노출하지 않는다.
Gateway 경로는 `/api/v1/store/**`·`/api/v1/admin/store/**`다. 신규 테이블은 `operations.store_profile`이며
실제 Hibernate DDL은 `service/operations-service/src/test/resources/ddl/operations-schema.sql`에 포함한다.
FE #568의 설정 화면·푸터 BUSINESS_* 이행 계약은 `api-design-guide.md` 쇼핑몰 공개 정보 절을 따른다. 새 Vault 키는 없다.

## 스토어 법적 문서 (#569)

operations-service `legaldocument`는 TERMS/PRIVACY × ko/en의 불변 초안 리비전·게시 포인터·최초 게시 기록을 소유한다.
ADMIN 편집·검토 확인 후 즉시 게시·이력·브랜드 비종속 시작 템플릿과 익명 게시본 조회를 제공한다.
AI·외부 수집·auth 동의 수집을 호출하지 않으며 새 초안은 공개 게시본을 바꾸지 않는다.
계약·운영 DDL·FE #570 영향과 #566 store route 배포 의존은 [store-legal-guide.md](store-legal-guide.md)를 따른다.

## 스토어 푸터 링크 (#597)

operations-service `storefooter`는 배포당 하나의 이중 언어 푸터 초안·게시본·이력과 외부 링크 순서를 소유한다.
`storeprofile`(#566)의 회사·CS 실정보와 `legaldocument`(#569)의 정책 본문·게시 여부를 복제하지 않는다.
관리자 저장은 공개 포인터를 바꾸지 않고 명시적 게시 후에만 공개 GET에 반영된다. 새 테이블·호스트 허용 설정,
FE #600 안전 렌더링과 운영 DDL은 [store-footer-guide.md](store-footer-guide.md)를 따른다. 새 Vault 비밀은 없다.
