# 알림 저장 계측 (#477 A4b-2 E1)

기존 `NotificationEntity`의 저장 경로를 테스트 전용으로 비교한다. 운영 Entity·DDL·JDBC batch 설정·스케줄러·실제 알림 전달은 변경하지 않는다. 결과 승인 후 상태 저장 본체를 구현하며, E1이 다섯 사건 상태의 원자 저장이나 SSE를 증명하지는 않는다.

## 비교 경로와 필수 조건

| 경로 | 동작 |
|---|---|
| BASELINE | 기존 단건 Adapter의 saveAndFlush 반복 |
| A_SAVE_ALL | 신규 알림 전체를 saveAllAndFlush |
| B_PERSIST | 신규 Entity를 persist한 뒤 마지막 flush |

assigned ID만으로 merge를 단정하지 않는다. 실제 EntityInformation의 신규 판정, version=null 객체의 INSERT, 저장된 version을 유지한 detached 객체의 merge 대조를 확인한다. 저장 후보는 호출자 트랜잭션·신규 미읽음·고유 ID·1~5000개·전체 사전 검증·반환 ID 대응과 불변 목록을 요구한다.

정상 저장은 사전 SELECT0·논리 INSERT N·UPDATE0과 별도 조회의 커밋 N행·수신자/source/감사/version 대응이 필수 gate다. 중간 실제 쓰기 뒤 unique 충돌, 마지막 flush 후 예외, PostgreSQL의 실제 미커밋 PK 대기 timeout은 전체 롤백과 작업 스레드/연결 회수를 확인한다. 예상 실패와 정상 후보의 저장 실패를 구분하고 실패 후보를 반복해 성공 사례만 고르지 않는다.

기준 구현의5000건은 최대1회, 선정 후보5000건은 최대3회다. 첫 후보5000건이 실패하면 나머지 반복을 중단한다. 소량 비교에서 동일한 저장 의미를 충족하면 A를 먼저 측정하지만 운영 방식 채택은 별도 검토한다. 시간 기준을 통과시키려고 batch 설정·timeout을 늘리거나 분할 커밋하지 않는다.

## 수치 해석

성공 CI에서도 `ANOMALY_STORAGE_PROBE` JSON 요약을 남긴다. SQL/바인드 값·식별자·자격 증명은 출력하지 않는다.

- JDBC 실행·batch 파라미터 행 수, Hibernate prepare/entity insert/update/flush를 분리한다. 이는 물리 네트워크 패킷 수나 서버 문장 수가 아니다.
- `factoryJdbcBatchSizeHint`와 세션 override는 힌트다. 실제 배치 여부는 `executeBatch`·파라미터 행 수로 확인한다. 힌트가 양수여도 실제 batch가 없을 수 있다.
- 명시 저장 구간과 commit/rollback까지의 전체 트랜잭션 구간을 나눈다. commit 자동 flush가 추가될 수 있으므로 전체 flush를 무조건1로 가정하지 않는다.
- fixture 생성·정리·검증 쿼리는 저장 SQL 계측 밖이다. `INPUT_BUILD`의 생성 전후 heap 진단도 따로 기록한다.
- heap은2ms 샘플의 관측 하한이며 peak·retained heap·누수 보장이 아니다. thread allocation은 저장 작업 스레드 범위다. GC/샘플러 오버헤드·전후 delta의 한계도 함께 해석한다.
- JDK/Data/Hibernate/driver/PG 버전·image ID·컨테이너 CPU/메모리·heap·GC를 기록한다. 호스트의 다른 부하는 알 수 없으며 독점 실행을 주장하지 않는다.
- 5000건 최대10초는 해당 관측 환경에서15초 설정의 여유를 검토하는 기준이다. 운영 SLA·일반 CI의 절대시간 hard assert가 아니다. 소표본3회로 p95/p99를 주장하지 않는다.

## 실행과 승인 경계

Windows PowerShell, `backend/`에서 실행한다.

```powershell
.\gradlew.bat :service:operations-service:test --tests '*AnomalyStorageProbe*' --no-build-cache --max-workers=2
.\gradlew.bat :service:operations-service:cleanTest :service:operations-service:check --no-build-cache --max-workers=2
```

전용 JPA 컨텍스트에는 앱 스캔·worker·스케줄러·Feign·Kafka를 넣지 않으며 클래스 종료 후 닫는다. H2는 소량 기능·계측 보조 검증이다. PostgreSQL17 테스트는 기존 스키마를 validate하며 로컬 Docker 부재는 skip/미실측이다. CI는 Docker 실행을 요구한다. H2 결과를 PostgreSQL5000건·timeout·성능 증거로 대체하지 않는다.

실제 PG CI의 SQL·원자성 gate와 환경별 시간/메모리를 회수한 뒤 감독이 저장 후보와 추가 설정 필요성을 판단한다. E2의 실제 다섯 상태 행·초기 경합·NOWAIT·생성 운영 DDL, E3의 수집·전체 알림·SSE·큐 포화 메모리는 각각 구현·재검증해야 한다. 대기 큐80000개만으로 전체 객체 수나 JVM 안전성을 판단하지 않는다.
