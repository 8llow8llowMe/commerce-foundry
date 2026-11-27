# 결제 이상 후보 원천 관측 (#477 A4a)

`GET /internal/v1/payment-observations/anomalies`는 인자 없는 읽기 전용 API다. 실제 서비스 JWT의 수신 대상은 payment-service이며
`SERVICE_operations-service`만 허용한다. `@Hidden`, 공통 응답 봉투의 200, `Cache-Control: no-store`를 사용한다.
결제·메시징·복구 스케줄러 게이트와 독립적으로 읽으며(7번째 버킷만 자동 취소 게이트를 따른다) 원문 결제 정보·식별자·금액·키·사유 문자열을 반환하지 않는다.

응답 `dataBody`는 `observedAt`과 아래 고정 일곱 버킷이다. 각 버킷은 `{count: long, oldestAt: Instant|null}`만 갖는다.
후보가 없으면 `count=0`, `oldestAt=null`이다. Clock은 한 번 읽어 마이크로초로 내리고 같은 시각을 두 질의와 응답에 쓴다.

| 버킷 | 후보 조건 | oldestAt 원천 |
|------|-----------|---------------|
| unknown | UNKNOWN, 모든 사유 | approvalStartedAt |
| manualReviewUnknown | UNKNOWN이며 MANUAL_REVIEW | approvalStartedAt |
| inProgress | IN_PROGRESS | approvalStartedAt |
| returnedReady | READY이며 paymentKey가 있음 | approvalStartedAt |
| lateApproved | LATE_APPROVED | approvedAt |
| doneOrderUnsynced | DONE이며 orderSynced=false | approvedAt |
| autoCancelManualReview | `payment_auto_cancel.state = MANUAL_REVIEW` (자동 취소 게이트 ON일 때만) | next_attempt_at |

`manualReviewUnknown`은 `unknown`의 부분집합이므로 합산하지 않는다. 일반 READY(키 없음)는 제외한다.
`autoCancelManualReview`는 결제 상태가 아니라 자동 취소 의도의 상태라서 `manualReviewUnknown`과 합산·부분집합 관계가 없다.
`oldestAt`은 마지막 승인 요청 시작 또는 PG 승인 시각이며 상태 진입 시각이나 상태 체류시간이 아니다.
`autoCancelManualReview`의 `oldestAt`은 마지막 실행 예정·획득 시각(`next_attempt_at`)의 최솟값이며 MANUAL_REVIEW 진입 시각이 아니다.
준비·수정·생성 시각(`updated_at`·`created_at`·`first_attempt_at` 포함)으로 대체하지 않는다. WAITING_FOR_DEPOSIT·실패·중단·취소 계열과 주문 반영 완료 DONE은 제외한다.
자동 취소의 PENDING·SUCCEEDED도 제외한다.
`doneOrderUnsynced`는 실패 코드 유무와 관계없이 거절 회신·취소 대상도 포함할 수 있으므로 단순 미회신이나 재전송 대기량으로 단정하지 않는다.
0건은 해당 버킷의 현재 후보가 없다는 뜻뿐이며, 다른 상태로 이동해도 빠지므로 PG·주문 정합성·취소 완료·전체 건강을 증명하지 않는다.

`autoCancelManualReview`는 `payment.auto-cancel.enabled`(기본 false)가 꺼져 있으면 `payment_auto_cancel`에 SQL을 한 줄도 보내지 않고
버킷 자체를 JSON `null`(미관측)로 둔다. `{count:0, oldestAt:null}`(후보 없음)과 다르다. 게이트가 꺼진 DB에는 테이블이 없을 수 있고
(ddl-auto none, 스키마 가드는 게이트 ON에서만 돈다), 0으로 보내면 게이트를 되돌린 순간 소비자가 사건을 후보 소멸로 잘못 닫는다.
소비자는 `null`을 "이번 관측에서 판단 불가"로 다뤄 기존 사건 상태를 유지해야 한다.

payment 컨텍스트 소유의 별도 읽기 Repository가 정적 JPQL 한 문장으로 여섯 count/min과 내부 시각 오류 건수를 집계한다.
후보 합집합 WHERE로 무관한 종결 이력을 제외한다. 기준 시각이 누락되거나 observedAt보다 미래인 후보가 하나라도 있으면 전체 503이다.
키가 있는 READY의 시각 누락도 오류이며, 정상 과거 최솟값이 있어도 미래·누락 후보를 숨기지 않는다. 동일 시각은 유효하다.
게이트 ON이면 같은 Repository의 두 번째 정적 JPQL 한 문장이 MANUAL_REVIEW의 count·min(next_attempt_at)과 observedAt보다 미래인 건수를 함께 읽는다.
미래 값이 하나라도 있거나 두 번째 질의가 실패하면 앞 여섯 버킷과 같이 전체 503이다(next_attempt_at은 NOT NULL이라 누락 검사는 없다).
여섯 버킷은 단일 DB statement의 스냅샷이고, 7번째는 같은 읽기 트랜잭션의 두 번째 statement라 여섯 버킷과 서로 다른 스냅숏일 수 있다
(READ COMMITTED). 메모리 로딩·PG·원격 호출·상태 전이·새 엔티티·스키마 변경은 없다.

payment Processor는 readOnly 트랜잭션 2초, 질의 timeout 2000ms(두 질의 모두)를 사용하고 payment domain model만 반환한다.
paymentobservation Facade가 자기 Info와 Presenter로 변환하며 다른 컨텍스트의 Entity·Port·QueryResult에 의존하지 않는다.
Facade는 트랜잭션 밖에서 Processor 프록시 전체를 감싸 시작·질의·커밋의 DataAccessException/TransactionException을
고정 503 `PAYMENT_OBSERVATION_001`로 번역한다. 원인 없이 새 예외를 던지고 WARN에는 코드와 예외 타입만 남긴다.

관측 실패는 0건·정상·해소를 의미하지 않는다. 소비자는 실패 시 기존 사고 상태를 유지해야 한다.
관측 Clock 고정 이후 시작된 결제 시도, 또는 관측 Clock 고정 이후 획득한 자동 취소가 질의 전에 MANUAL_REVIEW 로 확정되면 미래 후보로 보여 보수적으로 503이 될 수 있다(인스턴스 간 시계 차도 같다). 재시도 예약은 PENDING 이라 7번째 버킷에 들어오지 않는다.
실제 실행 계획·인덱스 선택·대규모 후보의 응답 시간을 보장하지 않으며 운영 데이터로 확인해야 한다. timeout은 처리 시간의 보장이 아니다.

H2와 PostgreSQL 공통 질의 계약은 빈 결과·모든 버킷·모든 UNKNOWN 사유·부분집합·경계·최솟값·제외 상태·단일 SELECT·미래·누락과
7번째 버킷의 MANUAL_REVIEW 한정·min·미래 1건 실패·게이트 OFF 질의 0회(문장 수 1)·게이트 ON 문장 수 2·같은 observedAt을 확인한다.
H2 전용 시험은 `payment_auto_cancel` 테이블을 지운 DB에서 게이트 OFF가 여섯 버킷을 정상으로 읽고 7번째를 null로 두는지 확인한다.
PostgreSQL Testcontainers는 로컬 Docker 부재 시 건너뛰고 CI에서는 실행을 요구한다. 웹 테스트는 실제 JWT 체인·응답 필드·null·no-store·정밀도와
DB 실패 원문 sentinel 비노출, 7번째 버킷의 필드 한정(식별자·PG 키·금액·사유 비노출)을, 단위 테스트는 Clock 1회·게이트 분기와 트랜잭션 시작·커밋 실패 번역을 확인한다.

operations의 A4b-1 소비기는 기존 internal Feign 서비스 토큰·payment 서킷과 connect 2초/read 3초·HTTP 재시도 없음으로 이 원천만 읽는다.
성공 봉투·여섯 버킷·정수 long·0/null·마이크로초 시각·UNKNOWN 부분집합 관계를 전체 검증하며 실패는 원인 없는 고정 `NOTIFICATION_052`로 바꾼다.
#477 PR 2부터 7번째 버킷도 읽는다. 누락·JSON null은 미관측(그 사건 상태 유지)이고, 객체면 같은 엄격 검증을 받으며 UNKNOWN 부분집합 검증에는 넣지 않는다.
그 밖의 추가 필드는 필드 경로로 읽으므로 무시된다. operations 사건 정책은 [이상 알림](anomaly-notifications.md)의 AUTO_CANCEL_MANUAL_REVIEW를 따른다.
원천 나이와 순수 사건 정책은 [이상 알림](anomaly-notifications.md)의 A4b-1을 따른다. E2에서 영속 상태·알림 원자 저장을,
E3b에서 기본 비활성 주기 수집과 커밋 후 최선 SSE 전달을 연결한다. 기본 플래그를 실제 운영에 켜는 결정은 별도다.
PG 재조회·수동 처리·결제 복구 API를 추가하지 않는다.
새 Vault 키와 운영 DDL은 없고 FE 고객·어드민 계약 영향도 없다. 운영 소비 계약은 [이상 알림](anomaly-notifications.md)에서 연결한다.
