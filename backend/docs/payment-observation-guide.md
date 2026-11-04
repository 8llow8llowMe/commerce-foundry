# 결제 이상 후보 원천 관측 (#477 A4a)

`GET /internal/v1/payment-observations/anomalies`는 인자 없는 읽기 전용 API다. 실제 서비스 JWT의 수신 대상은 payment-service이며
`SERVICE_operations-service`만 허용한다. `@Hidden`, 공통 응답 봉투의 200, `Cache-Control: no-store`를 사용한다.
결제·메시징·복구 스케줄러 게이트와 독립적으로 읽으며 원문 결제 정보·식별자·금액·키·사유 문자열을 반환하지 않는다.

응답 `dataBody`는 `observedAt`과 아래 고정 여섯 버킷이다. 각 버킷은 `{count: long, oldestAt: Instant|null}`만 갖는다.
후보가 없으면 `count=0`, `oldestAt=null`이다. Clock은 한 번 읽어 마이크로초로 내리고 같은 시각을 질의와 응답에 쓴다.

| 버킷 | 후보 조건 | oldestAt 원천 |
|------|-----------|---------------|
| unknown | UNKNOWN, 모든 사유 | approvalStartedAt |
| manualReviewUnknown | UNKNOWN이며 MANUAL_REVIEW | approvalStartedAt |
| inProgress | IN_PROGRESS | approvalStartedAt |
| returnedReady | READY이며 paymentKey가 있음 | approvalStartedAt |
| lateApproved | LATE_APPROVED | approvedAt |
| doneOrderUnsynced | DONE이며 orderSynced=false | approvedAt |

`manualReviewUnknown`은 `unknown`의 부분집합이므로 합산하지 않는다. 일반 READY(키 없음)는 제외한다.
`oldestAt`은 마지막 승인 요청 시작 또는 PG 승인 시각이며 상태 진입 시각이나 상태 체류시간이 아니다.
준비·수정·생성 시각으로 대체하지 않는다. WAITING_FOR_DEPOSIT·실패·중단·취소 계열과 주문 반영 완료 DONE은 제외한다.
`doneOrderUnsynced`는 실패 코드 유무와 관계없이 거절 회신·취소 대상도 포함할 수 있으므로 단순 미회신이나 재전송 대기량으로 단정하지 않는다.
0건은 해당 버킷의 현재 후보가 없다는 뜻뿐이며, 다른 상태로 이동해도 빠지므로 PG·주문 정합성·취소 완료·전체 건강을 증명하지 않는다.

payment 컨텍스트 소유의 별도 읽기 Repository가 정적 JPQL 한 문장으로 여섯 count/min과 내부 시각 오류 건수를 집계한다.
후보 합집합 WHERE로 무관한 종결 이력을 제외한다. 기준 시각이 누락되거나 observedAt보다 미래인 후보가 하나라도 있으면 전체 503이다.
키가 있는 READY의 시각 누락도 오류이며, 정상 과거 최솟값이 있어도 미래·누락 후보를 숨기지 않는다. 동일 시각은 유효하다.
단일 DB statement의 스냅샷이며 메모리 로딩·PG·원격 호출·상태 전이·새 엔티티·스키마 변경은 없다.

payment Processor는 readOnly 트랜잭션 2초, 질의 timeout 2000ms를 사용하고 payment domain model만 반환한다.
paymentobservation Facade가 자기 Info와 Presenter로 변환하며 다른 컨텍스트의 Entity·Port·QueryResult에 의존하지 않는다.
Facade는 트랜잭션 밖에서 Processor 프록시 전체를 감싸 시작·질의·커밋의 DataAccessException/TransactionException을
고정 503 `PAYMENT_OBSERVATION_001`로 번역한다. 원인 없이 새 예외를 던지고 WARN에는 코드와 예외 타입만 남긴다.

관측 실패는 0건·정상·해소를 의미하지 않는다. 소비자는 실패 시 기존 사고 상태를 유지해야 한다.
관측 Clock 고정 이후 시작된 결제 시도가 질의 전에 커밋되면 미래 후보로 보여 보수적으로 503이 될 수 있다.
실제 실행 계획·인덱스 선택·대규모 후보의 응답 시간을 보장하지 않으며 운영 데이터로 확인해야 한다. timeout은 처리 시간의 보장이 아니다.

H2와 PostgreSQL 공통 질의 계약은 빈 결과·모든 버킷·모든 UNKNOWN 사유·부분집합·경계·최솟값·제외 상태·단일 SELECT·미래·누락을 확인한다.
PostgreSQL Testcontainers는 로컬 Docker 부재 시 건너뛰고 CI에서는 실행을 요구한다. 웹 테스트는 실제 JWT 체인·응답 필드·null·no-store·정밀도와
DB 실패 원문 sentinel 비노출을, 단위 테스트는 Clock 1회와 트랜잭션 시작·커밋 실패 번역을 확인한다.

임계값·스케줄러·operations 소비·알림·억제·해소 판정은 후속 작업이다. PG 재조회·수동 처리·결제 복구 API를 추가하지 않는다.
새 Vault 키와 운영 DDL은 없고 FE 고객·어드민 계약 영향도 없다. 운영 소비 계약은 [이상 알림](anomaly-notifications.md)에서 연결한다.
