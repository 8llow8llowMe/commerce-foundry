# Payment Guide

결제 이상 후보 원천 조회는 [결제 관측 계약](payment-observation-guide.md)을 따른다(#477 A4a).
관측은 결제 상태를 바꾸지 않으며 임계값·소비 알림·해소 판정은 후속 작업이다.

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
  │                                                   ④ DONE 기록 + payment-approved outbox ◀──┘  (같은 트랜잭션, #308 PR-A1)
  │                            ◀── Kafka payment-approved ──┘  (릴레이 · 키 orderId)
  │                            ⑤ 결제 재조회(internal) · 선점 확정 · PAID 전이 (#308 PR-B 예정)
  │                            ⑥ order-paid / order-payment-rejected 회신 ──▶ order_synced 기록 (#308 PR-C 예정)
```

### 3-1. 금액은 서버가 정하고 서버가 검증한다

- 결제 금액은 **order-service 가 계산한 주문 금액**이다. 프론트가 보낸 금액을 믿지 않는다.
- successUrl 로 돌아온 `amount` 는 **변조될 수 있다.** 승인 요청 전에 `payment.amount` 와 `currency` 가 일치하는지
  확인하고, 다르면 **승인 요청을 보내지 않고** 거절한다 (400 `PAYMENT_008`). 이 경우는 보안 경고 로그(결제 · 회원 아이디만)로 남긴다.
- 쿠폰·배송비·할인이 바뀌어 주문 금액이 달라졌으면 결제 준비를 새로 받는다 (기존 READY 는 ABORTED).

### 3-1-1. 토스 승인 계약 확인 (2026-11-02)

- `POST /v1/payments/confirm` 요청 본문은 `paymentKey`(최대 200자), `orderId`(6~64자), `amount`가 필수다. 결제 인증 후 **10분 안에** 승인하지 않으면 인증이 만료된다. 이 10분은 HTTP read timeout 10초나 우리 주문의 `paymentDueAt`과 다른 제한이다.
- 토스 `orderId`는 영문 대소문자·숫자·`-`·`_`·`=`를 허용한다. 현재 `pgOrderId`는 결제 시도 ID의 숫자 문자열이므로 허용 범위 안에 있다. `successUrl`의 `paymentKey`·`orderId`·`amount`는 서버에 저장한 시도와 대조한 뒤 승인한다.
- 토스 `Idempotency-Key`는 최대 300자이고 최초 요청일부터 15일간 유효하다. 같은 승인 시도에서는 같은 키를 유지하고, 그 뒤의 복구는 PG 조회와 우리 결제 기록으로 판정한다. 주문번호 조회 `GET /v1/payments/orders/{orderId}`는 결제위젯 키(`gsk`)로 호출할 수 없다. 현재 `TossPaymentGatewayAdapter#inquire`는 주문번호 조회를 사용하므로 #15 재조회 구현에서는 키 종류를 확인하고 `gsk`일 때 결제키 조회를 사용해야 한다.

근거: [토스 코어 API](https://docs.tosspayments.com/reference), [인증 및 멱등키](https://docs.tosspayments.com/reference/using-api/authorization).

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
게이트를 켠 인스턴스는 outbox 테이블(`payment.outbox` · `payment.shedlock`)이 없으면 기동하지 않는다(#308, §6) — 운영 DDL 이 먼저다.
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
`approvedAt` | null, `failure{code, message}` | null(FAILED 만).
`failure.code`는 기존 결제사 코드이고 `failure.message`는 코드·PG 원문·저장 시점과 무관하게 "결제를 완료하지 못했습니다. 다른 결제 수단으로 다시 시도해 주세요."다(#440).
과거 저장된 원문도 고객 준비·승인·조회 응답에는 싣지 않는다. 저장된 진단 정보는 바꾸지 않으며 FAILED 외에는 failure가 null이다.
 결제키 · 미확정 이유 · 주문 반영 여부 · 승인 시작 시각 · 고객 멱등 키는 내리지 않는다.

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
| `PAYMENT_011` | 503 + `Retry-After: 10` | 주문 정보를 잠시 불러올 수 없음 |
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
| `PAYMENT_115` | 400 | internal 결제 조회(`GET /internal/v1/payments/{paymentId}`)의 `paymentId` 양수 아님 (§6) |
| `PAYMENT_198` · `199` | 400 | 파라미터 형식(결제 아이디 등) · 필수 파라미터 누락 |

- 응답 메시지는 언제나 오류 코드의 고정 문구다 — 핸들러는 예외 메시지가 아니라 `errorCode.getMessage()` 를 싣는다.
- **운영 DDL** (#14 — `approval_idempotency_key` 칸 · `uk_payment_member_approval_key` · `ck_payment_approval_key_payment_key`)은 배포 전에 적용한다. dev 도 수동 적용한다 —
  `ddl-auto=update` 는 칸은 더하지만 기존 테이블에 check · unique 제약을 만들지 않는다.
- #440: 게이트웨이 결제 쓰기는 회원당 기본 분당 6회 · 순간 3회이며 초과 시 하류 호출 전에 429 `GATEWAY_006` + `Retry-After: 10`이다 (`security-guide.md` §7-3).
  웹훅 경로는 이 버킷에서 제외하고 결제 상태 GET 폴링은 세지 않는다. `PAYMENT_011`의 `Retry-After: 10`은 클라이언트 재요청 간격 안내이며
  서킷 복구나 승인 성공을 보장하지 않는다. 자동 승인 재시도를 추가하지 않으며 `PAYMENT_010`의 기존 Retry-After 처리와 별개다.
- order-service 요약 호출은 서킷 `order-service`(4xx 는 `ignore-exceptions`)로 감싸고, 404 `ORDER_001` 만 "주문 없음" 이다. 다른 4xx · 401 `SECURITY_007` · 응답 계약 위반
  (아이디 불일치 · 금액 0 이하 · 모르는 통화 · 주문명 비었거나 100자 초과 · 기한 없음) · 5xx · 타임아웃 · 서킷 오픈은 503 `PAYMENT_011` 이다(`OrderInternalResponseSupport`).
- 도메인 예외 메시지 · 결제키 · 원인 예외 메시지는 응답 · 로그에 싣지 않는다. 5xx 로그는 코드와 `causeType` 만이다(`coding-conventions.md` §8). 요청 DTO · 명령의 `toString` 은 결제키 앞 8자만 남긴다.

### 어드민 결제 목록 (#15)

운영자는 `GET /api/v1/admin/payments`로 기존 결제 상태(UNKNOWN 포함) · 주문 아이디 · 주문 성공 회신 여부를 조합해 페이지 조회한다.
계약은 `api-design-guide.md` §2-15이며 `AdminAuthority.SETTLEMENT_READ`(ADMIN · OWNER)만 연다 — MANAGER · STAFF 는 게이트웨이를 지나도 메서드에서 403 이다(`security-guide.md` §3-1). 결제 시도 원금·상태·시각과 미확정 이유·주문 회신 거절 코드를 읽기만 하고, 결제키·고객 멱등 키·PG 원문·회원 개인정보를 싣지 않는다.
미확정 이유는 해결된 결제에도 이력으로 남을 수 있다. `orderSynced=false` 전체가 복구 대상은 아니며 DONE + false 중 null 거절 코드는 회신 미확인, 코드가 있으면 주문 거절 회신이다.
`status=REFUND_FAILED`는 조회 전용 **취소거절 이력** 필터다. PG 취소가 확정 거절된 원장(`payment_cancel.status=REJECTED`)이 하나 이상 있는 결제를 조회한다.
실제 결제 상태를 새로 만들지 않으며 응답의 `status`는 DONE · CANCELED 등 저장된 결제 상태 metadata 그대로다. PENDING 미확정은 이 필터에 포함하지 않는다.
다른 클레임의 후속 취소가 성공해 CANCELED가 되어도 거절 이력이 있으면 포함한다. 현재 미처리 환불이나 재시도 필요 여부를 판정하지 않는다.
중복 거절 원장이 있어도 결제는 한 번만 반환하며 `orderId` · `orderSynced`와 AND로 조합한다. 기존 목록 · count 두 질의에 같은 상관 EXISTS를 적용하고
`idx_payment_cancel_payment_id`를 사용 가능한 기존 인덱스로 유지한다. 새 DDL · PG 조회 · 승인/취소 재시도 · 주문 이벤트 재발행은 없다.
FE는 새 필터를 "취소거절 이력"으로 표시하고 응답 상태를 그대로 사용한다. 계약 검토자는 `seonghoho`다. 자동 복구 · 웹훅 · 알림은 후속이다.

### 리포트용 정상 승인 집계 internal (#550)

`GET /internal/v1/payments/approval-summary?from=&to=`는 `SERVICE_operations-service`만 호출한다(`@Hidden`, `Cache-Control: no-store`).
필수 시각은 Instant로 바인딩하며 UTC `[from, to)`를 조회한다. `from < to`, 최대 31일(정확히 31일 허용)이다.
응답 봉투의 `dataBody`는 `{from, to, groups:[{method(metadata), approvalCount, grossApprovedAmount:{amount, currency}}]}`다.
통화 · 결제수단 코드 오름차순이며 빈 결과는 `groups:[]`다. 금액은 최소 화폐 단위 long이고, 통화는 금액 객체 안에 한 번만 둔다.
결제키 · 주문/회원/결제 식별자 · 원문 진단값을 포함하지 않는다. FE 직접 계약 변경은 없다.

- `approvedAt >= from AND approvedAt < to`, `status IN (DONE, PARTIAL_CANCELED, CANCELED)`, `failureCode IS NULL AND abortReason IS NULL`인
  결제 시도를 통화 · 결제수단별 단일 SQL로 센다. `createdAt` · `updatedAt` · 주문 반영 여부는 조건이 아니다.
- `grossApprovedAmount`는 최초 정상 승인 원금의 합계다. 부분 · 전액 취소 뒤에도 유지하며 `LATE_APPROVED`와 늦은 승인 뒤 `CANCELED`는 두 원인 경로 모두 제외한다.
  현재 취소 시각이 없어 취소일 집계는 제공하지 않는다. 이 값은 주문 매출 · 순매출 · 환불액 · 정산액이 아니다.
- DB에서 decimal로 합산하고 `BigDecimal.longValueExact()`로 변환한다. long 합계 범위를 넘으면 요청 전체가 500 `PAYMENT_020`으로 실패하며 부분 그룹은 반환하지 않는다.
  고정 조건 조회지만 decimal 합계 · 건수 · 그룹 키의 타입을 같은 표현식으로 선택하고 추출하기 위해 이 집계는 QueryDSL custom 저장소를 유지한다.
  정적 JPQL 우선 규칙의 국소 예외이며 H2 · PostgreSQL에 같은 집계 계약을 실행해 DB별 합산 타입 차이를 검증한다.
- 시작 필수/형식은 400 `PAYMENT_118/119`, 종료 필수/형식은 `PAYMENT_120/121`, 역순 · 같은 시각 · 31일 초과는 `PAYMENT_122`다.
  다른 서비스는 403, 사용자 토큰 · 익명은 internal 인증 단계에서 401이다.
- 읽기 전용 트랜잭션 timeout 2초와 query timeout 2,000ms를 사용한다. 새 락 · 엔티티 · DDL은 없다.
  `approvedAt` 인덱스가 없어 31일 제한이 스캔 행 수를 제한하지는 않는다. 운영 데이터로 실행계획 · 지연 · timeout 빈도를 측정한 뒤 후속 인덱스를 검토한다.
  UNKNOWN 복구 등으로 정상 승인 사실이 나중에 확정되면 같은 과거 구간 결과가 달라질 수 있다. operations 리포트 연결은 #220 후속이다.

## 4. 결과 미확정(UNKNOWN) 처리

PG 호출이 **타임아웃·5xx·서킷 오픈**으로 끝나면 승인 여부를 모른다. 이것을 "실패" 로 처리하면 안 된다 —
고객 카드에서는 돈이 빠졌는데 우리는 실패로 보고 주문을 취소하게 된다.

| PG 응답 | 우리 상태 | 고객 응답 |
|---------|-----------|-----------|
| 승인 성공 | `DONE` | 결제 완료 |
| 명확한 거절 (4xx, 한도 초과·카드 거절 등) | `FAILED` (+ PG 오류 코드) | 실패 사유 표시, 재시도 가능 |
| 타임아웃 · 5xx · 서킷 오픈 | **`UNKNOWN`** | "결제 확인 중" — 몇 초 뒤 상태 조회 |

- `UNKNOWN` 은 스케줄러가 **PG 조회 API 로 재조회**해서 `DONE` 또는 `FAILED` 로 확정한다.
  - 재조회 · 오래된 IN_PROGRESS 복구 · 되돌린 READY 정리는 아래 §4-2의 별도 복구 게이트로 켠다. 기본 비활성이며 운영 결제 개방 조건(#308 · 취소/이상 조치 경로)은 별도다.
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

### 4-2. 결제 미확정 복구 (#15, S2)

복구는 `payment.recovery.enabled=false`가 기본이다. 신규 결제 게이트(`payment.enabled`)와 독립이므로 새 청구를 닫고 기존 기록만 복구할 수 있다.
복구를 켜면 승인 결과 저장에 필요한 outbox 스키마도 기동에서 검사한다. 스케줄러는 UseCase만 부르고, Facade는 트랜잭션을 열지 않는다.
짧은 건별 트랜잭션 → PG 결제키 조회(트랜잭션 밖) → 새 결과 기록 트랜잭션 순서다. 승인 API·주문 호출·DONE 미회신 재전송은 하지 않는다.

| 설정 (`payment.recovery`) | 기본 | 규칙 |
|---|---|---|
| `initial-delay` · `fixed-delay` | 각 1분 | 양수, 완료 뒤 지연이므로 PG가 느리면 실제 주기도 길어진다 |
| `unknown-inquiry-after` | 1분 | approval_started_at 기준 최초 대기 |
| `stale-in-progress-after` | 5분 | 이 시각까지 IN_PROGRESS인 건을 UNKNOWN(STALE_IN_PROGRESS)으로 커밋하고 조회 |
| `returned-ready-after` | 10분 | 결제키가 있는 READY의 조회 시작 |
| `manual-review-after` | 24시간 | 세 상태별 조회 대기 이상인 양수. 조회 횟수 제한이 아닌 자동 복구 시간 예산 |
| `page-size` · `max-pages-per-status-per-tick` | 10 · 1 | 각각 1~100 · 1~10 |

- UNKNOWN(MANUAL_REVIEW 제외), IN_PROGRESS, READY(결제키 있음)를 각각 `(approval_started_at, id)` 오름차순 keyset으로 읽는다.
  cutoff는 tick 시작에 고정한다. 상태별 커서는 다음 tick에도 유지하고 끝이면 다음 tick에서만 처음으로 돌아간다. 한 tick에서 wrap해 같은 행을 반복 조회하지 않는다.
  기본 tick당 후보·PG 조회는 각각 최대 30건(설정 상한은 3 × page-size × max-pages)이다. 재시도 행이 앞에 남아도 뒤 후보가 굶지 않게 한다.
- 로컬 중복 실행을 막는다. 다중 인스턴스의 PG 읽기 중복은 가능하며 한도는 인스턴스별이다. 저장·승인 이벤트의 중복은 @Version과 동일 트랜잭션으로 막는다.
- 건별로 상태·시간·수동확인 제외를 다시 검사한다. PG 조회 후 현재 기록이 조회 전 스냅숏(버전·상태·키·주문번호·금액/통화·주문/회원 등)과 달라지면 오래된 응답은 버린다.
  이미 DONE이면 저장·이벤트 0건이다. PG에는 회원 ID가 없으므로 회원 소유를 PG가 검증한다고 주장하지 않는다. 저장된 시도와 동일 버전의 대응을 확인한다.
- `inquireByPaymentKey`가 요청 결제키·주문번호와 응답을 대조하고 도메인이 키·금액·통화를 검증한다. DONE은 반드시 `PaymentProcessor.saveApproved`를 거쳐 outbox와 원자적으로 저장한다.
  기록/outbox 실패 시 함께 롤백하며 다음 스캔에서 조회로 복구한다. 낙관적 락 충돌은 롤백된 트랜잭션 밖에서 처리하고 뒤 후보는 계속 진행한다.
- READY에서 승인 확인 시 원래 승인 시작 시각·키·nullable 고객 멱등 키를 보존해 UNKNOWN(ALREADY_PROCESSED) → resolve → DONE으로 한 번에 기록한다.
  복구를 위해 `startApproval`을 호출하거나 새 고객 멱등 키·승인 시작 시각을 만들지 않는다.

| PG 결제키 조회 결과 | 처리 |
|---|---|
| Approved | 키·금액·통화 검증 후 DONE + 승인 outbox 1건 |
| Failed(ABORTED/EXPIRED만) | UNKNOWN은 FAILED, 되돌린 READY는 abortAfterInquiry로 ABORTED(RECOVERY_NOT_APPROVED) |
| Pending · NotFound · TIMEOUT · SERVER_ERROR | 상태를 유지하고 다음 tick. 이번 조회까지 불확정이고 24시간 예산을 넘으면 UNKNOWN(MANUAL_REVIEW) |
| CONTRACT · MANUAL_REVIEW · 키/금액 불일치 | 즉시 UNKNOWN(MANUAL_REVIEW), 자동 스캔 제외 |

24시간을 넘겼어도 이번 조회가 확정 결과면 먼저 확정한다. `NotFound`는 미승인 증거가 아니므로 실패·중단으로 바꾸지 않는다.
후보 인덱스는 `(status, approval_started_at, id)`다. 새 중단 사유의 enum check 확장과 인덱스는 운영 DDL을 먼저 적용하며, PostgreSQL CONCURRENTLY 인덱스는 트랜잭션 밖에서 만든다.

#### 배포 DDL과 검증

기존 DB에는 다음 순서로 수동 적용한다. 신규 DB의 전체 스키마는 `service/payment-service/src/test/resources/ddl/payment-schema.sql`이 정본이다.
Hibernate `update`가 기존 enum check를 바꾼다고 가정하지 않는다.

1. 복구를 끈 채 `service/payment-service/src/test/resources/ddl/payment-recovery-abort-reason.sql` 전체 DO 문을 한 번에 실행한다.
   `pg_attribute.attnum`과 `pg_constraint.conkey`가 abort_reason 단일 열인 check만 찾고, 단순 enum 표현식 및 기존 네 값 또는 알려진 새 다섯 값을 검증한다.
   예상 밖 정의·개수는 변경 없이 중단한다. 임의 생성된 기존 제약 이름을 추측하지 않으며 교차 필드 check를 보존한다.
   새 명명 제약 `ck_payment_abort_reason_values`가 같은 다섯 값을 가지면 재실행은 no-op이다.
2. 트랜잭션 밖의 autocommit 연결에서 `service/payment-service/src/test/resources/ddl/payment-recovery-index.sql`을 실행한다.
   `pg_index.indisvalid`와 `indisready`가 모두 true인지, `pg_get_indexdef(indexrelid)`가 payment.payment의
   `(status, approval_started_at, id)` B-tree인지 확인한다. 같은 이름의 인덱스가 있다고 검증을 생략하지 않는다.
   CREATE INDEX CONCURRENTLY 실패 후 INVALID 인덱스가 남으면 복구를 끈 상태에서 그 정의를 확인하고
   `DROP INDEX CONCURRENTLY payment.idx_payment_status_approval_started_at_id`를 트랜잭션 밖에서 실행한 뒤 인덱스 SQL을 재실행한다.
   `IF NOT EXISTS`는 같은 이름의 INVALID 인덱스를 복구하거나 다른 정의를 교정하지 않는다.
3. CI의 `PaymentRecoveryPostgresTest`에서 PostgreSQL 17의 실제 keyset 질의·롤백·기존 enum check 교체·교차 필드 제약 보존·재실행·인덱스 유효성을 확인한 뒤 활성화한다.
   로컬 Docker가 없는 환경의 skip은 PostgreSQL 실행 증거가 아니다. 실제 PG 접속·운영 데이터 EXPLAIN·운영 DDL 적용은 이 테스트로 대체하지 않는다.

구현은 `PaymentRecoveryFacade`의 NEVER 경계, `PaymentRecoveryScanProcessor`의 후보별 격리,
`PaymentRecoveryProcessor.prepare/finish`의 REQUIRES_NEW로 나뉜다. 스캔의 건별 재조회는 동시 요청의 상태·버전을 검사하기 위한 트랜잭션 경계이며 목록 부가정보를 채우는 N+1 조회가 아니다.

#### 운영자 수동 확인

- MANUAL_REVIEW는 재승인 허가가 아니다. 복구를 잠시 끄고 어드민 결제 목록의 내부 결제/주문 ID로 대상을 좁힌 뒤 토스 상점관리자의 주문번호·키·원금·통화·승인/취소 상태를 안전한 접근 경로로 확인한다.
- 승인됐으면 결제·주문·재고 상태와 이벤트 존재를 대조한다. 이 PR에는 수동 확정·전액 취소 API가 없으므로 임의 SQL로 DONE을 쓰지 않는다(outbox를 누락할 수 있다).
  확인 증거를 남기고 별도 운영 승인/복구 절차로 처리하며, 취소가 필요하면 승인된 취소 경로가 준비될 때까지 운영 결제 개방을 보류한다.
- PG 자체가 미확정이거나 응답 계약/금액이 다르면 사람 확인을 유지한다. 단순 404·재시도 횟수만으로 주문을 버리거나 새 결제를 받지 않는다.
- 과거 SQL/이관으로 READY에 결제키만 있고 approval_started_at이 없는 행은 시간 기반 자동 후보에서 제외된다. 시각을 추측해 채우지 말고 별도로 찾아 상점관리자 증거로 확인한다.
- 알림 전달은 #477, 주문 회신은 #308 PR-C, 취소·웹훅은 #15 후속이다. 이번 복구만으로 운영 결제 게이트를 자동으로 열지 않는다.

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
  `resolve` · `recoverReturnedReady` · `flagForManualReview` · `recordLateApproval` · `markOrderSynced` · `markOrderSyncFailed`(#14). 표 밖 전이는 `PaymentTransitionException`(현재 상태 · 시도한 동작)이고
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

#14 C2b는 주문 반영 전 프로세스 종료 뒤 결제가 CANCELED/PARTIAL_CANCELED로 바뀐 경우에도, 원승인 정보와 주문 소유를 검증하고 이미 확정한 선점을 복원한다.
최초 거절 reason은 PAYMENT_NOT_DONE으로 유지하며 내부 선점·claim을 외부 조회에 노출하지 않는다(기존 7필드, NOT_DONE의 stockReservationId=null).
pending 의도는 같은 claim으로 재개하고 전액취소 원천에는 추가 거절 회신을 보내지 않는다. 주문 PAID 소유·기결제 legacy는 자동 복원하지 않는다.
자세한 자격·재전달·운영 DDL/중단시험 범위는 `order-flow-guide.md` §3-6, `deploy-guide.md`의 C2b 절을 따른다. 소비/운영 결제 게이트 활성화 승인은 포함하지 않는다.

**C1a 원천 조회 API**: order-service의 `GET /internal/v1/order-payments/{paymentId}`는 저장된 최초 반영 결과만 반환한다.
정확한 7필드·인가·오류 계약은 [order-payment-result-guide.md](order-payment-result-guide.md)를 따른다.
이 API 추가는 ACK 소비·inbox·회신 outbox·재고 확정 뒤 중단 창 복구를 완료하지 않는다. #308 C·#15 운영 개방 조건과 기존 게이트는 유지한다.

**C1b1 호출 경계**: payment의 `OrderPaymentResultQueryPort`가 C1a를 서비스 토큰으로 조회하고 결과별 응답 계약을 검증한다.
미반영(`ORDER_028`)·인증/통신 실패·응답 계약 위반을 구분하며, 원격 본문·Feign 원인을 전파하거나 어댑터·Feign 재시도를 추가하지 않는다.
정확한 실패 분류는 위 가이드의 "payment 호출 경계"를 따른다. 아직 이벤트/로컬 결제 대조·ACK 소비·inbox·플래그 저장은 연결하지 않는다.
`order-paid.paidAt`는 주문 처리 시각이고 원천 `approvedAt`는 PG 승인 시각이므로 서로 같다고 비교하지 않는다.
C3b의 `OrderPaymentReplyEventUseCase`가 원천과 로컬 결제의 승인 시각을 마이크로초 정밀도로 대조하고 payment/inbox를 원자 기록한다. Kafka 진입은 C3c 후속이다.

결제 승인(DONE)을 주문의 결제 완료(PAID)로 반영하는 단계는 **Kafka 이벤트**다(#304 · #308, `architecture-guide.md` §8-1). 동기 internal 호출 + 재전송 플래그 + 스케줄러로 하던 이전 설계는
쓰지 않는다 — 전달은 outbox 릴레이(적어도 한 번)와 컨슈머 재시도 · DLT 가 보장하고(소비 · 회신 쪽은 PR-B · C 예정), 중복은 inbox · 유니크 키 · `@Version` 이 막는다.
**payment 에 재전송 스케줄러를 두지 않는다.** **#308 이 끝나기 전에는 운영 결제를 열지 않는다(`payment.enabled=false`, §3-4).**

| 이벤트 | 토픽 · 키 | 생산 → 컨슈머 그룹 | 페이로드 (v1, 아이디는 문자열) | 상태 |
|--------|-----------|--------------------|-------------------------------|------|
| `payment-approved` | `<ns>.payment.payment-approved` · orderId | payment → `<ns>.order.payment-approved` | `paymentId` · `orderId` · `memberId` · `amount`(최소 단위 정수) · `currency` · `approvedAt` · `method`(수단 코드) | 발행 구현(PR-A1) · 소비 PR-B 예정 |
| `order-paid` | `<ns>.order.order-paid` · orderId | order → `<ns>.payment.order-payment-result` | `orderId` · `paymentId` · `stockReservationId` · `paidAt` | PR-B 예정 |
| `order-payment-rejected` | `<ns>.order.order-payment-rejected` · orderId | order → `<ns>.payment.order-payment-result` | `orderId` · `paymentId` · `reason`(`ORDER_CANCELED` · `ORDER_NOT_FOUND` · `PAYMENT_MISMATCH` · `STOCK_UNAVAILABLE` · `ALREADY_PAID` · `PAYMENT_NOT_DONE`) | PR-B 예정 |

- **발행(payment, 구현)** — `PaymentProcessor` 가 DONE 저장과 `PaymentEventPort.approved` 를 한 트랜잭션에서 한다(`saveApproved` 하나로 묶었다 — #15 의 UNKNOWN 재조회 확정도
  이 메서드로 DONE 을 저장해야 한다). 전이가 멱등 no-op(이미 DONE, 같은 인스턴스)이면 저장도 발행도 하지 않는다 — 같은 결제의 승인 이벤트가 두 번 나가지 않는다.
  발행이 실패하면 DONE 저장도 롤백돼 IN_PROGRESS 로 남고 500 `PAYMENT_013` 이다 — 복구는 §4-2의 별도 게이트 재조회가 담당한다. DONE 이 아닌 결과(FAILED · UNKNOWN ·
  READY 되돌림 · 금액 불일치 UNKNOWN)는 발행하지 않는다. 페이로드에 PG 결제키 · 카드 정보 · 주문명 · 개인정보를 싣지 않는다(messaging-core 코덱의 금지 필드가 이름으로도 막는다).
  금액은 통화의 최소 단위 정수다(KRW 원 · USD 센트).
  메시징이 꺼져 있어도(`MESSAGING_ENABLED=false`) outbox(`payment.outbox`)에는 쌓이고 릴레이만 멈춘다.
  **결제 게이트(`payment.enabled`) 또는 복구 게이트(`payment.recovery.enabled`)가 열려 있으면 `payment.outbox` · `payment.shedlock` 이 없을 때 기동하지 않는다**(`PaymentOutboxSchemaGuard`) — 운영 DDL 을 빠뜨린 채
  PG 승인 뒤 매 결제가 500 이 되는 것을 막는다. 게이트가 닫혀 있으면 WARN 만 남긴다.
- **진위 확인(order, PR-B 예정)** — 봉투에는 서명이 없다. order 컨슈머는 이벤트를 알림으로만 보고 payment internal 결제 조회(`GET /internal/v1/payments/{paymentId}`, PR-A2 구현)로
  재조회해 상태 · 금액 · 통화 · 주문 · 회원을 확정한다. 두 불일치를 나눈다 — **이벤트 ↔ 재조회** 불일치(이벤트가 말한 주문 · 금액이 payment 원본과 다름 — 위조 · 버그)와
  재조회 404 는 DLT 로 보내고 주문을 바꾸지 않는다. **재조회 ↔ 주문** 불일치(payment 원본의 금액 · 통화 · 회원이 주문과 다름)는 `PAYMENT_MISMATCH` 거절 회신이다.
  재조회가 DONE 이 아니면(이미 취소 · 부분 취소 등) WARN 과 `order_payment` 기록(`REJECTED_PAYMENT_NOT_DONE`)을 남긴다(판정은 PR-B2).
  - **조회 계약(payment, 구현)** — `PaymentInternalController`, 허용 호출자 `SERVICE_order-service` 하나(`security-guide.md` §9-4). 회원을 거르지 않는다(서비스 간 조회).
    응답 `{paymentId, orderId, memberId, status{code, name, description}, amount{amount, currency}, method{code, name, description} | null, approvedAt | null}` —
    아이디는 문자열, `status.code` 가 `PaymentStatus` 제어 코드다. 결제키 · 고객 멱등 키 · 주문명 · PG 원문 상태 · 간편결제 제공자 · 실패 사유 · 미확정 이유 · 주문 반영 기록은 내리지 않는다.
    없으면 404 `PAYMENT_001`, `paymentId` 가 양수가 아니면 400 `PAYMENT_115`, 숫자 · long 범위가 아니면 400 `PAYMENT_198`. 결제 게이트(`payment.enabled`)를 보지 않는다.
  - **호출(order, PR-B1 구현)** — `PaymentClient`(서비스 토큰, `contextId=paymentClient`) · `PaymentInternalResponseSupport`(서킷 `payment-service`, 4xx 는 세지 않음,
    timeout 은 Feign 기본 connect 2s · read 3s) · `PaymentQueryPort#findPayment`. 404 `PAYMENT_001` 은 빈 결과, 타임아웃 · 5xx · 서킷 오픈은 `UNAVAILABLE`(재시도),
    401 · 403 은 `UNAUTHORIZED`(재시도 — 키 교체 · 허용 호출자 설정 실수 시간대의 승인이 바로 DLT 로 가 "돈 받고 주문 만료" 가 되지 않게),
    그 밖의 4xx(400 등)는 `REJECTED`(비재시도), 다른 결제 · 아이디 · 금액 · 통화 · 상태 형식 · DONE 의 승인 시각 없음은 `CONTRACT_VIOLATION` 이다(`PaymentQueryException`).
    결과의 주문 · 회원 · 금액 · 통화 대조는 컨슈머(PR-B2)가 한다.
- **재고(order, 확정 · 재선점 PR-B1 구현 · 컨슈머 연결 PR-B2)** — 재고 선점이 만료된 뒤 결제가 완료되는 경우(고객이 결제창을 오래 열어 둠)가 있다. 확정이 409 `PRODUCT_025`(이미 해제 · 만료)면 같은 주문으로
  재선점(`POST /internal/v1/stock-reservations`)하고 **곧바로 확정**한다. 재선점이 `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면 `STOCK_UNAVAILABLE` 로 거절 회신한다.
  **주문은 선점 확정 200(`CONFIRMED`)을 받은 뒤에만 PAID 로 둔다** — 같은 이벤트를 다시 받아도 확정을 멱등으로 다시 부른다 (`order-flow-guide.md` §3-4).
  재선점은 결제 대기 · 만료 주문만 한다(선점이 이미 풀린 결제 대기 주문 포함). 결제 전 취소된 `CANCELED` 주문은 거절(`ORDER_CANCELED`)이다.
  확정 뒤 취소가 이긴 경합(확정 뒤 끊긴 복구창 포함)은 해제 프로브로 확정 선점을 찾아 `order_payment.restoration_claim_id` 로 재고를 복원한 **뒤에**
  `ORDER_CANCELED` 를 회신한다(#308 PR-C1, `order-flow-guide.md` §3-6).
  C2a는 같은 order/reservation의 모든 복원 의도(대기/완료)를 confirm 전 · 최종 PAID 전에 검사하고 기존 주문 NOWAIT 잠금 아래 의도/PAID 결정을 직렬화한다.
  `PRODUCT_028`은 부분 복원일 수도 있어 의도 유지 · DLT · 운영 확인이며 restored_at/inbox/회신을 쓰지 않는다. 확인 절차는 위 가이드 §3-6을 따른다.
  order 의도 없는 외부 복원은 기존 confirm 응답으로 판별할 수 없다. 소비 OFF/drain 뒤 모든 order 인스턴스에 적용하고 게이트는 유지한다.
  취소/부분취소 재전달의 NOT_DONE 복구와 내부/외부 계약 정렬은 C2b이며 C2a만으로 전체 결제 복구 완료를 뜻하지 않는다.
- **회신과 `order_synced`(payment, PR-C 예정)** — 두 칸은 그대로 두고 뜻을 **"주문의 회신 이벤트를 받았는가"** 로 바꾼다(DONE 계열만 쓴다 — LATE_APPROVED 는 주문에 반영하지 않는다, #14):

  | `order_synced` | `order_sync_failure_code` | 뜻 | 처리 |
  |----------------|---------------------------|----|------|
  | false | null | 아직 회신을 받지 않았다 | 릴레이·컨슈머 재시도 뒤 유실/DLT는 §6-3 재대조 또는 수동 처리 |
  | true | null | `order-paid` 를 받았다(주문 PAID 확정) | 없음 |
  | false | 코드 | `order-payment-rejected(reason)` 를 받았다 — 다시 보내도 바뀌지 않는다 | ERROR 로그 · 지표 + **자동 전액 취소 · 어드민 확인** 대상 |
  | true | 코드 | 금지(check) — 반영 성공 뒤 늦게 온 거절은 무시한다 | — |
- **전액 취소** — C3b의 최초 유효 거절·잔액 존재 시 커밋 후 비PG `PaymentCancelPolicy` 훅을 최대 1회 시도한다. DB 거절 재시도는 §6-3, 실제 PG 취소는 #579다.

#### C3b 기록·빠른 훅 경계

Facade는 외부 TX를 거절한다. 원천 조회와 훅은 TX 밖, 로컬 원승인 금액·회원·주문·통화·시각 대조 및 payment/inbox 기록은 하나의 짧은 TX다.
원격 조회·저장소 실패 분류·정책 포트는 Processor만 호출한다. QueryResult는 Processor 안에서 application 스냅숏으로 바꾸고 Facade에는 노출하지 않는다.
정상 DONE 기원의 DONE/PARTIAL_CANCELED/CANCELED만 기록하고 성공 단조성·같은 결과 no-op을 유지한다. inbox 정리 뒤에도 업무 멱등성으로 훅을 재시도하지 않는다.
훅 후보는 최초 거절 기록이며 CANCELED가 아니고 성공 취소 누계를 뺀 잔액이 양수인 경우다. 후보는 실행 권한이 아니며 #579는 실행 직전 상태·잔액을 다시 확인해야 한다.
기본 정책은 PG를 호출하지 않는다. 고정 enum 로그만 남기며 훅·관측 예외는 기록 커밋을 되돌리지 않는다. 커밋 후 훅 전 중단과 훅 예외의 실행 유실은 #591이 복구한다.
commit/unique/version 실패는 TX 밖에서 분류하며 원문·식별자·cause를 전파하지 않는다. 정확 inbox 제약+23505만 중복이고 다른 무결성 위반은 영구 계약 실패다.
기록 계약은 PR #654로 완료했다. Kafka 진입은 아래 C3c가 연결하며 운영 게이트 활성화는 별도다.
  돈만 받고 상품이 없는 주문을 남기지 않는다.

### 6-1. payment 회신 저장소 기반 (#308 C3a-1)

정상 DONE 기원의 DONE · PARTIAL_CANCELED · CANCELED는 회신 기록을 허용한다. LATE_APPROVED와 FAILED/ABORTED에서 늦게 승인된 뒤 CANCELED가 된 시도는 거절한다.
같은 성공·같은 거절 코드는 no-op이며 성공 뒤 늦은 거절은 성공을 덮지 않는다. 원승인 금액·통화·시각과 기존 생성자/DB 제약은 유지한다.
이 단계에는 생산 회신 Processor·리스너가 없다. payment 저장과 inbox를 묶는 시험은 저장소 계약이며 실제 소비 완료를 뜻하지 않는다.

`payment.processed_event`의 `(consumer_group,event_id)` unique가 동시 중복의 최종 방어다. `record`는 MANDATORY로 업무 tx에 INSERT하며,
tx 밖 호출은 실패한다. 중복 INSERT나 후속 업무 실패는 같은 tx 전체를 롤백한다. `exists` 선조회만으로 경합을 막지 않는다.
정리는 `< cutoff`를 `(processed_at,id)` 순으로 batch 제한 조회한 뒤 해당 ID만 bulk 삭제한다. 이전 batch가 커밋된 뒤 실패하면 실패 batch만 롤백되고 다음 주기에 재개한다.

현재 core는 messaging 또는 relay OFF면 relay·cleanup을 모두 정지한다. 둘 ON이고 cleanup OFF면 relay만 가능하며, 모두 ON이면 outbox와 inbox를 정리한다.
listener OFF는 cleanup OFF가 아니다. **cleanup OFF라도 배포 전 `ddl/payment-processed-event.sql` 적용이 필수**다(`deploy-guide.md` C3a-1 절).
기본 inbox 보존 7d 이후의 재전달은 inbox가 막지 못한다. C3c 소비 활성화 전에 토픽·DLT 보존/재시도/수동 재처리 창과 후속 도메인 멱등성을 함께 확인한다.
C3a-2 지표와 기존 #616 A4a 관측 API는 별도 범위이며 #550은 approval-summary다. 기존 관측 API의 의미는 유지한다.
실제 회신 소비·PG 취소 #579 및 소비/결제 게이트 활성화는 후속 범위다. 재대조 구현은 §6-3을 따른다.

### 6-2. 주문 회신 지연 관측 (#308 C3a-2)

`PaymentOrderSyncMetrics`는 스케줄러·캐시 없이 scrape 때 세 질의를 각각 실행한다. `payment.order-result.pending-after`는 기본 5m이며 0은 허용하고 음수는 기동을 거절한다.

| 지표 (`commerce.payment.order-sync.` 접두사) | 모집단·단위 |
|---|---|
| `pending.count` | DONE/PARTIAL_CANCELED + orderSynced=false + 거절 코드 없음 + approvedAt < observedAt-pendingAfter |
| `pending.oldest.age` | 같은 모집단의 최소 approvedAt부터 observedAt까지 전체 경과 초(baseUnit=seconds), 기준 시간을 뺀 초과분 아님 |
| `rejected.count` | DONE/PARTIAL_CANCELED + orderSynced=false + 거절 코드 있음, 시간 제한 없음 |
| `observation.failures` | 실패한 Gauge callback 횟수(셋 모두 실패하면 3 증가) |

strict `<`이므로 동등·미래 승인 행은 pending에서 제외한다. 정상 빈 집합은 0, DB 실패나 불가능한 반환값은 해당 Gauge만 NaN이며 다음 관측에서 재조회한다.
콜백마다 Clock을 한 번 읽고 count/min은 같은 조건을 사용하지만 독립 트랜잭션이므로 원자 snapshot·상호 합산·업무 상태 판정 근거로 쓰지 않는다.
각 질의는 read-only tx timeout 2초와 JPA query timeout 2000ms를 사용하며 풀 대기·전체 scrape의 2초 상한은 보장하지 않는다. 예외 원문/cause·ID를 로그/태그에 넣지 않는다.
기존 #616 A4a의 `doneOrderUnsynced`는 DONE+false(거절 포함)를 유지하며 미래 시각 오류 검출도 그대로다. #550 approval-summary는 바뀌지 않는다.
PostgreSQL 시험은 ACCESS EXCLUSIVE 잠금으로 실제 취소·rollback·같은 풀 연결 회복을 확인하고, PG17 합성 10k/100k의 정상·미회신/거절 backlog에서
ANALYZE 후 실제 Hibernate 생성 SQL·동일 바인딩의 EXPLAIN(ANALYZE,BUFFERS)을 first/repeat로 기록한다. 운영 분포 효율이나 cold cache를 보장하지 않으며 생산 인덱스를 추가하지 않는다.
이 절은 구현 계약이며 실제 시험 실행·로컬 Docker skip·CI PG 실행 결과는 PR 검증 근거로 구분한다.

### 6-3. 회신 재대조와 DB 거절 재시도 (#591 payment 범위)

`payment.order-result.reconciliation.enabled`는 기본 **false**다. 메시징/listener/운영 결제 게이트와 독립이며 이번 변경으로 어느 게이트도 열지 않는다.
DLT 또는 회신 유실 뒤 기존 `OrderPaymentResultQueryPort` 원천으로 결과를 재대조한다. 원천에 결과가 없거나 실패하면 다음 cycle에서 재시도하며 운영자가 수동으로 원인을 확인할 수 있다.

| lane | DB 후보 |
|---|---|
| RECONCILE | DONE/PARTIAL_CANCELED + order_synced=false + 거절 코드 없음 + approved_at < cycle 시작 시각 - 기존 pending-after(기본 5m) |
| REJECTED | DONE/PARTIAL_CANCELED + order_synced=false + 거절 코드 있음. 시간 cutoff 없음 |

두 lane은 `payment.order_reconciliation_cursor`의 별도 행으로 순회한다. cycle 시작에 전체 결제의 최대 ID와 승인 cutoff를 고정하고
`last_id < id <= cycle_upper_id`를 ID 오름차순·limit으로 읽는다. 신규/나중에 자격을 얻은 행은 다음 cycle에서 방문한다.
후보가 없으면 cursor를 0으로 되돌리고 해당 tick의 lane을 끝낸다. 마지막 페이지 뒤 빈 조회가 cycle을 닫으므로 작은 backlog는 빈 tick이 하나 생길 수 있다.
실패한 앞부분/계속 유입되는 큰 ID/재시작이 뒤 후보를 계속 굶기지 않도록 **배치 예약 cursor를 먼저 별도 TX로 커밋**한다.
예약 커밋 뒤 작업 중 중단되면 그 배치는 다음 cycle에서 재방문한다. 프로세스가 살아 있고 DB 예약이 성공하는 동안 유한한 cycle을 순회하며, 처리 완료 큐나 exactly-once 실행을 뜻하지 않는다.

기본 initial-delay/fixed-delay는 각 1m, page-size=10(1~100), max-pages-per-lane=1(1~10), lock-at-most-for=10m이다.
lane별 예산과 실패 처리는 독립이다. 건별 작업에서 기존 단건 원천 API를 재사용하며 새 벌크 API를 추가하지 않는다.
기존 connect/read timeout·서킷과 record TX timeout을 유지한다. 배치 한도는 작업 수의 상한이며 전체 tick의 벽시계 시간 상한은 아니다.

ShedLock 이름은 `payment-order-reconciliation`, 테이블은 서비스 운영 계약상 **payment.shedlock**으로 고정한다.
`commerce.messaging.outbox.relay.lock-table`도 같은 값이어야 하며 스위퍼 ON에서 다른 값이면 기동을 거절한다.
core 릴레이/cleanup의 락 이름과 다르고 LockProvider 빈을 새로 노출하지 않아 core의 provider 선택을 바꾸지 않는다.
실행이 lock-at-most-for를 넘으면 동시 실행 가능성이 있으므로 호출 timeout·페이지 예산에 맞춰 lease를 설정한다. 예약 행 잠금과 payment @Version/inbox unique는 겹친 실행에도 최종 기록을 보호한다.

원천 조회는 한 후보당 한 번, TX 밖에서 수행한다. `payment-order-reconcile:v1:<paymentId>`의 UTF-8 바이트로 만든 name UUID를
기존 `order-payment-result` inbox 그룹에 기록한다. 같은 C3b record를 사용해 원천/로컬 주문·회원·원승인 금액/통화/마이크로초 시각·승인 기원을 재검증하고
payment/inbox를 같은 TX로 커밋한다. 실제 이벤트와의 경합은 @Version/unique로 롤백하고 후속 진입에서 판정한다. 성공 단조성과 같은 결과 no-op을 유지한다.

REJECTED lane은 inbox/빠른 훅 성공 이력을 보지 않고 `PaymentCancelPolicy.onRejected(paymentId)`를 TX 밖에서 반복 호출한다.
#579의 기본 OFF 정책은 취소 의도만 기록한다. 실제 PG 호출은 별도 자동 취소 실행자가 맡는다. 같은 tick의 빠른 훅과 DB lane이 중복 호출할 수 있다.
원천 거절은 재고 복원 진행 중에도 존재할 수 있다. 거절 기록/정책 전달만 수행하고 복원 완료·실행 권한을 추정하지 않는다.
#579는 실제 실행 직전 상태·잔액·멱등성을 재검증해야 하며, 이번 변경은 재고 복원 API나 그 완료 신호를 추가하지 않는다.

`commerce.payment.order-sync.reconciled{result}` counter는 재대조가 **커밋 후 changed=true**인 경우만 증가한다.
result는 PAID/여섯 REJECTED의 고정 enum이며 ID·원문·cause를 로그/태그에 넣지 않는다. 중복/no-op/롤백/정책 재시도는 세지 않는다.
커밋 직후 프로세스 종료로 counter 증가를 놓칠 수 있어 회계 원장이 아니다. 기존 `pending.oldest.age`와 core의 실제 DLT counter를 재사용하고,
재대조 실패를 DLT 발행 횟수에 합산하지 않는다. operations의 기본 OFF DLT 관리자 알림은 `order-paid`·`order-payment-rejected` DLT 두 토픽도 CRITICAL로 구독한다. 토픽 생성·ACL을 확인한 뒤 별도 활성화하며, 알림은 복구 완료 증거가 아니다.

운영 DDL은 `service/payment-service/src/test/resources/ddl/payment-order-reconciliation.sql`이다. 새 cursor 테이블의 CREATE·comment·check와 기존 payment 테이블의 후보 복합 인덱스는
Hibernate 7.2.24/PostgreSQL 17 출력에서 추출했다. lane PK가 두 행 조회 인덱스를 겸하며 기존 인덱스·payment 행은 보존한다.
PG17의 추가 인덱스 전 10만 행 편향 계획은 `payment_pkey`로 99,980행을 필터링했다(약 17ms/26ms, shared hit 3,598/3,391). 인덱스 추가 후 같은 CI 합성 분포의 첫 페이지는 두 lane 모두 새 인덱스를 선택하고 필터 제거 0행이었다. RECONCILE은 Index Scan·shared hit 25·실행 0.096ms, REJECTED는 Index Only Scan·shared hit 1,378·실행 1.529ms였다(CI run 42374033251). REJECTED의 버퍼 사용은 계속 관찰하며 이 합성 측정은 운영 분포의 성능 보장이 아니다.
구 DB에 **스케줄러 OFF → DDL 전체 트랜잭션 적용(기존 payment 후보 인덱스와 두 lane seed 포함) → validate/배포 → 운영 선행 조건 확인 후 별도 활성화** 순서다. 인덱스 생성은 기존 payment 쓰기를 막을 수 있으므로 결제 게이트를 닫고 진행 중인 승인·취소·복구·웹훅 쓰기를 drain한 작업 창에서 적용한다.
기존 payment 행·컬럼·제약 변경은 없고 인덱스만 추가한다. 새 DB 전체 스냅숏은 별도로 적용한 뒤 운영 스크립트의 seed INSERT만 적용한다.
Hibernate create/update는 seed를 만들지 않는다. 스위퍼 ON의 schema guard는 cursor 칼럼·두 seed·inbox·shedlock을 확인하고 누락이면 원문 없이 기동 실패한다.
DDL 중간 실패는 rollback하고 catalog/기존 행을 확인한 뒤 재적용한다. 성공 뒤 스크립트를 다시 실행하거나 진행 cursor를 초기화하지 않는다.
배포 rollback은 스위퍼 OFF와 구 버전 재배포로 수행하고 추가 테이블/진행값을 보존한다.

H2/PG 계약·동시 lock/실제 이벤트 경합·구 스키마→추가 DDL→validate 시험을 추가했다. 이 slice의 Gradle 실행은 공유 build.lock을 소유한 main이 수행한다.
현재 컴파일/시험 성공을 주장하지 않으며, adapter 재생성 시험은 실제 JVM 종료·재기동 시험을 대신하지 않는다. FE/HTTP 계약 영향은 없다.

### 6-4. Kafka 회신 소비 (#308 C3c)

`OrderPaymentReplyEventListener`는 `TopicNames`의 `order.order-paid`·`order.order-payment-rejected` 두 원본 토픽만 소비한다.
그룹은 기존 inbox와 같은 `<ns>.payment.order-payment-result`다. producer/aggregate는 order, 키는 aggregateId 및 payload의 문자열 orderId와 같아야 한다.
UUID/v1 봉투·문자열 양수 long 식별자·PAID 선점/시각·여섯 거절 사유를 검증한다. `paidAt`는 주문 처리 시각이므로 PG 승인 시각과 비교하지 않는다.
이벤트는 알림이며 기존 C3b `handle`이 서비스 토큰 원천 7필드와 로컬 payment를 다시 대조한다. 새 API·스키마·PG 정책은 없다.

`commerce.messaging.enabled`와 `payment.order-result.consumer.enabled`가 모두 true일 때만 등록한다. 전용 소비자는 기본 false다.
두 원본 토픽·두 `.dlt`가 없으면 기동을 거절하며 앱은 토픽을 생성하지 않는다. 기존 메시징만 켜진 인스턴스에는 회신 토픽을 새로 요구하지 않는다.
운영 결제·재대조·자동취소 게이트 값은 그대로 유지한다. 소비자 구현 완료만으로 운영 활성화나 #308 전체 완료를 뜻하지 않는다.

공용 컨테이너의 자동 커밋 OFF·RECORD ACK·지수 재시도·DLT를 재사용한다. `handle`의 DB 커밋과 커밋 후 훅 시도가 끝나고 정상 반환한 뒤 offset을 커밋한다.
기존 `Reason.retryable()`의 일시 실패는 제한 재시도한다. 영구 실패와 잘못된 봉투는 즉시 DLT다. SOURCE_UNAUTHORIZED도 키 교체/배포 복구 시간을 위한 제한 재시도다.
기본 재시도는 총 5회, 1/2/4/8초 대기이며 동일 파티션의 후속 주문 처리가 지연될 수 있다. 시간 상한에는 원격 호출·DB 대기 시간이 추가된다.
DB 커밋 뒤 ACK 전 중단은 같은 그룹의 재전달로 복구한다. inbox가 있으면 중복 반환하고, 정리 뒤에는 원천 재검증과 업무 no-op·성공 단조성에 의존한다.
훅 전 중단/훅 예외에서 빠른 훅은 복구되지 않을 수 있다. 기존 #591의 DB 재방문과 #579의 의도 발견·별도 PG 실행이 보완한다.

DLT 진단은 공용 정책의 토픽·partition·offset·정규 event UUID·예외 타입만 허용한다. payment/order/member ID·record key·금액·payload·원격 본문·cause는 로그에 추가하지 않는다.
DLT 본문/키는 재처리를 위해 원본을 보존하지만 예외 메시지·stack 헤더는 제거한다. Kafka 접근 ACL과 기존 개인정보 없는 이벤트 계약이 전제다.

수동 재처리는 다음 순서로 수행한다. DLT는 자동 소비하지 않으며 #591도 DLT 레코드를 재발행하지 않는다.

1. 원인·원천 결과·현재 payment와 inbox를 확인하고 계약/인증/장애 원인을 먼저 해결한다. DLT의 토픽·partition·offset을 운영 원장에 남긴다.
2. 같은 namespace의 대응 원본 토픽으로 **동일 eventId·key·봉투**를 재전송한다. 새 eventId 생성이나 DB 플래그 직접 변경으로 우회하지 않는다.
3. 새 원본 offset의 처리와 payment/inbox·실제 취소 원장을 확인한다. 재전송 성공 자체를 업무 완료 또는 환불 완료로 보지 않는다.

기본 원본·DLT 보존은 각각 7d, inbox 보존은 processedAt 기준 7d다. 순차 대기한 두 Kafka 창의 합이 inbox 보존보다 길 수 있어 중복 제거를 항상 보장하지 않는다.
운영 활성화 전에 실제 topic retention·재시도·수동 처리 목표 시간을 확인한다. 창 초과/영구 불일치는 수동 조사하고 보존시간만 늘려 자동 해결로 간주하지 않는다.
listener OFF와 cleanup OFF는 독립이다. cleanup은 기존 messaging/relay/cleanup 조건을 유지한다.

## 7. 웹훅

수신·영속 inbox·PG 재조회·재시도·수동 확인 및 활성화 절차는 [payment-webhook-guide.md](payment-webhook-guide.md)를 따른다 (#15).

## 8. 취소 · 환불

- 환불 금액(배송비 차감, 부분 반품)은 **order-service 의 클레임 정책이 계산**하고 payment-service 는 실행만 한다.
- 취소 요청도 `Idempotency-Key` 를 싣는다. 키는 클레임 ID 처럼 재시도해도 같은 값이어야 한다.
- PayPal 환불은 수수료가 돌려받아지지 않을 수 있다 — 환불 정책 문구와 어드민 화면에 반영한다.

### order → payment 결제 후 취소 호출 (#16 PR-2)

- **경로** — 고객 결제 후 취소(`POST /api/v1/orders/{orderId}/cancellation`)가 클레임을 접수(tx1)한 뒤 트랜잭션 밖에서
  `POST /internal/v1/payments/{paymentId}/cancellations` 를 부른다(`PaymentCancelPort` → `PaymentCancelClientAdapter`). 미확정이면 재시도 스케줄러
  (`ClaimCancellationRetryScheduler`, `order.cancellation.retry.*`)가 같은 경로로 다시 부른다. 흐름 · 상태는 `order-flow-guide.md` §5 가 정본이다.
  재시도 스케줄러가 꺼져 있으면(`order.cancellation.retry.enabled=false`, 기본) 결제 후 취소 자체를 받지 않는다(503 `ORDER_035`) — 미확정 환불이 영영 따라오지 않는 창을 막는다.
- **본문 · 키** — `Idempotency-Key` = 클레임 아이디, 본문 `{claimId, amount, currency}`. 결제 아이디 · 금액은 **접수 때 클레임에 저장한 값만** 보낸다 — 재시도에서 다시 계산하지
  않는다(키가 같은데 금액이 바뀌면 `PAYMENT_019`). 접수 때 환불액(전체 취소 = 주문 총액)이 `order_payment` 승인 금액과 다르면 접수하지 않는다(500 `ORDER_034`),
  결제 완료 기록이 없으면 500 `ORDER_033` 이다.
- **분류** (`PaymentCancelInternalResponseSupport`) — 200 → 환불 확정(같은 클레임의 기존 성공 포함). `PAYMENT_016` · `017` · `019` · `001` · 400 `PAYMENT_1xx` → 종결 거절
  (클레임 `REFUND_FAILED` + 그 코드, 운영자 확인). 그 밖(503 `PAYMENT_018` · `PAYMENT_012` · 타임아웃 · 5xx · 모르는 4xx · 401/403 · 서킷 오픈 · 계약 위반 ·
  Feign 밖 예외) → 미확정 — 같은 클레임 · 같은 본문으로 백오프 재시도, `max-attempts` 를 넘으면 `REFUND_FAILED`(`REFUND_UNCONFIRMED`). 키 유효 기간(14일) 안에서
  끝나도록 `max-backoff × max-attempts ≤ 14d` 를 기동 때 검사한다.
- **타임아웃 · 서킷** — `paymentCancelClient`(contextId) read timeout **10초**(PG 취소 대기, 조회 3초와 따로), 서킷 `payment-cancellation` 은 조회 서킷
  `payment-service` 와 분리한다 — PG 취소 장애가 승인 반영 재조회를 막지 않게. 클레임 lease(`order.cancellation.retry.lease`, 기본 2분)는 read timeout 보다 충분히 길어야 한다.

### 전액 취소 실패 필드 제약 보정 (#551)

배포 전 `service/payment-service/src/test/resources/ddl/payment-canceled-failed-fields.sql`을 운영자가
`psql -v ON_ERROR_STOP=1 -f <파일>`로 적용한다. 기존 `ck_payment_failed_fields`의 CANCELED 허용 누락만 고친다.
정상 전액 취소와 FAILED/ABORTED 기원의 늦은 승인 전액 취소를 허용하며 원 승인액 · 승인 시각 · 실패/중단 원인을 보존한다.
FAILED 실패 코드 필수 · 메시지/코드 쌍 · 다른 상태 제약은 유지한다. 공개 API · PG 호출 · 금액 · 멱등 · 락 정책은 바뀌지 않는다.

제약 교체는 한 트랜잭션에서 기존 이름으로 즉시 검증한다. `ACCESS EXCLUSIVE` 잠금과 전체 기존 행 검사가 필요하므로 쓰기 트래픽이 낮을 때 적용한다.
잠금 대기는 2초, 문장은 30초 상한이며 실패하면 롤백된다. 제약이 없으면 실패하고, 정상 적용 뒤 같은 스크립트를 다시 실행할 수 있다.
적용 뒤 `pg_constraint`에서 해당 제약이 하나이고 `convalidated=true`인지 확인한다. Hibernate 스냅숏은 `ddl/payment-schema.sql`이다.
이전 제약 때문에 PG 취소 성공 후 DB 기록이 실패한 건은 원장이 PENDING일 수 있다. 임의 SQL로 성공 처리하거나 새 키로 재청구하지 않는다.
PG 거래 · 취소 내역과 기존 클레임 멱등 키를 대조한 뒤, 키 유효기간 안에서 기존 취소 조회/재시도로 복구하며 불명확하면 수동 확인한다.

### 자동 취소와 영속 실행 (#579)

`payment.auto-cancel.enabled`는 기본 false다. 거절 회신 뒤 빠른 `PaymentCancelPolicy`는 PG를 부르지 않고 의도만 커밋한다.
결제 행을 잠가 현재 주문 반영 거절이 있는 DONE/PARTIAL_CANCELED 또는 LATE_APPROVED인지 다시 확인하며, 일반 DONE과 전액 취소는 새 의도를 만들지 않는다.

금전 원장은 기존 `payment.payment_cancel` 하나다. 자동 취소 claimId는 **양수 Snowflake**이며 메타데이터 `payment_auto_cancel.claim_id`에 한 번 저장한다.
의도 생성 시 claimId·금액·통화는 null이다. 최초 acquire는 결제 행 잠금 아래 성공 취소 누계를 원승인액에서 뺀 잔액을 동결하고,
새 양수 claim을 **INSERT 전용 + flush**로 저장한 뒤 claimId·금액·lease를 같은 짧은 트랜잭션에 저장한다. 다른 PENDING claim은 준비를 막는다.
사전 ID 충돌 또는 동시 PK 충돌은 전체 롤백하며, Executor가 최대 3회 새 트랜잭션으로 재시도한다. 성공 커밋 전에는 PG를 부르지 않는다.
한 번 커밋한 claimId는 바꾸지 않는다. 원장이 없거나 식별자·금액이 어긋나면 수동 확인이며 대체 claim을 만들지 않는다.
기존 `save`도 paymentId·금액·통화 불일치 시 기존 원장을 변경하지 않고 거절한다. 외부 0·음수 claim은 DB/PG 호출 전에 거절한다.
양수 전용 구버전 원장 독자와 같은 형식을 유지하며 #591의 lane·커서·두 seed·가드는 수정하지 않는다.

PG 키는 claimId와 독립적인 `payment-auto-cancel-v1-<paymentId>`다. 의도 생성부터 정확한 문자열을 저장하고 헤더와 cancelRequestId에 그대로 쓴다.
작은 테스트 ID도 토스 길이 제한을 만족한다. 미확정 결과·재시도·재배포에서 키·금액·통화·커밋된 claimId를 재발급하지 않는다.
명시 실행 `PaymentAutoCancelUseCase.execute`는 NEVER이며 Processor의 준비 커밋 뒤 PG Processor(NEVER)를 호출한다.
attempts·nextAttemptAt·firstAttemptAt·2분 lease·token은 영속화한다. 재시도는 실행 시도 후 60초부터 최대 1시간까지 증가한다.
증거 저장과 완료는 유효 token 소유자만 할 수 있고, 결제 상태·SUCCEEDED 원장·완료 의도는 한 트랜잭션으로 커밋한다.

취소 전용 paymentKey GET은 결제키·주문번호·원금·통화·잔액·중복 없는 DONE 취소 이력과 이력합+잔액=원금을 검증한다.
검증된 준비 잔액이 그대로인 OPEN만 같은 키의 POST를 허용한다. POST 2xx만으로 완료하지 않는다.
정확한 고정 사유 `PAYMENT_AUTO_CANCEL:<pgKey>`·금액·선택적 requestId·마지막 transactionKey가 요청별 취소를 가리켜야 증거로 저장하고,
별도 GET에서 같은 거래와 잔액 0을 확인한다. 일반결제의 cancelRequestId는 null일 수 있어 단독 식별 근거로 쓰지 않는다.
POST 직후 증거 저장 전 중단도 GET 이력에 정확한 사유·동결 금액·DONE·마지막 거래키가 하나로 일치하면 거래 증거를 먼저 저장해 복구한다.
외부 같은 금액 취소·표식 부재·모순은 잔액만으로 귀속하지 않고 MANUAL_REVIEW로 남기며 POST하지 않는다.

최초 시도 후 14일부터 POST를 금지한다(토스 15일 유효기간 안의 보수적 여유). 만료 후에도 GET 확정을 먼저 허용한다.
만료된 키의 GET이 timeout/5xx 등 RETRY를 포함해 미확정이면 메타데이터는 수동 확인으로 종결하고 금전 원장은 PENDING으로 보존한다.
유효기간 안의 timeout·5xx·429·서킷·전송 불명은 PENDING/nextAttemptAt으로 재시도한다. 종결 거절·계약 모호성은 고정 사유
`AUTO_CANCEL_MANUAL_REVIEW`만 저장하며 PG 원문을 로그·메타데이터에 싣지 않는다.

운영 적용은 [취소 DDL 문서](payment-cancellation-ddl.md)와 실제 `ddl/payment-auto-cancel.sql`을 따른다.
양수 claimId 보정과 스케줄러·관측 변경은 대상 모듈 컴파일/시험, 생성 스냅숏 일치, 실제 PostgreSQL 이행 시험으로 검증한 뒤 배포한다.
이 문서는 운영 게이트 활성화 승인이 아니다.
#213 어드민 알림·operations-service 연동은 구현하지 않았다. FE/HTTP 응답 계약은 유지한다.

#### 자동 발견·재시도 스케줄러

게이트는 **`payment.auto-cancel.enabled` 하나**이며 기본 false다. ON이면 빠른 훅·실행기·스케줄러·스키마 가드가 함께 활성화된다.
`payment.auto-cancel.scheduler`에는 주기·락 기간·페이지 예산만 둔다. 별도 scheduler.enabled는 사용하지 않으며 남아 있어도 단일 게이트를 우회하거나 스케줄러만 끌 수 없다.

`PaymentAutoCancelScheduler → UseCase → SchedulerFacade → Processor → Port/Adapter` 순서다.
새 `payment.auto_cancel_cursor`는 REJECTED(DONE/PARTIAL_CANCELED 거절), LATE_APPROVED, PENDING(기한 도래·lease 없음/만료)을 독립 순회한다.
이미 의도가 있는 결제는 발견에서 제외한다. cycle 시작 시 payment/intent ID 상한과 due cutoff를 동결하고 제한된 ID keyset만 예약한다.
예약은 5초 REQUIRES_NEW에서 커서 진행까지 커밋하며 PG I/O나 의도 생성을 하지 않는다. 커밋 뒤 발견은 enqueue, PENDING은 명시 실행 UseCase를 호출한다.
후보 실패는 다음 후보, lane 조회 실패는 다음 lane으로 격리한다. 처리 전 프로세스가 중단된 후보도 다음 유한 cycle에서 다시 발견한다.
PENDING은 만료 lease를 다시 획득해 저장된 claimId·PG 키·금액으로 중단 복구한다.

기본 초기/반복 주기는 각 1분, page-size 5·lane당 최대 1페이지다. page-size 1~50·페이지 수 1~10·곱 100 이하를 검사한다.
ShedLock은 `payment.shedlock` 테이블을 공유하되 락 이름 `payment-auto-cancel`과 전용 provider로 분리한다. 기본 락 기간은 15분이다.
명목 예산은 `(발견 2×10초 + PENDING 실행 90초)×page-size×페이지수 + 커서 3×5초×페이지수 + 여유 60초`다.
PENDING은 최대 3회의 충돌 acquire·증거/결과 TX·최대 3회 PG 호출을 포함한다. 더 짧은 락 설정은 기동 실패다.
이는 벽시계 완료 보장이 아니다. JVM 정지 등으로 락이 만료돼 실행자가 겹쳐도 원장 INSERT·고정 ID/키·의도 lease/token fencing이 정합성을 지킨다.

단일 게이트 ON의 가드는 ddl-auto=none에서도 새 커서 컬럼·정확한 세 seed·양수 claim 메타데이터·공용 원장·payment.shedlock을 검사한다.
DB 원문/cause는 보존하지 않는다. #591 커서·두 seed·가드는 변경하지 않는다.
배포는 metadata DDL → scheduler DDL(기존 payment의 status,id 인덱스 포함) → 매핑 validate/생성 DDL 검증 → 전체 실행자 배포 → 별도 활성화 승인 순서다.
전체 스냅숏으로 새 DB를 만들면 CREATE를 반복하지 말고 운영 스크립트의 세 seed INSERT만 적용한다.
롤백은 단일 게이트 OFF·실행자 drain 후 구버전을 배포하고 양수 원장·의도·커서 진행값·PG 키를 보존한다. seed/claimId/키를 초기화하지 않는다.
#213 어드민 알림·operations-service 연동은 구현하지 않았다.

수동 확인은 MANUAL_REVIEW 영속 상태와 고정 사유 `AUTO_CANCEL_MANUAL_REVIEW`로 남긴다.
최초 전이 커밋 후 같은 고정 코드 로그와 태그 없는 `commerce.payment.auto-cancel.manual-review` counter를 기록한다.
롤백·같은 완료의 재호출은 counter를 늘리지 않는다. 커밋 직후 프로세스 중단으로 로그/counter가 유실될 수 있으므로 정확한 누적 사건 원장으로 쓰지 않는다.
gauge 질의는 read-only 트랜잭션 timeout 2초와 JPA query timeout 2000ms를 함께 적용한다. 풀 대기나 전체 scrape의 2초 벽시계 상한을 보장하지 않는다.
`commerce.payment.auto-cancel.manual-review.pending` gauge는 DB의 MANUAL_REVIEW 건수를 다시 조회해 재기동 후에도 대상을 보여 준다.
조회 실패는 0 대신 NaN이며 ID·PG 키·원문·cause를 metric 태그나 로그에 넣지 않는다. 이는 **#213 어드민 알림 전달이 아니다**.

토스 계약 확인: [코어 API](https://docs.tosspayments.com/reference), [취소 안내](https://docs.tosspayments.com/guides/v2/cancel-payment).
고정 사유 표식은 취소 귀속을 보수적으로 판단하기 위한 애플리케이션 규칙이다.

## 9. 해외 결제 (USD · PayPal)

- 금액은 **USD 센트(minor unit) 정수**로 저장하고, PG 요청 시 adapter 가 소수 표현(`89.50`)으로 바꾼다.
  이 변환은 `shared-commerce` 의 `CurrencyCode.fractionDigits` 를 쓴다 — 서비스마다 다시 계산하지 않는다.
- USD 가격 정책은 **관리자 수동 고정 가격**으로 확정했다(#20 A). 상품×통화 기본가·정가와 SKU×통화 추가금을 사용하며 환율 환산은 하지 않는다.
  정본은 [usd-pricing-guide.md](usd-pricing-guide.md)다. 가격행·명시 통화·nullable SKU 가격은 구현 전 목표이며 현재 결제는 KRW만 허용한다.
  B~E 구현과 신규 구매 gate 검증·별도 개방 승인 전에는 신규 명시 통화 USD 기능을 노출·활성화할 수 없다.
  기존 USD 상품도 이행 시 실제 통화로 보존하며, 취소·환불은 현재 가격이 아닌 원승인 통화·금액을 따른다.
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
