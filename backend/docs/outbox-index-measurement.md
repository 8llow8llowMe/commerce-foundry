# Outbox 인덱스 실측 (#476)

## 범위와 재현

측정 코드는 operations-service `global/messaging/OutboxIndexExplainTest`다. `postgres:17-alpine` 전용 Testcontainers에
실제 `ddl/operations-schema.sql`을 적용하고 Hibernate validate를 거친다. 다른 서비스의 outbox도 같은 칼럼·질의·인덱스 모양이지만,
이 실험은 operations의 합성 데이터에 대한 결과이며 운영 데이터나 모든 서비스의 실제 부하를 대표하지 않는다.

```powershell
cd backend
.\gradlew.bat :service:operations-service:test --tests '*OutboxIndexExplainTest' --no-build-cache --max-workers=2
```

Docker가 없는 로컬에서는 이 클래스가 건너뛰어진다. CI에서는 Docker가 없으면 실패한다.
성공한 CI는 기본 workflow에서 테스트 artifact를 올리지 않으므로, operations의 Gradle `onOutput`은 이 클래스의
`OUTBOX_INDEX_ENV`, `OUTBOX_INDEX_SAMPLE`, `OUTBOX_INDEX_COMPLETE` 합성 측정 레코드만 콘솔에 전달한다.
전체 JSON은 로컬 `build/reports/outbox-index-explain.json`에도 저장한다. 실측 전에는 숫자를 채우거나 인덱스 변경을 결정하지 않는다.

## 고정 데이터와 비교 조건

| 항목 | 값 |
|---|---|
| 기준 시각 / 정리 cutoff | 2026-01-15 UTC / 기준 시각에서 7일 전 |
| 발행 완료 행 | 각 모집단에 100,000행, published_at은 8일 전 + 0~3,599초 |
| 미발행 행 | 0 / 1,000 / 100,000행 |
| 미발행 중 미래 예약 | 각 4번째 행, 정확히 25%. 나머지 75%는 1~60초 전 |
| payload / 업무 키 | 합성 문자열 1,024바이트 / 100개 합성 키 |
| ID 배치 | 발행 완료는 짝수, 미발행은 홀수. available_at은 ID 순서와 의도적으로 다르게 배치 |
| 배치 크기 / 반복 | 100 / 질의별 워밍업 1회 후 측정 3회 |
| 통계 / 캐시 | fixture와 후보 설정 뒤 ANALYZE. VACUUM·강제 cold cache·플래너 강제 설정 없음 |

각 미발행 모집단에 다음 세 구성을 순서대로 적용한다. 후보 인덱스는 disposable 컨테이너에만 CREATE/DROP한다.
기존 `(published_at, available_at)` 인덱스와 PK는 모든 구성에서 유지한다.

1. baseline: 기존 인덱스만 사용.
2. partial_id: `(id) WHERE published_at IS NULL` 추가.
3. partial_available_id: `(available_at, id) WHERE published_at IS NULL` 추가.

각 구성에서 릴레이의 **전체 행 조회 + ID 정렬**, 정리 대상 ID, 미발행 count, oldest available_at을
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행한다. 측정 SQL의 결과는 실제 JPA 저장소 결과와 비교한다.
세 번째 구성에서는 available_at 정렬 후보도 별도로 측정한다. 총 117개 원문 플랜에 실행 시간·정렬 노드·actual rows·shared hit/read가 포함된다.

이 JDBC SQL/DDL은 #476의 PostgreSQL 측정용이며 서비스의 영속성 구현에 native query를 추가하는 변경이 아니다.
작업 중인 다른 세션의 엔티티·저장소·비즈니스 상태 전이·API는 수정하지 않는다.

## 순서 계약

`ORDER BY available_at, id`는 현재 릴레이의 ID 오름차순과 같지 않다. 같은 aggregate의 ID 1/2에 서로 다른 과거 available_at을 넣으면
기존 쿼리는 `[1,2]`, 후보는 `[2,1]`을 반환한다. 이를 별도 테스트로 확인한다. 이 후보가 빨라도 현재 순서를 지키는 대체 쿼리로 승인하지 않는다.

## 측정 결과와 도입 판단

실제 PostgreSQL 실행 결과를 수집한 뒤 이 절과 #476에 근거를 기록한다. 현재 단계는 측정 코드 구현이며 운영 인덱스/정렬/DDL 변경은 없다.

## 해석의 한계

- [PostgreSQL EXPLAIN 문서](https://www.postgresql.org/docs/17/using-explain.html)에 따라 시간과 플랜은 통계 샘플·캐시·하드웨어에 영향을 받는다.
  고정 밀리초 상한이나 특정 플랜 노드 출현 여부로 테스트 합격을 판정하지 않는다.
- 이미 쓴 데이터와 순차 후보 생성에 따른 warm-cache 비교다. OS/DB 캐시를 강제 비우지 않아 cold-read 지연을 대표하지 않는다.
- ANALYZE를 썼지만 VACUUM은 하지 않아 visibility map 상태에 따른 index-only heap fetch가 남을 수 있다.
- 후보 인덱스의 INSERT/markPublished 쓰기 비용·WAL·스토리지·대량 운영 데이터 전환 비용은 측정하지 않는다.
  SELECT 이득만으로 운영 인덱스 추가를 결정하지 않는다.
- 같은 키의 순서를 바꾸는 후보는 성능과 별개로 현재 계약에 맞지 않는다. 기존 인덱스 제거도 이 실험의 범위가 아니다.
- 채택할 경우에만 스냅숏 관리 방식과 운영 `CREATE INDEX CONCURRENTLY`·INVALID 인덱스 복구를 별도 구현한다.
