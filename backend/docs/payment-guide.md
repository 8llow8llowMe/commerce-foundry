# Payment Guide

> 결제는 실수 비용이 가장 큰 영역이다. **돈이 오갔는데 기록이 없거나, 기록은 있는데 돈이 안 오간** 상태를
> 만들지 않는 것이 이 문서의 목적이다. `payment-service` 의 정본이고 `/payment-guard` 스킬이 이 문서로 점검한다.
>
> PG 의 구체 값(제한 시간, 필드 규칙, 엔드포인트)은 **연동 이슈에서 공식 문서로 재확인**하고 이 문서를 고친다.
> 공식 문서: <https://docs.tosspayments.com>

## 1. 구성

| 결제수단 | 경로 | 통화 | 계약 |
|----------|------|------|------|
| 국내 카드 · 계좌이체 | 토스페이먼츠 | KRW | PG 기본 계약 |
| 카카오페이 · 네이버페이 | 토스페이먼츠 (간편결제) | KRW | PG 계약 + 간편결제 활성화 |
| PayPal | 토스페이먼츠 해외간편결제 | **USD** | **별도 계약** |

- PG 를 **토스페이먼츠 하나로 통합**한다. 결제사별 직접 연동은 하지 않는다 — 2인 팀이 결제사마다
  승인·취소·웹훅·정산을 따로 운영할 수 없다.
- 그래도 PG 교체 가능성은 열어 둔다. application 은 `PaymentGatewayPort` 만 알고 토스는
  `adapter/out/client/pg/toss/TossPaymentGatewayAdapter` 에만 나온다.
- 카드번호 등 결제수단 정보는 **우리 서버를 지나가지 않는다.** 토스 결제위젯/결제창이 받는다.

## 2. 키 관리

| 키 | 위치 | 노출 |
|----|------|------|
| 클라이언트 키 (`test_ck_` / `live_ck_`, 결제위젯 `test_gck_` / `live_gck_`) | 프론트 환경변수 | 공개돼도 된다 |
| 시크릿 키 (`test_sk_` / `live_sk_`, 결제위젯 `test_gsk_` / `live_gsk_`) | Vault `TOSS_SECRET_KEY` → payment-service | **절대 커밋·로그 금지** |
| 웹훅 시크릿 (사용 시) | Vault `TOSS_WEBHOOK_SECRET` → payment-service | 절대 커밋·로그 금지 |

- 키 이름·등록 절차는 `secret-management.md` 를 따른다.

- **prod 프로파일에서 `test_` 키로 기동하면 실패시킨다.** 반대로 local/dev 에 `live_` 키가 들어와도 실패시킨다.
  키가 뒤바뀐 배포는 "결제는 되는데 돈이 안 들어오는" 사고가 된다.
  - `TossPaymentsStartupGuard`(payment `*BeansConfig` 의 static `@Bean`, `RedisStartupGuard` 와 같은 방식)가 기동 전에 본다(#14).
    비었거나 플레이스홀더가 남으면 실패, 시크릿 키 형식(`(test|live)_g?sk_…`)이 아니면 실패(클라이언트 키 `ck` · `gck` 를 넣는 실수 포함).
  - **활성 프로파일이 모두 local · dev · test 일 때만 `test_` 키를 받고, 그 밖(prod · 다른 프로파일 · 프로파일 없음 · 혼합)은 `live_` 키만 받는다** — 프로파일을 빠뜨린 배포가 테스트 키로 뜨지 않게 닫힌 쪽이 기본이다.
  - 토스 Feign 타임아웃(`spring.cloud.openfeign.client.config.toss-payments.*`)이 없거나 read 가 10s(게이트웨이 응답 타임아웃) 이상이어도 실패한다.
    가드는 read 타임아웃만 본다 — DNS · TLS · DB 시간까지 더한 승인 API 전체가 10s 안이라는 보장은 아니다. 게이트웨이 504 는 FE 가 "확인 중 → 상태 조회" 로 다룬다.
  - Feign `logger-level` 이 `default` 나 `toss-payments` 어느 쪽에서든 `HEADERS` · `FULL` 이면 실패한다 — Basic 인증 헤더(시크릿 키)가 로그에 남는다.
  - `live_` 키일 때 base-url 은 정확히 `https://api.tosspayments.com` 이어야 한다 — 다른 호스트 · http 로 라이브 키가 나가지 않게 한다.
  - 위반은 모아서 한 번에 알리고 메시지에는 값이 아니라 키 이름만 싣는다. 키 본문은 영문 · 숫자 · 밑줄을 받는다(문서용 키 `test_gsk_docs_…` 포함).
  - `test` 프로파일과 `application-test.yml` 의 더미 키는 **자동 테스트 전용**이다. 로컬 기동 · 배포에 쓰지 않는다.
  - 결제위젯(`gsk`)과 API 개별 연동(`sk`) 중 무엇을 쓸지는 FE 연동에서 정한다 — 가드는 둘 다 받는다. 키 출처: <https://docs.tosspayments.com/reference/using-api/api-keys> (2026-10-21 확인).
- 계약 주체(사업자)와 키 소유는 고객사(MOKOYURI) 계정이다. 개발자 계정은 테스트 키만 다룬다.

## 3. 결제 승인 흐름 (필수)

```text
[FE] 주문서                [order-service]            [payment-service]                 [Toss]
  │ POST /orders ─────────────▶ 주문 생성(PENDING_PAYMENT)
  │                            금액 서버 계산 · 재고 선점
  │ POST /payments ─────────────────────────────────▶ 결제 준비(READY)
  │                                                    주문 금액 조회(internal) · pgOrderId 발급
  │ ◀──────────────── pgOrderId, amount, orderName ──┘
  │ 결제위젯 requestPayment(pgOrderId, amount) ─────────────────────────────────────▶ 인증
  │ ◀── successUrl?paymentKey&orderId&amount ──────────────────────────────────────┘
  │ POST /payments/confirm ─────────────────────────▶ ① 소유자·금액·상태 검증
  │                                                   ② READY → IN_PROGRESS (낙관적 락)
  │                                                   ③ 승인 요청 (Idempotency-Key) ───▶ 승인
  │                                                   ④ DONE 기록 ◀───────────────────┘
  │                            ◀── #308 payment.approved(outbox, 예정) ┘
  │                            PAID 전이 · 재고 확정 차감
```

### 3-1. 금액은 서버가 정하고 서버가 검증한다

- 결제 금액은 **order-service 가 계산한 주문 금액**이다. 프론트가 보낸 금액을 믿지 않는다.
- successUrl 로 돌아온 `amount` 는 **변조될 수 있다.** 승인 요청 전에 `payment.amount` 와 `currency` 가 일치하는지
  확인하고, 다르면 **승인 요청을 보내지 않고** 거절한다 (400 `PAYMENT_008`). 이 경우는 보안 경고 로그(결제 · 회원 아이디만)로 남긴다.
- 쿠폰·배송비·할인이 바뀌어 주문 금액이 달라졌으면 결제 준비를 새로 받는다 (기존 READY 는 ABORTED).

### 3-2. 중복 승인 방지 — 두 겹으로 막는다

1. **우리 상태**: `READY → IN_PROGRESS` 전이를 낙관적 락(`@Version`) 또는 조건부 UPDATE 로 한 번만 성공시킨다.
   이미 `IN_PROGRESS`/`DONE` 이면 PG 를 다시 부르지 않는다 — `DONE` 은 현재 상태를 돌려주고, `IN_PROGRESS` 는 409 `PAYMENT_007`(처리 중) 이다(§3-4).
2. **PG 멱등 키**: 승인·취소 요청에 `Idempotency-Key` 헤더를 싣는다. 값은 우리 결제 시도 ID 처럼 재시도해도
   같은 값이어야 한다 — 요청마다 새 UUID 를 만들면 멱등이 아니다.

### 3-3. 트랜잭션 경계

- **PG 호출을 DB 트랜잭션 안에 두지 않는다** (`architecture-guide.md` §3 Facade 예외).
  - ② 상태 전이 → 커밋 → ③ PG 호출(트랜잭션 밖) → ④ 결과 기록 → 커밋
- PG 승인 성공 후 ④ 의 DB 저장이 실패해도 결제는 이미 일어났다. 그래서 ② 에서 남긴 `IN_PROGRESS` 기록이
  복구의 단서가 된다 (§5).

### 3-4. 고객 API 계약 (#14 c2)

고객 전용(`hasRole('CUSTOMER')`)이고 회원은 토큰 주체다. 구현은 `PaymentWebController` → `PaymentWebFacade`(트랜잭션 `NEVER`) →
`OrderSummaryProcessor`(order-service, 트랜잭션 없음) · `PaymentProcessor`(메서드마다 트랜잭션) · `PaymentGatewayProcessor`(토스, 트랜잭션 없음)다.

**운영 결제 게이트** — `payment.enabled`(기본 **false**, 닫힌 기본값). 공통 `application.yml` 은 false, `application-local.yml` · `application-dev.yml`(토스 테스트 키 환경)만 true 이고
prod 는 프로파일 yml 이 없어 false 다. 꺼져 있으면 결제 준비 · 승인 모두 DB · PG · order 를 부르기 전에 503 `PAYMENT_015`("지금은 결제를 받을 수 없습니다.")다 —
상태 조회는 막지 않는다. **운영에서 켜는 조건은 #308(결제 완료 → 주문 반영)과 #15(UNKNOWN 재조회 · IN_PROGRESS 정체 복구 · 되돌린 READY 정리)가 모두 들어온 뒤**다.
그 전에 열면 돈은 빠졌는데 주문은 결제 대기로 남고, 미확정 결제를 확정할 경로도 없다(§4 · §6).
환경변수 `PAYMENT_ENABLED=true` 로도 켤 수 있지만, 운영(prod)에서는 위 두 이슈가 머지되기 전에 넣지 않는다 — 배포 env · Vault 에 이 키를 두지 않는다.
승인 직전 주문 재확인(아래 3번 표 뒤)과 PG 승인 사이에 주문이 만료 · 취소되는 좁은 경합은 남는다 — 재선점 · 자동 전액 취소로 받는 것은 #308 의 몫이다.
재선점은 `EXPIRED` 주문만 한다 — 고객이 결제 전 취소한 `CANCELED` 주문(#16)은 확정 · 재선점 없이 거절하고 전액 취소한다. 확정 뒤 주문 반영이 `CANCELED` 에 지면
결제 후 복원(`stock-restorations`)으로 재고를 되돌린다(`order-flow-guide.md` §1-1).

| API | 성공 | 응답 |
|-----|------|------|
| `POST /api/v1/payments` `{orderId}` | 201 새 결제 시도 · 200 기존 활성 시도 | `PaymentPrepareResponse` — 아래 칸 + 주문의 `paymentDueAt` |
| `POST /api/v1/payments/confirm` + `Idempotency-Key`(UUID) `{paymentKey, pgOrderId, amount{amount, currency}}` | 200 — DONE · FAILED · UNKNOWN | `PaymentResponse` |
| `GET /api/v1/payments/{paymentId}` | 200 | `PaymentResponse` |

`PaymentResponse` 칸: `paymentId` · `orderId` · `pgOrderId`(문자열), `status`(metadata), `amount`, `orderName`, `method`(metadata | null), `easyPayProvider` | null,
`approvedAt` | null, `failure{code, message}` | null(FAILED 만). 결제키 · 미확정 이유 · 주문 반영 여부 · 승인 시작 시각 · 고객 멱등 키는 내리지 않는다.

**결제 준비** — 업무 키는 주문 아이디 + 주문당 활성 결제 시도 하나(`uk_payment_active_order_id`)라 `Idempotency-Key` 를 받지 않는다.

1. order-service 주문 요약(`GET /internal/v1/orders/{orderId}/payment-summary?memberId=`, 서비스 토큰)을 트랜잭션 밖에서 받는다.
   없거나 남의 주문 404 `PAYMENT_002` → 결제 대기(`PENDING_PAYMENT`) 아님 409 `PAYMENT_003` → **KRW 아님 409 `PAYMENT_005`**(USD 는 #20) →
   **결제 기한까지 `payment.prepare.min-remaining`(기본 10분)보다 적게 남음 409 `PAYMENT_004`** — 고객이 인증을 마치기 전에 주문이 정리되는 창을 줄인다.
2. 활성 시도 규칙:
   - 없음 → 새 READY(201).
   - READY 이고 금액 · 주문명이 요약과 같다 → 그대로(200, 저장 없음). 결제키가 있는 되돌린 READY 는 금액만 같으면 그대로다.
   - READY · 결제키 없음 · 금액이나 주문명이 다르다 → 같은 트랜잭션에서 ABORTED(`AMOUNT_CHANGED` · 주문명만 다르면 `REPREPARED`) + 새 READY(201).
   - READY · 결제키 있음 · 금액이 다르다 → 409 `PAYMENT_012` — 이전 결제키가 승인됐을 수 있어 조회 없이 버리지 않는다(§5 되돌린 READY).
   - IN_PROGRESS · UNKNOWN · DONE 등 → 그 시도를 그대로(200).
3. 저장이 활성 슬롯 · 버전에서 겹치면 새 트랜잭션에서 다시 읽어 위 규칙으로 답할 수 있으면 200, 아니면 409 `PAYMENT_012`(잠시 뒤 재시도).

**결제 승인** — 멱등은 `api-design-guide.md` §6 그대로다: **고객 `Idempotency-Key` 를 결제 행(`approval_idempotency_key`)에 기록**하고 `(member_id, approval_idempotency_key)`
유니크 키(`uk_payment_member_approval_key`)가 키의 결과를 가리킨다. 별도 멱등 테이블을 두지 않는다 — 결과가 곧 결제 행의 현재 상태다. 토스에 싣는 PG 멱등 키(결제 시도 아이디, §3-2)와 다르다.

1. PG 주문번호로 회원의 결제 시도를 읽는다 — 없거나 남의 것 404 `PAYMENT_001`.
2. 이 회원이 이 키로 **다른** 결제 시도의 승인을 시작했으면 409 `PAYMENT_014`.
3. READY 가 아니면 저장 없이 답한다.

   | 현재 상태 | 같은 키 | 다른 키 |
   |-----------|---------|---------|
   | DONE · UNKNOWN (취소 계열 PARTIAL_CANCELED · CANCELED · WAITING_FOR_DEPOSIT 도 이 줄) | 같은 결제키 · 같은 금액 · 통화 → 200 현재 상태 · 다른 결제키 → 409 `PAYMENT_014` · 같은 결제키인데 금액 · 통화가 다름 → 409 `PAYMENT_014` + 보안 경고 | 같은 결제키 → 200 현재 상태(새 키로 재확인) · 다른 결제키 → 400 `PAYMENT_009` + 보안 경고 |
   | FAILED | 같은 결제키 → 200 현재 상태 · 다른 결제키 → 409 `PAYMENT_014` | 409 `PAYMENT_006` — 결제 준비부터 다시 |
   | IN_PROGRESS | 409 `PAYMENT_007` | 409 `PAYMENT_007` |
   | ABORTED · LATE_APPROVED | 409 `PAYMENT_006` | 409 `PAYMENT_006` |
4. READY 면 금액 · 통화를 대조한다 — 다르면 PG 를 부르지 않고 400 `PAYMENT_008` + 보안 경고(§3-1).
   이어서 **주문을 다시 확인한다**(order-service, 트랜잭션 밖) — 결제 준비 뒤 주문이 취소 · 만료 · 결제됐을 수 있다. 없음 404 `PAYMENT_002` · 결제 대기 아님 409 `PAYMENT_003` ·
   `now >= paymentDueAt` 409 `PAYMENT_004`(여기서는 `min-remaining` 이 아니라 기한 경과만) · order 장애 503 `PAYMENT_011` · 주문 금액 · 통화가 결제 시도와 다름 409 `PAYMENT_012` + error 로그
   (주문 금액은 불변이라 결함 신호). 어느 쪽이든 PG 를 부르지 않고 결제 행을 바꾸지 않는다 — READY 그대로 두고 정리는 #15 다. 3번에서 끝나는 요청(READY 가 아님)은 order 를 부르지 않는다.
5. READY → IN_PROGRESS + 결제키 + 고객 키를 저장해 커밋한다. 되돌린 READY 에 다른 결제키 · 다른 시도의 결제키(`uk_payment_payment_key`)는 400 `PAYMENT_009` + 보안 경고,
   키 유니크 충돌은 409 `PAYMENT_014`, 버전 충돌(동시 승인)은 새 트랜잭션에서 다시 읽어 3번 표대로(대개 409 `PAYMENT_007`).
6. 트랜잭션 밖에서 토스 승인(§4-1).
7. 결과를 새 트랜잭션에서 기록한다 — Approved → DONE(금액 · 결제키가 어긋나면 UNKNOWN `APPROVAL_MISMATCH`), Rejected → FAILED, Unknown → UNKNOWN, NotSent → READY 로 되돌림.
   버전 충돌(재조회 · 웹훅이 먼저)은 다시 읽은 현재 상태 200, 그 밖의 저장 실패는 500 `PAYMENT_013`(상태 조회로 확인 — IN_PROGRESS 행이 복구 단서다).
8. DONE · FAILED · UNKNOWN 은 200, READY(보내지 못함)는 503 `PAYMENT_010` + `Retry-After: 5` — 청구가 없었으니 같은 본문으로 다시 보낸다.
   되돌린 READY 는 같은 결제키면 새 키로 와도 다시 시작한다(5번이 키를 새로 기록한다).

| 코드 | HTTP | 뜻 |
|------|------|----|
| `PAYMENT_001` | 404 | 결제 정보 없음(타인 소유 포함) |
| `PAYMENT_002` | 404 | 주문 없음(타인 소유 포함) |
| `PAYMENT_003` | 409 | 결제할 수 없는 주문 상태 |
| `PAYMENT_004` | 409 | 결제 기한이 임박했거나 지남 |
| `PAYMENT_005` | 409 | 지원하지 않는 통화(KRW 만) |
| `PAYMENT_006` | 409 | 이미 끝난 결제 시도(FAILED · ABORTED · LATE_APPROVED) — 다시 준비 |
| `PAYMENT_007` | 409 | 승인 처리 중 |
| `PAYMENT_008` | 400 | 결제 금액 불일치 |
| `PAYMENT_009` | 400 | 결제키 불일치 |
| `PAYMENT_010` | 503 + `Retry-After` | 결제사에 연결하지 못함(청구 없음, 같은 요청 재시도) |
| `PAYMENT_011` | 503 | 주문 정보를 잠시 불러올 수 없음 |
| `PAYMENT_012` | 409 | 결제 요청이 겹침 — 잠시 후 재시도. 번역되지 않은 도메인 예외의 마지막 안전망도 이 코드다(error 로그) |
| `PAYMENT_013` | 500 | 승인 결과 기록 실패 — 상태 조회로 확인 |
| `PAYMENT_014` | 409 | 같은 `Idempotency-Key` 로 다른 결제 승인을 요청할 수 없음 |
| `PAYMENT_015` | 503 | 운영 결제 게이트가 닫혀 있음(`payment.enabled=false`) — 지금은 결제를 받을 수 없음 |
| `PAYMENT_100` | 400 | 요청 값 오류(폴백) |
| `PAYMENT_101` · `102` | 400 | `orderId` 필수 · 양수 |
| `PAYMENT_103` · `104` | 400 | `paymentKey` 필수 · 1~200자 |
| `PAYMENT_105` · `106` · `107` | 400 | `pgOrderId` 필수 · 6~64자 · 영문 · 숫자 · `-` · `_` |
| `PAYMENT_108` | 400 | `amount` 필수 |
| `PAYMENT_109` · `110` | 400 | `amount.amount` 필수 · 양수 |
| `PAYMENT_111` · `112` | 400 | `amount.currency` 필수 · 대문자 3자 |
| `PAYMENT_113` · `114` | 400 | `Idempotency-Key` 헤더 필수 · UUID 형식 (`fieldErrors[0].field` = 헤더 이름) |
| `PAYMENT_198` · `199` | 400 | 파라미터 형식(결제 아이디 등) · 필수 파라미터 누락 |

- 응답 메시지는 언제나 오류 코드의 고정 문구다 — 핸들러는 예외 메시지가 아니라 `errorCode.getMessage()` 를 싣는다.
- **운영 DDL** (#14 — `approval_idempotency_key` 칸 · `uk_payment_member_approval_key` · `ck_payment_approval_key_payment_key`)은 배포 전에 적용한다. dev 도 수동 적용한다 —
  `ddl-auto=update` 는 칸은 더하지만 기존 테이블에 check · unique 제약을 만들지 않는다.
- 후속: #440 — 결제 쓰기 요청 제한, `PAYMENT_011` 의 `Retry-After`, 거절 문구 정책.
- order-service 요약 호출은 서킷 `order-service`(4xx 는 `ignore-exceptions`)로 감싸고, 404 `ORDER_001` 만 "주문 없음" 이다. 다른 4xx · 401 `SECURITY_007` · 응답 계약 위반
  (아이디 불일치 · 금액 0 이하 · 모르는 통화 · 주문명 비었거나 100자 초과 · 기한 없음) · 5xx · 타임아웃 · 서킷 오픈은 503 `PAYMENT_011` 이다(`OrderInternalResponseSupport`).
- 도메인 예외 메시지 · 결제키 · 원인 예외 메시지는 응답 · 로그에 싣지 않는다. 5xx 로그는 코드와 `causeType` 만이다(`coding-conventions.md` §8). 요청 DTO · 명령의 `toString` 은 결제키 앞 8자만 남긴다.

## 4. 결과 미확정(UNKNOWN) 처리

PG 호출이 **타임아웃·5xx·서킷 오픈**으로 끝나면 승인 여부를 모른다. 이것을 "실패" 로 처리하면 안 된다 —
고객 카드에서는 돈이 빠졌는데 우리는 실패로 보고 주문을 취소하게 된다.

| PG 응답 | 우리 상태 | 고객 응답 |
|---------|-----------|-----------|
| 승인 성공 | `DONE` | 결제 완료 |
| 명확한 거절 (4xx, 한도 초과·카드 거절 등) | `FAILED` (+ PG 오류 코드) | 실패 사유 표시, 재시도 가능 |
| 타임아웃 · 5xx · 서킷 오픈 | **`UNKNOWN`** | "결제 확인 중" — 몇 초 뒤 상태 조회 |

- `UNKNOWN` 은 스케줄러가 **PG 조회 API 로 재조회**해서 `DONE` 또는 `FAILED` 로 확정한다.
  - 재조회 · 오래된 IN_PROGRESS 복구 · 되돌린 READY 정리 스케줄러는 #15 다 — 그 전까지 `UNKNOWN` · 오래 남은 `IN_PROGRESS` 는 토스 상점관리자에서 사람이 확인한다.
- 서킷이 열렸을 때 confirm 을 아예 보내지 않았다면 `READY` 로 되돌릴 수 있다. **보냈는지 모르면 UNKNOWN 이다.**

### 4-1. 토스 승인 응답 분류 (#14 — `TossPaymentsResponseSupport` 가 정본)

어댑터는 예외를 던지지 않고 `PgApprovalQueryResult` 네 가지 중 하나를 돌려준다. 애매하면 언제나 `Unknown` 쪽이다.

| 토스 결과 | 포트 결과 | 결제 상태 |
|-----------|-----------|-----------|
| 2xx + `status=DONE` + 주문번호 · 통화 · 금액 · `approvedAt` 해석 성공 | `Approved` | `DONE` (금액 · 결제키 대조는 도메인) |
| 2xx 인데 위 조건 중 하나라도 어긋남(형식 오류 포함) | `Unknown(CONTRACT)` | `UNKNOWN` |
| 승인 오류표의 "처리 전 거절" 코드(4xx — 카드 거절 · 한도 초과 · 잘못된 요청 · `FORBIDDEN_REQUEST` 등) | `Rejected` | `FAILED` |
| 오류표에 없는 4xx 코드 · 코드 없는 4xx | `Unknown(CONTRACT)` | `UNKNOWN` |
| 409 · `ALREADY_PROCESSED_PAYMENT` · `ALREADY_PROCESSING_REQUEST` · `DUPLICATED_ORDER_ID` | `Unknown(ALREADY_PROCESSED)` | `UNKNOWN` |
| 일시 오류 코드(`PROVIDER_ERROR` · `CARD_PROCESSING_ERROR`) · 429 · 5xx · 타임아웃 · 보낸 뒤 연결 끊김 | `Unknown(SERVER_ERROR / TIMEOUT)` | `UNKNOWN` |
| 401 · `UNAUTHORIZED_KEY` · `INVALID_API_KEY` · `INCORRECT_BASIC_AUTH_FORMAT` · 연결 거부 · DNS 실패 · 서킷 오픈 | `NotSent` | `READY` 로 되돌림(결제키 유지) |

- 거절 코드 목록은 `TossPaymentsResponseSupport.REJECTION_CODES` 한 곳이고, 토스 core API "결제 승인" 오류표(2026-10 스크랩)에서 뽑았다.
  판정은 HTTP status 가 아니라 코드로 한다. 토스가 오류표에 코드를 더하면 그 코드는 목록에 넣기 전까지 `UNKNOWN` 으로 쌓인다 — 돈 쪽으로 안전한 기본값이다.
- 조회(`GET /v1/payments/{paymentKey}` · `/v1/payments/orders/{orderId}`)는 `ABORTED` · `EXPIRED` 만 `Failed`, `READY` · `IN_PROGRESS` 는 `Pending`,
  404 `NOT_FOUND_PAYMENT` 는 `NotFound`, 취소 · 입금 대기 계열은 `Unknown(MANUAL_REVIEW)` 다. 403 `FORBIDDEN_CONSECUTIVE_REQUEST` · 429 · 5xx 는 `Unknown(SERVER_ERROR)`,
  응답의 결제키 · 주문번호가 요청과 다르면 `Unknown(CONTRACT)` 다.

- Feign 재시도는 끈다(`Retryer.NEVER_RETRY`). 같은 결제 시도는 언제나 같은 `Idempotency-Key`(결제 시도 id)로 보내므로,
  토스는 15일 동안 첫 응답을 그대로 돌려준다 — 재승인에 `Approved` 가 다시 와도 오류가 아니라 멱등 성공이다.
- 서킷은 5xx · 429 · 타임아웃 · 연결 실패만 실패로 센다. 4xx 거절은 세지 않는다.
- **되돌린 `READY` 를 버릴 근거는 결제키 단위 조회(`GET /v1/payments/{paymentKey}`)의 `Failed` 뿐이다.** 주문번호 조회는 같은 주문번호로 재인증한
  다른 결제키를 돌려줄 수 있고, `NotFound` 는 미승인 증거가 아니다.
  `PaymentInquiry.Failed` 는 결제키를 싣고, `abortAfterInquiry` 는 결제키가 다르면 전이 예외, `resolve(Failed)` 는 결제키가 다르면 `PaymentApprovalMismatchException` 이다
  — 다른 결제키의 실패로 `FAILED` 를 확정하면 이 결제키의 승인을 놓친다. 불일치는 Processor 가 사람 확인(`flagForManualReview`)으로 돌린다.

## 5. 결제 상태 모델

```text
READY ──▶ IN_PROGRESS ──▶ DONE ──▶ PARTIAL_CANCELED ──▶ CANCELED   (취소 전이는 취소 이슈에서 연다)
  │   ◀──────┘ │  │  └────────────────────────────────▶ CANCELED
  │  (되돌림)  │  └──▶ FAILED ──┐
  │            └──▶ UNKNOWN ──▶ DONE | FAILED   (재조회로 확정 · UNKNOWN 안에서 사람 확인 표시)
  └──▶ ABORTED ──────────────────┴──▶ LATE_APPROVED   (종결 뒤 PG 승인 확인 — 전액 취소 대상)
      (금액 변경 · 재준비 · 주문 만료로 버려진 시도)
```

- 상태 전이는 `Payment` 의 도메인 메서드로만 한다 — `startApproval` · `abort` · `abortAfterInquiry` · `approve` · `reject` · `markUnknown` · `revertToReady` ·
  `resolve` · `flagForManualReview` · `recordLateApproval` · `markOrderSynced` · `markOrderSyncFailed`(#14). 표 밖 전이는 `PaymentTransitionException`(현재 상태 · 시도한 동작)이고
  Processor 가 PAYMENT 오류 코드로 바꾼다. 같은 상태 재호출이 멱등인 것은 주문 반영 기록 · `flagForManualReview` 뿐이다. 승인 · 거절의 재호출은 예외다 — 중복 승인 경로를 조용히 통과시키지 않는다.
  멱등 no-op(같은 인스턴스 반환)이면 저장하지 않는다(쓸데없는 버전 증가 · 충돌 방지).
- **되돌린 READY** — `revertToReady`(IN_PROGRESS → READY)는 승인 요청을 **보내지 않은 것이 확실할 때만** 쓴다(서킷 오픈 · 연결 전 실패 · 키 설정 오류).
  결제키 · `approval_started_at` 을 흔적으로 남긴다. 이 READY 는 **같은 결제키로만** 다시 승인을 시작하고(다른 키면 `PaymentApprovalMismatchException`),
  버릴 때는 PG 조회로 미승인을 확인한 뒤 `abortAfterInquiry` 로만 버린다 — `abort` 는 결제키가 없는 READY 만이다.
  "보내지 않았다" 는 판정이 틀려 이전 키가 이미 승인됐는데 바로 버리면, 슬롯이 풀려 새 시도가 또 승인되어 **이중 결제**가 된다.
- **LATE_APPROVED** — FAILED · ABORTED 로 종결한 시도를 PG 가 승인했다고 알려 오면(웹훅 · 재조회 · 대사) `recordLateApproval` 로 남긴다.
  돈이 빠졌으니 승인 칸(`payment_key` · `method` · `approved_at`)을 기록하고, 종결 이유(`failure_code` 또는 `abort_reason`)는 이력으로 둔다.
  주문에 반영하지 않고 활성 슬롯도 다시 잡지 않는다 — **전액 취소 대상**이다(취소 전이는 취소 이슈, "허용되지 않은 전이라 무시" 로 버리지 않는다).
- 결제 1건 = 결제 시도 1건 = `payment` 행 1건. 실패 후 재시도는 **새 결제 시도**(새 `pgOrderId`)로 만든다.
  `pgOrderId` 는 결제 시도 아이디(Snowflake) 문자열이다 — 토스 규칙(6~64자, 영문 · 숫자 · `-` · `_`, 승인 · 취소된 번호 재사용 불가 `DUPLICATED_ORDER_ID`)을 만족한다.
  출처: <https://docs.tosspayments.com/reference> (주문번호 · 결제 승인, 2026-10-21 확인).
- **주문당 활성 결제 시도는 하나다** — `active_order_id` 유니크 키(FAILED · ABORTED · LATE_APPROVED 면 null, 그 밖이면 반드시 `order_id` — check 가 NULL 도 막는다).
  같은 주문의 READY 둘이 동시에 결제창에 올라 두 번 승인되는 길을 DB 가 막는다. UNKNOWN 은 돈이 빠졌을 수 있어 슬롯을 놓지 않는다.
  같은 주문에 새 시도를 만들려면 기존 READY 를 먼저 ABORTED 로 둔다(되돌린 READY 는 위 규칙대로 조회 뒤에만).
- 저장 충돌은 포트가 `PaymentPersistenceConflictException`(kind = `ACTIVE_SLOT` · `PAYMENT_KEY` · `VERSION`) 하나로 알린다 — 원인 예외는 싣지 않는다(#173).
  충돌 뒤 다시 읽기는 **새 트랜잭션**에서 한다(PostgreSQL 트랜잭션이 aborted 라 같은 트랜잭션에서 읽으면 실패한다). `PAYMENT_KEY` 충돌은 다른 시도의 결제키를 붙인 것이라 변조 신호다.
- 승인 결과의 금액 · 결제키가 결제 시도와 다르면 `PaymentApprovalMismatchException`(어긋난 항목만, 값은 싣지 않음)이고 승인으로 기록하지 않는다.
  승인 응답이면 UNKNOWN(`APPROVAL_MISMATCH`), 재조회 결과면 UNKNOWN 그대로 `flagForManualReview(MANUAL_REVIEW)` — 재조회 스케줄러는 MANUAL_REVIEW 를 건너뛰고 알림한다.
  IN_PROGRESS 로 오래 남은 행(승인 뒤 기록 실패 · 프로세스 종료)은 복구 스케줄러가 `markUnknown(STALE_IN_PROGRESS)` 로 재조회 대상에 넣는다.
- PARTIAL_CANCELED · CANCELED · WAITING_FOR_DEPOSIT(가상계좌, §7)는 enum · check 제약에 미리 둔다(status check 를 나중에 drop/add 하지 않게). 지금은 들어오는 전이가 없다.
- 금액 컬럼은 `total_amount` + `total_currency` 짝이다(`coding-conventions.md` §9-3).
- 취소는 `payment_cancel` 행으로 누적한다. **남은 취소 가능 금액 = 승인 금액 − 취소 누계**이며 이를 넘는 취소를 거절한다.

## 6. 서비스 간 정합성

결제 완료를 order-service 에 알리는 단계가 실패할 수 있다.

- payment-service 는 `DONE` 기록과 함께 **`order_synced = false`** 를 남기고, 알림이 성공하면 `true` 로 바꾼다.
  `order_synced` · `order_sync_failure_code` 조합의 뜻(DONE 계열만 쓴다 — LATE_APPROVED 는 주문에 반영하지 않는다, #14):

  | `order_synced` | `order_sync_failure_code` | 뜻 | 처리 |
  |----------------|---------------------------|----|------|
  | false | null | 아직 반영하지 않았다 | 재전송 대상 |
  | true | null | 반영 완료(order-service 가 PAID 확정) | 없음 |
  | false | 코드 | order-service 가 종결 거절(주문 취소됨 · 재고 없음 · 금액 불일치 등 — 재전송해도 바뀌지 않는다) | 재전송에서 빼고 **자동 전액 취소 · 어드민 확인** 대상(취소 이슈 전까지는 알림) |
  | true | 코드 | 금지(check) — 반영 성공 뒤 늦게 온 실패는 무시한다 | — |
- 스케줄러가 `DONE && !order_synced && order_sync_failure_code is null` 을 주기적으로 재전송한다. order-service 의 결제 완료 처리는 **멱등**이어야 한다
  (이미 PAID 면 성공으로 응답).
- 재고 선점이 만료된 뒤 결제가 완료되는 경우(고객이 결제창을 오래 열어 둠)가 있다. order-service 가 재고를 다시
  잡지 못하면 **자동 전액 취소** 후 고객에게 안내한다 — 돈만 받고 상품이 없는 주문을 남기지 않는다.
  확정이 409 `PRODUCT_025`(이미 해제 · 만료)면 같은 주문으로 재선점(`POST /internal/v1/stock-reservations`)하고 **곧바로 확정**한다. 재선점이
  `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면 전액 취소다. **주문은 선점 확정 200(`CONFIRMED`)을 받은 뒤에만 PAID 로 둔다** — 결제 완료 재전송 때도
  확정을 멱등으로 다시 부른다 (`order-flow-guide.md` §3-4).
- **메시지 브로커는 Kafka 로 정했다**(#304, `architecture-guide.md` §8-1). 결제 승인 → 주문 반영은 #308 에서 아래처럼 만든다.
  **아직 주문 반영 경로가 없다 — #308 전에는 운영 결제를 열지 않는다(`payment.enabled=false`, §3-4).** 위의 "동기 호출 + 재전송 플래그 + 스케줄러" 는 구현되지 않았다.
  - payment-service 가 `approve`(DONE 기록) 트랜잭션에서 outbox 로 `payment.approved`(키 orderId, 본문은 결제 · 주문 아이디 · 금액 · 통화 · 승인 시각 · 수단 코드 — PG 결제 키 · 카드 정보 없음)를 발행한다.
  - order-service 컨슈머가 금액 · 통화 · 소유자를 다시 검증하고 선점을 확정(위 재선점 규칙 그대로, Feign 은 트랜잭션 밖)한 뒤 `markPaid` 와 회신 `order.paid` 를 한 트랜잭션에 남긴다.
    다시 잡지 못하거나 금액이 다르면 `order.payment-rejected(code)` 를 회신한다.
  - `order_synced` · `order_sync_failure_code` 는 남기고 뜻을 "주문의 회신 이벤트를 받았는가" 로 바꾼다 — `order.paid` 면 synced, `order.payment-rejected` 면 실패 코드 + 자동 전액 취소 대상.
    **재전송 스케줄러는 만들지 않는다** — 전달은 outbox 릴레이와 컨슈머 재시도 · DLT 가 보장한다. 종결 뒤 승인(LATE_APPROVED)은 `payment.late-approved` 로 알림만 보낸다.

## 7. 웹훅

- 수신 경로: `POST /api/v1/payments/webhooks/toss` (`adapter/in/webhook`, JWT 없음).
- **웹훅 본문을 그대로 믿지 않는다.** 이벤트를 받으면 PG 조회 API 로 결제를 재조회해 그 결과로 상태를 확정한다.
- 같은 이벤트가 여러 번 올 수 있다. 상태 전이 규칙(§5)이 이미 반영된 이벤트를 무시하게 만든다 — 멱등.
- 처리는 짧게 하고 빨리 `200` 을 돌려준다. 무거운 후처리는 비동기로 넘긴다.
- 가상계좌를 켜면 입금 완료가 웹훅으로만 온다. 그때 `WAITING_FOR_DEPOSIT` 상태와 입금 콜백 처리를 추가한다.

## 8. 취소 · 환불

- 환불 금액(배송비 차감, 부분 반품)은 **order-service 의 클레임 정책이 계산**하고 payment-service 는 실행만 한다.
- 취소 요청도 `Idempotency-Key` 를 싣는다. 키는 클레임 ID 처럼 재시도해도 같은 값이어야 한다.
- PayPal 환불은 수수료가 돌려받아지지 않을 수 있다 — 환불 정책 문구와 어드민 화면에 반영한다.

## 9. 해외 결제 (USD · PayPal)

- 금액은 **USD 센트(minor unit) 정수**로 저장하고, PG 요청 시 adapter 가 소수 표현(`89.50`)으로 바꾼다.
  이 변환은 `shared-commerce` 의 `CurrencyCode.fractionDigits` 를 쓴다 — 서비스마다 다시 계산하지 않는다.
- USD 가격은 **SKU 별 통화 가격표**로 둔다(권장). 결제 시점 환율 환산은 금액이 매번 달라져 정산·환불이 어려워진다.
  최종 방식은 해외 판매 이슈에서 결정한다.
- 해외 주문은 국내 주문서와 결제수단 목록이 다르다 (USD → PayPal·해외카드만).

## 10. 대사 (reconciliation)

- 하루 한 번 우리 `DONE`/`CANCELED` 기록과 PG 거래 내역을 대조한다. 어긋난 건은 어드민에 노출한다.
- 처음에는 수동 점검(어드민 목록 + PG 상점관리자)으로 시작하고, 배치는 `batch-service` 를 만들 때 붙인다.

## 11. 테스트

- PG 는 adapter 단위에서 가짜 서버(WireMock 등)로 성공·거절·타임아웃·5xx 를 모두 재현한다.
- **UNKNOWN 경로와 중복 승인 경로는 반드시 테스트한다** — 평소에는 절대 지나가지 않는 길이라 운영에서 처음 터진다.
  - payment-service `TossPaymentGatewayAdapterWireMockTest` 가 §4-1 표의 행마다 한 번 이상 지나간다. POST 가 재전송되지 않는지 `verify(1, ...)` 로 본다
    — 기본 `Client.Default`(요청 버퍼링 끔) 전제라 feign-hc5 · okhttp 를 넣으면 다시 확인한다.
  - 중복 승인은 토스 실제 동작대로 "같은 멱등 키 → 같은 200 DONE" 을 재현한다. READY→IN_PROGRESS 원자성은 승인 Processor 테스트가 본다.
- 테스트 키로 실제 토스 샌드박스 승인 → 취소까지 한 번 돌린 결과를 PR 검증 내역에 남긴다.
