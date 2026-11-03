# Outbox 원천 관측 (#477 A3a)

## 책임과 범위

payment-service가 자신의 outbox에서 발행 가능한 미발행 행의 집계 사실만 제공한다. operations가 다른 서비스 DB를 직접 읽거나 기존 지표의 여러 조회를 조합하지 않게 하는 원천 계약이다. operations 호출자·주기 수집·임계값·알림·해소 판정은 후속 단계다. 결제 상태·PG·릴레이·기존 지표·엔티티·DDL은 변경하지 않는다.

기존 DLT 알림은 [이상 징후 알림](anomaly-notifications.md)을 따른다. outbox가 정체하면 같은 outbox로 발행하는 이상 이벤트도 갇힐 수 있어, 원천 사실을 별도 읽기 API로 확인하는 방식을 선택했다. 새 브로커·토픽·운영 프로세스는 없다.

## HTTP 계약

`GET /internal/v1/outbox-observations/ready`는 payment-service 내부 주소로 호출한다. 인자는 없고 `@Hidden`이다. 허용 호출자는 `SERVICE_operations-service` 하나이며 사용자 ADMIN/CUSTOMER 토큰·무토큰·잘못된 서비스 토큰은 401 `SECURITY_007`, 다른 서비스 토큰은 403 `SECURITY_005`다. 게이트웨이로 노출하지 않는다.

성공은 200, `Cache-Control: no-store`, 기존 공통 봉투다. `dataBody`에는 다음 세 필드만 있다.

```json
{
  "observedAt": "2026-11-03T05:00:00Z",
  "readyUnpublishedCount": 2,
  "oldestReadyAvailableAt": "2026-11-03T04:52:00Z"
}
```

- `observedAt`: 원천 서비스가 조회 직전에 Clock에서 한 번 읽고 마이크로초 미만을 버린 UTC 기준시각이다. PostgreSQL의 시각 정밀도와 경계 비교를 맞춘다. 정확한 DB 스냅숏 생성 시각이라는 뜻은 아니다.
- `readyUnpublishedCount`: `publishedAt is null AND availableAt <= observedAt`인 행 수다. 미래 예약은 제외하고 정확히 같은 시각은 포함한다.
- `oldestReadyAvailableAt`: 같은 조건에 맞는 가장 오래된 `availableAt`이다. 대상이 없으면 `readyUnpublishedCount=0`, 이 값은 명시적 null이다.

식별자·본문·실패 코드·결제키·금액·개인정보·임계값·정상/해소 플래그는 반환하지 않는다. 나이가 필요하면 소비자가 `observedAt - oldestReadyAvailableAt`으로 계산한다. 미래 예약 행이 오래전에 생성됐더라도 발행 가능 시각 전에는 정체 근거가 아니다.

## 조회·트랜잭션·성능

한 JPQL 집계에서 count와 min을 함께 읽어 한 SQL statement의 스냅숏을 사용한다. 기존 `OutboxMetrics`의 count와 min은 별도 조회이고 미래 예약도 포함하므로 이 응답을 만드는 데 재사용하지 않는다. 조회에 락·상태 변경·재발행·조건부 UPDATE는 없다. 메시징·결제 게이트가 닫혀 있어도 이미 쌓인 행은 관측할 수 있다.

Processor는 읽기 전용 트랜잭션과 2초 제한을 사용하고 집계 쿼리에도 2초 query timeout을 둔다. Facade는 Processor 프록시 호출 전체를 감싸 DB·트랜잭션 오류를 `503 OUTBOX_OBSERVATION_001` 고정 문구로 변환한다. 오류 원문·원인 사슬은 응답이나 로그로 넘기지 않는다. 실패를 `0/null` 성공 응답으로 바꾸지 않는다.

이 제한은 커넥션 풀 획득이나 HTTP 전체 시간을 보장하지 않는다. 후속 호출자는 연결·읽기 제한과 관측 freshness를 별도로 관리해야 한다. 이 단계는 자동 재시도를 추가하지 않는다.

기존 `(published_at, available_at)` 인덱스를 유지한다. [#476 실행 계획 측정](outbox-index-measurement.md)의 count/min 비용은 이 새 집계의 지연을 보장하지 않는다. 원천 데이터 규모·관측 빈도·실행 계획으로 부하를 평가하고, 필요하면 별도 근거를 갖춰 인덱스를 검토한다. 공용 릴레이의 ID 정렬은 변경하지 않는다.

## 소비자가 지켜야 할 의미

`0/null`은 해당 조회에서 발행 가능한 미발행 행이 없다는 사실뿐이다. downstream 반영·결제 확정·릴레이 정상 동작을 증명하지 않는다. 수집 실패·시간 초과·비정상 응답은 관측 불가이며 정상 또는 해소로 사용하지 않는다.

응답 시각의 단조 증가는 보장하지 않는다. 이전 이하의 관측시각·오래된 응답·시계 역행·응답 순서 역전을 후속 탐지기가 구분해야 한다. DB 조회 중 릴레이가 발행하면 그 statement의 스냅숏으로 결과를 받고 다음 조회에서 변경을 관측한다. 이 API만으로 해결 알림을 발송하지 않는다.

## 배포와 검증

원천 A3a(PR #548)에는 새 DDL·환경변수·Vault 키·Gateway 경로가 없다. payment-service 배포 후 operations 서비스 토큰으로 200과 세 필드 응답을 확인한다. 후속 A3b는 operations의 실제 Feign 호출과 관측 알림을 연결한다.

A3b의 기본 비활성 플래그·신선도·시계 방어·억제·0건 해소 의미·신규 operations 상태 테이블 배포는 [이상 징후 알림](anomaly-notifications.md)의 결제 outbox 절을 따른다. 수집 불가·비정상·오래된 응답은 기존 사고를 유지하며 해소로 판단하지 않는다.

검증 대상은 빈 테이블·미래 예약만·경계 시각·여러 과거 행·발행 완료 제외·발행 후 재조회, Clock 1회 사용, 실제 JWT 인가, 세 필드/null/no-store 계약, 실패 503과 원문 비노출이다. PostgreSQL 전용 검증의 로컬 건너뜀과 실제 실행 결과를 구분하며, payment-service `cleanTest/check --no-build-cache` 및 CI 결과를 완료 근거로 기록한다.
