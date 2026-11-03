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
계약은 `api-design-guide.md` §2-15이며 ADMIN 전용이다. 결제 시도 원금·상태·시각과 미확정 이유·주문 회신 거절 코드를 읽기만 하고, 결제키·고객 멱등 키·PG 원문·회원 개인정보를 싣지 않는다.
미확정 이유는 해결된 결제에도 이력으로 남을 수 있다. `orderSynced=false` 전체가 복구 대상은 아니며 DONE + false 중 null 거절 코드는 회신 미확인, 코드가 있으면 주문 거절 회신이다.
이 API는 PG 조회나 승인 재시도, 주문 이벤트 재발행을 하지 않는다. REFUND_FAILED는 아직 모델에 없어 지원하지 않으며 취소/환불 실패 조회·자동 복구·웹훅·알림은 후속이다.

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
- **회신과 `order_synced`(payment, PR-C 예정)** — 두 칸은 그대로 두고 뜻을 **"주문의 회신 이벤트를 받았는가"** 로 바꾼다(DONE 계열만 쓴다 — LATE_APPROVED 는 주문에 반영하지 않는다, #14):

  | `order_synced` | `order_sync_failure_code` | 뜻 | 처리 |
  |----------------|---------------------------|----|------|
  | false | null | 아직 회신을 받지 않았다 | 없음 — 전달은 릴레이 · 컨슈머 재시도가 보장한다. 오래 남으면 outbox · DLT 지표로 본다 |
  | true | null | `order-paid` 를 받았다(주문 PAID 확정) | 없음 |
  | false | 코드 | `order-payment-rejected(reason)` 를 받았다 — 다시 보내도 바뀌지 않는다 | ERROR 로그 · 지표 + **자동 전액 취소 · 어드민 확인** 대상 |
  | true | 코드 | 금지(check) — 반영 성공 뒤 늦게 온 거절은 무시한다 | — |
- **전액 취소** — 거절 회신은 `PaymentCancelPolicy` 훅(기본 로그 · 지표, PR-C 예정)으로 넘기고, 실제 PG 전액 취소와 종결 뒤 승인(LATE_APPROVED)의 `payment-late-approved` 알림은 #15 다.
  돈만 받고 상품이 없는 주문을 남기지 않는다.

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
