# 토스 결제 상태 웹훅 (#15)

## 계약과 신뢰 경계

공식 [이벤트 본문](https://docs.tosspayments.com/reference/using-api/webhook-events)의 PAYMENT_STATUS_CHANGED와
[웹훅 연결](https://docs.tosspayments.com/guides/v2/webhook)을 기준으로 한다.
`POST /api/v1/payments/webhooks/toss`는 익명 JSON 수신 경로이며 `permitAll()`이다. 요청 필드는
`eventType`, `createdAt`, `data.orderId`, `data.paymentKey`뿐이다. eventId 없는 공급자 본문을 받으며 나머지 칸은 무시한다.
PAYMENT_STATUS_CHANGED만 지원하며 다른 이벤트는 400이다. REFUND_FAILED·가상계좌·취소 원장 반영 등 #15 나머지 범위는 후속이다.

createdAt은 1970~9999년의 ISO local/offset date-time(최대 40자)이며 시간대 없는 공급자 형식도 허용한다.
시간대가 있으면 UTC Instant, 없으면 LocalDateTime으로 정규화한다. 신뢰·작업 나이 판단에 쓰지 않는다.
정규화한 eventType/orderId/paymentKey/createdAt의 길이 접두어 연결을 SHA-256으로 해시한다.
같은 fingerprint 재전송은 한 행이며 COMPLETED·MANUAL_REVIEW도 초기화하지 않는다. 생성 시각이 다르면 별도 행이므로 DONE 이후 취소도 새 조회를 유발한다.

수신 자체는 발신자 인증의 증거가 아니다. 서명 헤더 인증은 구현하지 않았고 헤더·본문 상태·금액을 금융 상태의 정본으로 믿지 않는다.
발송별 transmission-id는 중복 키로 쓰지 않는다. PG 결제 비밀을 웹훅 HMAC 키로 재사용하지 않는다.
상태 변경의 근거는 작업자가 **로컬 DB 결제키로 PG GET 재조회**한 결과뿐이다. 발신자 인증·엣지 요청 제한은 배포 환경에서 별도 검토한다.
로컬 pgOrderId가 없으면 404 `PAYMENT_WEBHOOK_002`, 기존 non-null paymentKey와 다르면 400 `PAYMENT_WEBHOOK_003`이다.
IN_PROGRESS 키는 confirm 원격 호출 전에 커밋되므로 승인 응답 전 알림도 받는다.
키 없는 READY/ABORTED의 늦은 승인은 지원하지 않으며 요청 키를 로컬 결제에 붙이지 않는다.
본문 상태·금액·주문명·카드·PII를 저장하거나 로그에 남기지 않는다.

## 영속 수신과 실행

수신 Facade는 NEVER, DB Processor는 REQUIRES_NEW다. HTTP에서는 PG를 호출하지 않는다.
200은 inbox 커밋 또는 동일 fingerprint의 영속 존재 확인 후에만 반환하며 공통 `Response<Void>`다.
동시 INSERT 충돌 뒤에는 중단된 트랜잭션 밖의 새 트랜잭션으로 존재를 재조회한다. DB 장애는 503 `PAYMENT_WEBHOOK_004`다.
OFF는 503 `PAYMENT_WEBHOOK_001`, 형식 오류는 `PAYMENT_WEBHOOK_100` 또는 필드별 `101` 이벤트·`102` 시각·`103` data·`104` 주문번호·`105` 키다.

inbox는 로컬 paymentId, fingerprint, 상태, attempts, availableAt, claimToken, reason, 시각, version만 저장한다.
JSON·PG 키·고객/주문 정보는 없다. Snowflake PK, fingerprint 유니크, attempts/claim check와 `(state,available_at,id)` 인덱스를 사용한다.
PENDING → PROCESSING → COMPLETED/RETRY/MANUAL_REVIEW로 전이한다. PROCESSING의 availableAt은 lease 만료 시각이다.
due/id 순으로 최대 batchSize개의 후보만 읽는다. 건별 claim은 새 token·version CAS·attempts 증가·lease를 독립 짧은 트랜잭션으로 커밋한다.
만료 PROCESSING도 회수하며 재시작에 메모리 큐·브로커가 필요 없다. 건별 결제 재조회는 동시성 검사용 트랜잭션 경계다.

전용 1스레드 `paymentWebhookTaskScheduler` → UseCase → NEVER Facade → DB Processor 순서로 실행한다.
PG GET은 트랜잭션 밖이고 기존 recovery 스케줄러 스레드를 공유하지 않는다.
조회 전후 결제 전체 스냅숏·버전이 다르면 옛 응답을 버리고 RETRY한다(DONE이 됐어도 즉시 완료하지 않는다).
새 GET으로 검증한 같은 DONE만 새 이벤트 없이 완료한다. 신규 DONE은 기존 `PaymentProcessor.saveApproved`로
결제+승인 outbox+inbox를 같은 트랜잭션에 기록한다. 마지막 inbox CAS는 token/version/미만료 lease를 검사한다.
회수·만료·충돌이면 결제/outbox도 롤백한다. outbox 실패는 거짓 완료 없이 PROCESSING lease를 남겨 회수한다.
PG approve/cancel, order/stock 호출은 없다.

- Approved는 키·금액·통화 일치 시 IN_PROGRESS.approve, UNKNOWN.resolve, bound READY 복구 후 resolve로 DONE을 기록한다.
- FAILED/키 있는 ABORTED의 실제 승인은 LATE_APPROVED + MANUAL_REVIEW다. 승인 이벤트·자동 취소·주문 반영은 하지 않는다.
- Failed(ABORTED/EXPIRED)는 가변 lane만 실패/중단으로 확정하며 DONE을 낮추지 않는다.
- 취소/부분 취소/입금 대기·계약·키/금액/통화 불일치·지원하지 않는 로컬 상태는 payment를 유지하고 MANUAL_REVIEW다.
- TIMEOUT/SERVER_ERROR/NotFound/Pending은 지수 backoff로 재시도한다. NotFound는 미승인 근거가 아니다.
  maxAttempts 또는 수신 후 maxAge 초과는 MANUAL_REVIEW다. 중복 수신은 예산을 되돌리지 않는다.

## 설정과 활성화

`payment.webhook.enabled`(`PAYMENT_WEBHOOK_ENABLED`)는 기본 false다. OFF는 수신·작업자를 멈추고 행을 보존한다.
ON은 PENDING/RETRY/만료 PROCESSING부터 이어 처리한다. 기존 payment.enabled/recovery.enabled와 독립이다.

| 설정 | 기본·범위 |
|---|---|
| batch-size | 20, 1~100 |
| lease | 60초, 30초~30분 |
| max-attempts | 6, 1~20 |
| initial-backoff | 5초, 1초~5분 |
| max-backoff | 5분, initial-backoff 이상·최대 1시간 |
| max-age | 24시간, 1분~7일 |
| initial-delay / fixed-delay | 1분 / 5초 |

Hibernate PostgreSQL 17 생성 스냅숏 `service/payment-service/src/test/resources/ddl/payment-schema.sql`이 DDL 정본이다.
기존 운영에는 생성문 추가분 `ddl/payment-webhook-inbox.sql`을 UTF-8로 **활성화 전에** 적용한다.
운영 DDL → OFF 앱 배포 → PostgreSQL CI·PG 테스트 환경 확인 → HTTPS URL 및 PAYMENT_STATUS_CHANGED 등록 → 활성화 순서다.
활성 시 inbox 컬럼/outbox가 없으면 기동을 중단한다. 기존 데이터 변환은 없다.
실제 PG 결제 실험·개발자센터 등록·운영 DDL 적용은 이 변경에서 수행하지 않는다.

## 수동 확인과 재처리

최소 권한 계정으로 로컬 아이디·이유·시도·나이만 조회한다. 키·digest·원문은 로그/티켓에 복사하지 않는다.

```sql
select id, payment_id, state, reason, attempts, received_at, available_at
from payment.payment_webhook_inbox
where state = 'MANUAL_REVIEW'
order by received_at, id;
```

PAYMENT_MISSING은 보존/정합성, PG_CONTRACT는 PG 계약/키·금액, LATE_APPROVAL은 수동 환불/주문 대응,
EXHAUSTED는 PG 장애와 예산을 조사한다. PG 관리자 화면과 로컬 결제·취소 원장으로 대조한다.
MANUAL_REVIEW 일괄 PENDING 변경이나 attempts 무조건 초기화는 금지한다.
재처리가 필요하면 worker를 끄고 단건 id·version·결제 현재 상태·PG 재조회 결과·예산·수신 나이를 승인 기록으로 검토하여 운영 변경을 설계한다.
자동 replay/reset API는 없다.

## 검증 범위

H2 실제 익명 HTTP·바인딩·중복·결제/outbox 원자성·confirm 경합·늦은 승인 보류·재시도·lease 회수·OFF 보존을 검증한다.
PostgreSQL 17은 생성 DDL 적용/validate, due 순서·예산·인덱스·제약, claim 경합·오래된 작업자 거부를 검증한다.
Docker 없는 로컬 skip은 PostgreSQL 실행 증거가 아니며 실제 PG/운영 적용 증거와 구분한다.
FE 응답 계약은 그대로이며 #15 전체는 계속 열려 있다.
