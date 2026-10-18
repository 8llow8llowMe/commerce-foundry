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
| 클라이언트 키 (`test_ck_` / `live_ck_`) | 프론트 환경변수 | 공개돼도 된다 |
| 시크릿 키 (`test_sk_` / `live_sk_`) | Vault `TOSS_SECRET_KEY` → payment-service | **절대 커밋·로그 금지** |
| 웹훅 시크릿 (사용 시) | Vault `TOSS_WEBHOOK_SECRET` → payment-service | 절대 커밋·로그 금지 |

- 키 이름·등록 절차는 `secret-management.md` 를 따른다.

- **prod 프로파일에서 `test_` 키로 기동하면 실패시킨다.** 반대로 local/dev 에 `live_` 키가 들어와도 실패시킨다.
  키가 뒤바뀐 배포는 "결제는 되는데 돈이 안 들어오는" 사고가 된다.
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
  │                            ◀── internal: 결제 완료 ┘
  │                            PAID 전이 · 재고 확정 차감
```

### 3-1. 금액은 서버가 정하고 서버가 검증한다

- 결제 금액은 **order-service 가 계산한 주문 금액**이다. 프론트가 보낸 금액을 믿지 않는다.
- successUrl 로 돌아온 `amount` 는 **변조될 수 있다.** 승인 요청 전에 `payment.amount` 와 `currency` 가 일치하는지
  확인하고, 다르면 **승인 요청을 보내지 않고** 거절한다 (`PAYMENT_AMOUNT_MISMATCH`). 이 경우는 보안 경고 로그로 남긴다.
- 쿠폰·배송비·할인이 바뀌어 주문 금액이 달라졌으면 결제 준비를 새로 받는다 (기존 READY 는 ABORTED).

### 3-2. 중복 승인 방지 — 두 겹으로 막는다

1. **우리 상태**: `READY → IN_PROGRESS` 전이를 낙관적 락(`@Version`) 또는 조건부 UPDATE 로 한 번만 성공시킨다.
   이미 `IN_PROGRESS`/`DONE` 이면 PG 를 다시 부르지 않고 현재 상태를 돌려준다.
2. **PG 멱등 키**: 승인·취소 요청에 `Idempotency-Key` 헤더를 싣는다. 값은 우리 결제 시도 ID 처럼 재시도해도
   같은 값이어야 한다 — 요청마다 새 UUID 를 만들면 멱등이 아니다.

### 3-3. 트랜잭션 경계

- **PG 호출을 DB 트랜잭션 안에 두지 않는다** (`architecture-guide.md` §3 Facade 예외).
  - ② 상태 전이 → 커밋 → ③ PG 호출(트랜잭션 밖) → ④ 결과 기록 → 커밋
- PG 승인 성공 후 ④ 의 DB 저장이 실패해도 결제는 이미 일어났다. 그래서 ② 에서 남긴 `IN_PROGRESS` 기록이
  복구의 단서가 된다 (§5).

## 4. 결과 미확정(UNKNOWN) 처리

PG 호출이 **타임아웃·5xx·서킷 오픈**으로 끝나면 승인 여부를 모른다. 이것을 "실패" 로 처리하면 안 된다 —
고객 카드에서는 돈이 빠졌는데 우리는 실패로 보고 주문을 취소하게 된다.

| PG 응답 | 우리 상태 | 고객 응답 |
|---------|-----------|-----------|
| 승인 성공 | `DONE` | 결제 완료 |
| 명확한 거절 (4xx, 한도 초과·카드 거절 등) | `FAILED` (+ PG 오류 코드) | 실패 사유 표시, 재시도 가능 |
| 타임아웃 · 5xx · 서킷 오픈 | **`UNKNOWN`** | "결제 확인 중" — 몇 초 뒤 상태 조회 |

- `UNKNOWN` 은 스케줄러가 **PG 조회 API 로 재조회**해서 `DONE` 또는 `FAILED` 로 확정한다.
- 서킷이 열렸을 때 confirm 을 아예 보내지 않았다면 `READY` 로 되돌릴 수 있다. **보냈는지 모르면 UNKNOWN 이다.**

## 5. 결제 상태 모델

```text
READY ──▶ IN_PROGRESS ──▶ DONE ──▶ PARTIAL_CANCELED ──▶ CANCELED
  │            │            └────────────────────────▶ CANCELED
  │            ├──▶ FAILED
  │            └──▶ UNKNOWN ──▶ DONE | FAILED   (재조회로 확정)
  └──▶ ABORTED   (금액 변경 · 시간 초과로 버려진 시도)
```

- 상태 전이는 도메인 메서드(`payment.approve(...)`, `payment.cancel(...)`)로만 한다. 허용되지 않은 전이는 예외다.
- 결제 1건 = 결제 시도 1건. 실패 후 재시도는 **새 결제 시도**(새 `pgOrderId`)로 만든다.
  `pgOrderId` 규칙(길이·허용 문자, 재사용 가능 여부)은 토스 문서를 따른다.
- 취소는 `payment_cancel` 행으로 누적한다. **남은 취소 가능 금액 = 승인 금액 − 취소 누계**이며 이를 넘는 취소를 거절한다.

## 6. 서비스 간 정합성

결제 완료를 order-service 에 알리는 단계가 실패할 수 있다.

- payment-service 는 `DONE` 기록과 함께 **`order_synced = false`** 를 남기고, 알림이 성공하면 `true` 로 바꾼다.
- 스케줄러가 `DONE && !order_synced` 를 주기적으로 재전송한다. order-service 의 결제 완료 처리는 **멱등**이어야 한다
  (이미 PAID 면 성공으로 응답).
- 재고 선점이 만료된 뒤 결제가 완료되는 경우(고객이 결제창을 오래 열어 둠)가 있다. order-service 가 재고를 다시
  잡지 못하면 **자동 전액 취소** 후 고객에게 안내한다 — 돈만 받고 상품이 없는 주문을 남기지 않는다.
  확정이 409 `PRODUCT_025`(이미 해제 · 만료)면 같은 주문으로 재선점(`POST /internal/v1/stock-reservations`)하고 **곧바로 확정**한다. 재선점이
  `PRODUCT_021`(재고 부족) · `PRODUCT_022`(판매 불가)면 전액 취소다. **주문은 선점 확정 200(`CONFIRMED`)을 받은 뒤에만 PAID 로 둔다** — 결제 완료 재전송 때도
  확정을 멱등으로 다시 부른다 (`order-flow-guide.md` §3-4).
- 메시지 브로커(Kafka/RabbitMQ) + outbox 로 바꿀지는 트래픽을 보고 별도 이슈에서 결정한다. 그 전까지는 위의
  "동기 호출 + 재전송 플래그 + 스케줄러" 가 기본이다.

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
- 테스트 키로 실제 토스 샌드박스 승인 → 취소까지 한 번 돌린 결과를 PR 검증 내역에 남긴다.
