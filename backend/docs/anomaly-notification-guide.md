# DLT 이상 알림 (#477 A2)

operations-service가 기존 DLT 적재 사실을 활성 ADMIN별 개인 알림으로 남긴다. 원천 결제·주문 상태를 조회하거나 변경하지 않는다.
새 HTTP API·Kafka 토픽·PG 키는 추가하지 않는다. auth A1 수신자 API와 기존 알림 목록·읽음·SSE를 사용한다.

## 입력과 개인 알림

구독은 현재 namespace의 아래 세 토픽만 허용한다. 패턴 구독·임의 헤더 라우팅은 사용하지 않는다.

| namespace 뒤 토픽 | 심각도 | 고정 제목 | 화면 |
| --- | --- | --- | --- |
| `payment.payment-approved.dlt` | CRITICAL | 결제 이벤트 처리 이상을 확인해 주세요 | `/admin/payments` |
| `order.order-expired.dlt` | WARNING | 주문 만료 이벤트 처리 이상을 확인해 주세요 | `/admin/orders` |
| `order.order-canceled.dlt` | WARNING | 주문 취소 이벤트 처리 이상을 확인해 주세요 | `/admin/orders` |

consumer는 byte 배열로 받고, listener에 전달하기 전에 topic/partition/offset만 가진 record로 바꾼다.
key·value·headers·오류 원문을 읽거나 해석·저장·로그 출력하지 않는다. 깨진 JSON도 같은 적재 신호다.
application 입력은 Kafka 타입을 모르는 `AnomalyRecordCommand`다. listener가 전체 토픽 이름을 allowlist와 대조한다.

auth의 `GET /internal/v1/admin-notification-recipients?afterId=0&size=100`을 서비스 토큰으로 조회한다.
봉투·성공 여부·문자열 양수 canonical long·엄격한 숫자 오름차순·페이지 100개 이하·총 1,000개 이하를 확인한다.
숫자 JSON, 선행 0, 중복, 순서 역전, 불완전 응답, 빈 수신자, 초과, 전송 실패는 모두 원문/cause 없는 고정 오류다.
정확히 1,000개이면 마지막 empty page 확인을 위해 최대 11번 호출한다. cursor 사이에 계정 변경이 일어날 수 있어 전체 목록은 auth의 단일 DB 스냅숏이 아니다.

이미 처리한 offset은 auth 전에 읽기 전용으로 건너뛸 수 있다. 새 offset은 억제창 안이어도 auth를 조회한다.
따라서 DLT 폭주가 auth 호출을 일으키는 한계가 있다. 수신자를 임의 계정으로 대체하거나 0명 성공 처리하지 않는다.
발행 직후 ADMIN 비활성화와 같은 권한 변화는 다음 조회에 반영되며, 기존 알림을 소급 삭제하지 않는다.

## 영속성·커밋·전송

`notification_anomaly_state.source_key`는 namespace를 포함한 토픽이다. 현재 namespace의 허용 세 출처당 최대 한 행이다.
과거 namespace 상태는 보존하므로 모든 namespace를 합친 전역 3행 제한은 아니다. 환경별 DB 분리 원칙은 그대로다.
`@Version` CAS와 먼저 수행하는 `saveAndFlush`로 rolling window 판정을 직렬화한다. 비관적 행 잠금을 사용하지 않는다.

새 TX에서 seen 재검사 → state CAS flush → `(topic, partition_id, offset_id)` UNIQUE 처리 기록 → 수신자 N명 알림을 모두 원자 커밋한다.
처음 INSERT한 상태의 알려진 UNIQUE, seen UNIQUE, optimistic conflict만 Facade에서 최대 3번 새 TX로 재시도한다.
실패한 TX 안에서 예외를 삼킨 뒤 재조회하지 않는다. 수신자 목록은 이 세 시도에 재사용한다.

첫 적재는 즉시 알린다. 마지막 발행부터 기본 1시간 뒤 **새 offset**이 오면 다시 알리며, 정각 경계로 창을 나누지 않는다.
같은 offset은 억제창 뒤에도 처리 기록 보존 기간 중에는 no-op이다. 억제 중 새 offset도 lastSeen과 seen은 저장한다.
출처 `ANOMALY`의 `source_id`는 이번 발행 Snowflake이며, 수신자 모두에 공통이다. 결제·주문 식별자나 offset을 뜻하지 않는다.
알림 UNIQUE는 `(recipient_member_id, source_type, source_id, kind)`다. 한 사람의 읽음은 다른 사람의 알림에 영향을 주지 않는다.
기존 JOB 기록·push 조회도 수신자를 명시하며 기존 읽음 조건부 UPDATE는 유지한다.

커밋 이벤트에는 INSERT 결과의 immutable Notification 목록만 싣는다. AFTER_COMMIT는 추가 SELECT 없이 전용 전송 큐에 넣는다.
전송은 최대 스레드 1개/대기 큐 16개의 executor에서 실제 NotificationPushPort로 수행한다.
큐 포화·종료·SSE 실패는 고정 경고 후 best effort로 끝내며 Kafka 재시도나 커밋 실패로 바꾸지 않는다.
DB가 정본이다. 커밋 직후 프로세스 종료, 큐 포화, 다른 인스턴스에 연결된 SSE는 목록 재조회로 따라잡는다.
모든 인스턴스의 즉시 SSE 전달을 보장하지 않는다. 기존 JOB AFTER_COMMIT 전송은 별도 기존 경로다.

처리 기록 보존 기본은 8일(현재 Kafka 7일 + 복구 여유 1일)이며 8일 미만 설정은 거절한다.
매시간 `processed_at < cutoff` 조건의 단일 bulk DELETE와 해당 인덱스로 정리한다. cutoff와 같은 시각은 남긴다.
state는 정리하지 않고, 개인 알림 90일 보존과도 독립이다. 처리 기록 정리 뒤 무기한 offset replay의 영구 중복 억제는 보장하지 않는다.

## 설정과 소비 실패

| 프로퍼티 (`operations.notifications.anomaly`) | 환경변수 | 기본값 / 제한 |
| --- | --- | --- |
| `enabled` | `NOTIFICATION_ANOMALY_ENABLED` | false |
| `suppression-window` | `NOTIFICATION_ANOMALY_SUPPRESSION_WINDOW` | 1h, 양수 |
| `record-retention` | `NOTIFICATION_ANOMALY_RECORD_RETENTION` | 8d 이상 |
| `max-recipients` | `NOTIFICATION_ANOMALY_MAX_RECIPIENTS` | 1000, 1..1000 |
| `retry-attempts` | `NOTIFICATION_ANOMALY_RETRY_ATTEMPTS` | 첫 시도 포함 3, 1..3 |
| `retry-backoff` | `NOTIFICATION_ANOMALY_RETRY_BACKOFF` | 1s, 0..5s |
| `transaction-timeout-seconds` | `NOTIFICATION_ANOMALY_TRANSACTION_TIMEOUT_SECONDS` | 10, 1..10 |
| `cleanup-interval` | — | 1h |

gate ON에는 `commerce.messaging.enabled=true`와 세 DLT 존재가 필수다. OFF에는 전용 listener/verifier/executor를 등록하지 않으며
DLT 토픽이 없는 기존 JOB 메시징 기동을 막지 않는다. 전용 RequiredTopicsVerifier는 **존재만** 확인한다. retention을 검사하지 않는다.

전용 factory는 auto commit=false, RECORD, earliest, auto-topic=false, max.poll.records=1, max.poll.interval.ms=300000을 강제한다.
그룹은 `<namespace>.operations.anomaly-dlt`이며 정확한 토픽 목록·그룹과 토픽 최대 249자/그룹 최대 255자를 검증한다. 공통 `.dlt` publishing error handler를 사용하지 않는다.
Feign 기존 default timeout connect 2s/read 3s를 쓰며 default/auth-service/전용 context의 더 긴 timeout은 ON 기동에서 거절한다.
Hikari connection-timeout은 gate ON에서 250..30000ms를 강제하고 중복 fastpath 읽기 TX timeout은 5초다.
한 poll의 보수적인 예산은 `(연결 획득30초 + 읽기TX5초) + auth11페이지×5초 + CAS3회×(연결획득30초 + 쓰기TX10초) + backoff5초 = 215초 < 300초`다.
handler의 seek 뒤에는 다음 poll에서 재전달되므로 Kafka retry 횟수를 한 poll 예산에 곱하지 않는다.
이는 연결 획득·JDBC 쿼리 timeout이 작동할 때의 예산이다. 단절된 DB의 network read를 TX timeout만으로 항상 끊는다고 보장하지 않는다.
운영에서는 JDBC socket/network/statement timeout과 DB 장애 시 정지/재시작을 별도로 검증한다.
DB 알림 fanout 1,000건을 10초에 못 쓰면 전체 롤백·offset 미커밋이다. 큰 설치에서는 수신자 상한/DB 성능을 별도로 검토한다.

전용 handler는 TPO별 유한 재시도 카운터를 쓰고 seek한다. 성공·할당 변경·소비 스레드 종료 때 카운터를 정리하며,
추적 1,024건을 넘으면 안전 정지한다. 재시도 소진 시 offset을 커밋하지 않고 **해당 전용 container만 비정상 정지**한다.
원문·예외 메시지/스택 대신 고정 ERROR와 causeType만 남긴다. skip/recover commit, `.dlt.dlt` 발행, 재귀 producer는 없다.
JOB container는 계속 동작한다. 정지 로그 `anomaly dlt consumer stopped`를 기존 운영 로그 감시에서 경보해야 한다.

## 배포·복구

1. auth A1과 `idx_member_role_deactivated_at_id`를 먼저 배포하고 operations 서비스 토큰의 수신자 조회를 확인한다.
2. operations 스키마에 새 두 테이블과 인덱스를 선적용한다. Hibernate 생성 정본은
   `service/operations-service/src/test/resources/ddl/operations-schema.sql`이다. gate OFF여도 엔티티 스키마 validate 대상이다.
3. 새 notification UNIQUE를 추가한 뒤 이전 source-only UNIQUE를 제거한다. 새 키 추가가 실패하면 다음 단계로 가지 않는다.

```sql
ALTER TABLE operations.notification
    ADD CONSTRAINT uk_notification_recipient_member_id_source_type_source_id_kind
    UNIQUE (recipient_member_id, source_type, source_id, kind);
ALTER TABLE operations.notification DROP CONSTRAINT uk_notification_source_type_source_id_kind;
```

4. **모든 인스턴스**를 신코드 + gate OFF로 배포한다. 구버전 source-only 조회는 fanout 후 여러 행을 오해하므로 구버전 공존 중 ON을 금지한다.
5. namespace·세 DLT·그룹 접근 권한·auth·실제 브로커 retention을 확인하고 gate ON한다. 앱은 토픽을 만들지 않는다.
   DLT retention을 7일 초과로 늘릴 때는 처리 기록 retention도 먼저 늘린다. 존재 verifier 통과를 retention 검증으로 간주하지 않는다.
6. ACL 도입 후 operations principal에 세 DLT READ/DESCRIBE와 새 group READ만 더한다. 원천 main topic 소비/produce·타 schema SELECT는 필요 없다.
   현재 PLAINTEXT/ACL 없음 제약은 [배포 지침](deploy-guide.md#4-4-kafka-공유-클러스터-310--설계-304-a-절)을 따른다.

DDL 운영 적용은 담당자가 수행한다. 게이트 환경변수는 bootRun/실행 프로세스/배포 설정에 명시적으로 주입한다.
operations의 Dockerfile/배포 compose는 아직 없고 Jenkinsfile도 자리잡기 상태다. 이번 변경은 배포 compose 연결을 추가하지 않는다.
consumer 정지 시 auth/DB/설정 원인을 먼저 고친 뒤 앱을 재시작하면 마지막 미커밋 레코드부터 처리한다.
Kafka 보존 기간 안에 복구한다. offset을 넘겨 건너뛰거나 알림 오류를 또 DLT로 보내지 않는다.
비활성화는 gate OFF 재배포다. fanout 데이터가 생긴 뒤 구버전 binary로 단순 rollback하지 않는다. 새 UNIQUE와 신코드를 유지한다.

## 운영 확인과 후속

[결제 가이드 §6](payment-guide.md#6-서비스-간-정합성)와 #15의 사람 확인 절차로 원천 결제·주문 처리 상태를 확인한다.
알림 클릭은 기존 관리 화면으로 이동한다. FE가 외부 runbook 링크를 지원하지 않아 클릭 한 번으로 이 문서를 열지는 않는다.
알림 읽음·DLT 소비 완료·더 이상 레코드가 오지 않음은 해소 사실이 아니다. 자동 해소 알림은 만들지 않는다.

outbox oldest age, 결제 UNKNOWN/MANUAL_REVIEW/LATE_APPROVED, 주문 미회신, 권위 있는 RESOLVED fact는 후속 A3다.
#477은 이 후속과 runbook 동선까지 끝나기 전에 전체 완료로 닫지 않는다.

검증은 순수 window/형식/재시도 단위 테스트, H2+embedded Kafka+실제 ADMIN HTTP SSE/Feign,
PostgreSQL17+Testcontainers Kafka+같은 흐름/독립 Processor 경합/스냅숏 validate로 나눈다.
Docker 없는 로컬 skip을 PostgreSQL/Kafka 컨테이너 통과로 보고하지 않으며 CI에서 실제 실행을 확인한다.
