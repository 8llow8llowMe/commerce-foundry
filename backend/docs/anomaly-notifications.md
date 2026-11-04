# 이상 징후 알림 (#477 A2)

operations-service가 기존 DLT 메타데이터를 받아 활성 관리자에게 `ANOMALY` 알림을 저장한다. A2는 DLT 유입만 다룬다. outbox 장기 미처리·복구 탐지는 A3, 결제 불확실 상태 탐지는 A4 범위다. DLT 유입이 없다는 사실은 복구 증거가 아니므로 해결 알림을 만들지 않는다.

## 전달 방식 비교

| 방식 | 역할 | 고려 사항 |
|---|---|---|
| 기존 DLT 구독 — A2 | 생산자 변경 없이 재시도를 소진한 이벤트 탐지 | 업무 원인 확정 불가, DLT 읽기 권한 필요 |
| 전용 이상 징후 이벤트 | 향후 도메인이 확인한 이상·복구 증거 전달 | 생산자 계약·outbox·버전 관리 필요. A2 미구현 |
| internal POST | 향후 동기 운영 신호 전달 | 서비스 인증·재시도·중복 방지·가용성 결합 검토 필요. A2 미구현 |

`TopicNames`로 namespace를 붙인 `operations.job-run.dlt`, `order.order-expired.dlt`, `order.order-canceled.dlt`, `payment.payment-approved.dlt`만 명시적으로 구독한다. 패턴 구독·DLT 재발행은 없다. 리스너는 topic·partition·offset만 읽고 key·value·headers는 읽거나 남기지 않는다. 결제·주문·재고 상태를 바꾸지 않는다.

결제 승인 DLT는 CRITICAL, 나머지는 WARNING이다. 제목은 담당자의 점검을 요청하는 고정 한국어 문구다. 개인정보·주문번호·예외 원문·Kafka 이름을 넣지 않는다. 결제는 기존 `/admin/payments`, 작업은 `/admin/jobs`로 연결하고 주문 이벤트 알림 링크는 null이다. 목록·읽음·모두 읽음·SSE 계약은 유지한다. `sourceType=DLT_ALERT`, `sourceId=수신자별 알림 Snowflake ID`로 기존 JOB 출처 유니크 제약을 보존한다. FE 신규 API·화면은 필요하지 않다.

## 억제·중복·트랜잭션

`operations.notifications.dlt-alert.enabled`는 기본 false이며 `DLT_ALERT_ENABLED`로 설정한다. 이 값과 `commerce.messaging.enabled`가 모두 true여야 리스너와 추가 토픽 기동 검사가 등록된다. 억제 기간은 마지막 실제 알림 시각부터 정확한 Duration으로 판정한다. 기본 1h, 양수부터 7d까지 설정할 수 있다. 마지막 알림+기간과 정확히 같은 시각이면 다시 알린다. 억제된 이벤트는 기간을 연장하지 않는다.

`dlt_alert_state`는 허용 토픽마다 한 행이며 PESSIMISTIC_WRITE NOWAIT와 @Version으로 파티션 간 처리를 직렬화한다. `dlt_partition_checkpoint`는 관측한 토픽·파티션마다 최고 offset을 보관한다. 잠금 순서는 토픽 → 체크포인트다. 같거나 낮은 offset은 중복 처리하지 않는다. 억제된 높은 offset은 체크포인트만 전진한다. 토픽 묶음은 고객·이벤트 수와 무관하게 알림량과 상태 크기를 제한하기 위한 선택이다. 일반 알림 보존 정리는 이 두 테이블을 지우지 않는다.

수신자 전체 페이지 조회는 DB 트랜잭션 밖에서 끝낸다. Processor가 15s 제한 트랜잭션에서 토픽 잠금을 얻은 후 Clock을 읽는다. 체크포인트·억제 시각·모든 수신자 알림은 함께 커밋·롤백된다. 첫 INSERT 경합에서 진 트랜잭션은 전체 롤백하고 재전달에서 새로 시작한다. PostgreSQL이 중단한 트랜잭션을 재사용하지 않는다. 기존 행 잠금 충돌은 NOWAIT로 즉시 실패한다. 첫 INSERT 대기의 실제 제한은 JDBC·DB의 트랜잭션 시간 제한 적용에 의존하므로 운영 대기 지표도 확인한다.

리스너는 DB 커밋 성공 후에만 ACK한다. 실패는 원문 예외를 전파하지 않고 고정 WARN과 5s 지연 nack으로 무제한 재시도한다. 별도 무제한 재시도 오류 처리기도 DLT recoverer를 쓰지 않아 `.dlt.dlt`를 만들 수 없다.

수신자는 서비스 토큰을 붙인 `GET /internal/v1/admin-notification-recipients?afterId&size=100`으로 조회한다. 기존 auth-service 서킷과 Feign 연결 2s·읽기 3s 제한을 쓴다. 성공 봉투·본문·페이지 크기 100 이하·양의 long 문자열 정규형·페이지 간 엄격한 증가를 검증한다. 최대 1,000명, 끝 확인을 포함해 최대 11회 요청이다. 전체 0명·비정상 응답·호출 실패·상한 초과는 DB 진행 없이 실패한다. Kafka max.poll.interval은 전체 원격 조회+DB 예산보다 길어야 한다(기본 5m). 중복·억제 이벤트도 먼저 수신자를 조회하므로 auth 장애 중 지연될 수 있다. 페이지 조회는 auth DB 전체를 묶은 스냅숏이 아니다.

SSE는 커밋 뒤 현재 인스턴스 연결로 최선 전달한다. 커밋 후 전송 전 종료·다른 인스턴스 연결·SSE 실패는 저장된 목록으로 따라잡는다. SSE 실패로 커밋된 알림·체크포인트를 취소하지 않는다.

## 실패·재시도·복구 근거

| 증거 | 처리 | 복구 근거 |
|---|---|---|
| 새 DLT offset | 억제 기간 밖이면 관리자마다 알림 저장 | 자동 추론하지 않음 |
| 같거나 오래된 offset | 중복 알림 없음 | 해결 신호 아님 |
| auth 실패·비정상 페이지·0명·1,000명 초과 | ACK 없이 고정 WARN, 5s 뒤 재시도 | 정상 수신자 조회와 DB 커밋 |
| 잠금 충돌·첫 INSERT 경합·DB 실패·수신자 일부 저장 실패 | 상태·전체 알림 롤백, 새 트랜잭션 재시도 | DB 커밋 |
| DB 커밋 후 ACK 전 종료 | 재전달 offset 건너뜀 | 저장된 워터마크 |
| SSE 끊김·전송 실패 | 저장된 목록으로 복원 | 알림 전달 상태이며 업무 복구 증거는 아님 |
| 새 DLT 유입 없음 | 해결 알림 없음 | 운영자 확인 또는 후속 도메인 복구 탐지 필요 |

지표·추세는 건수·지연·지속 시간 변화를 보여 주고, 사용자 알림은 지금 담당자가 행동해야 하는 상황을 알려 준다. 억제는 Kafka lag·DLT 보존·outbox 나이·서비스 상태 감시를 대신하지 않는다. 알림 채널 장애는 같은 채널로 보고할 수 없으므로 고정 재시도 WARN과 그룹 lag를 외부에서 감시한다.

## 배포·ACL·활성화

1. `service/operations-service/src/test/resources/ddl/operations-schema.sql`에서 신규 두 테이블·감사 컬럼·버전 컬럼·토픽 PK·`(topic, partition_number)` 유니크 제약 DDL을 추출해 활성화 전에 적용한다. 운영 ddl-auto는 none이다. 전체 스냅숏을 기존 DB에 재실행하지 않는다.
2. `docker/kafka/topics.txt`의 기존 네 DLT와 namespace를 확인한다. operations에는 해당 토픽 DESCRIBE·READ, `<namespace>.operations.dlt-alerts` 그룹 READ가 필요하다. DLT WRITE는 필요 없다. 기존 outbox·워커 ACL은 별개다. 활성화 시 토픽 조회 실패는 기동을 멈춘다.
3. auth A1 수신자 API 배포, operations-service internal 토큰 허용, 활성 관리자 존재, auth 서킷·시간 제한을 확인한다.
4. 실제 배포 프로세스에 `MESSAGING_ENABLED=true`, 올바른 `KAFKA_NAMESPACE`, `DLT_ALERT_ENABLED=true`를 주입한다. 새 비밀 키는 없다. 억제 기간은 대응 주기에 맞춰 변경한다.
5. 그룹 lag·고정 재시도 WARN·저장된 알림·SSE와 목록 복원을 확인한다. 새 그룹은 보존된 DLT를 처음부터 읽으며 토픽 단위 억제를 적용한다.

## 운영자 대응 절차

1. 결제 CRITICAL은 즉시 승인 전달과 후속 주문 동기화를 점검한다. WARNING은 담당 작업·주문 이벤트 소비자와 DLT를 점검한다. 키·본문·헤더·개인정보·자격 증명을 알림이나 티켓에 복사하지 않는다.
2. 제한된 운영 도구에서 토픽·파티션·offset·lag를 연결해 본다. 앱은 본문을 저장·공개하지 않는다. auth·수신자 설정·DB·애플리케이션·원 소비자의 상태를 확인한다.
3. 원 서비스 문제를 해결하고 승인된 재처리·대사 절차를 따른다. 결제는 [서비스 간 정합성](payment-guide.md#6-서비스-간-정합성)과 [운영자 수동 확인](payment-guide.md#운영자-수동-확인)을 확인한다. 주문 이벤트는 [재고 선점 해제](order-flow-guide.md#3-5-주문-만료--취소-이벤트--선점-해제-309)와 [결제 승인 소비](order-flow-guide.md#3-6-결제-승인-소비-308-pr-b2)를 확인한다. 이 구독자는 업무 이벤트 재발행·상태 변경·복구 확정을 하지 않는다. 업무 기록과 소비 진행을 별도 확인한다.
4. 토픽을 임의 삭제·재생성하지 않는다. 재생성하면 offset은 초기화되지만 체크포인트는 남는다. 필요하면 구독자를 중지하고 새 토픽 세대·사고 범위를 확인한 뒤 DB 운영자가 해당 토픽의 체크포인트·억제 상태만 통제된 트랜잭션으로 초기화하고 재시작한다. 알림 강제 발송·일반 보존 정리를 위해 워터마크를 지우지 않는다.
5. 알림 실패가 반복되면 외부 lag 감시를 유지하며 의존성을 복구하고 같은 DLT를 재시도한다. 수동 ACK·건너뛰기는 정상 복구 절차가 아니다.

## 검증

A4a는 payment의 읽기 전용 [결제 이상 후보 원천 API](payment-observation-guide.md)를 제공한다.
UNKNOWN 전체와 수동 검토 부분집합은 합산하지 않고, 누락·미래 후보 시각이나 원천 실패를 정상·해소로 간주하지 않는다.
operations의 엄격 소비·순수 정책은 아래 A4b-1이며 주기 수집·영속 상태·알림 전달 연결은 후속 작업이다.

## 결제 이상 원천 소비·순수 사건 정책 (#477 A4b-1)

`PaymentAnomalyObservationPort`는 A4a 원천을 읽고 같은 notification 컨텍스트의 불변 domain만 반환한다.
여섯 버킷 전체를 검증한 뒤 UNKNOWN(전체와 수동 확인 부분집합), IN_PROGRESS, RETURNED_READY, LATE_APPROVED,
DONE_ORDER_UNSYNCED 다섯 사건을 불변 상태와 신호로 함께 판정한다. 일반 UNKNOWN의 최솟값을 부분집합에서 빼서 추정하지 않는다.

기본 임계는 UNKNOWN 5분·수동 확인 0초·IN_PROGRESS 5분·RETURNED_READY 10분·LATE_APPROVED 0초·DONE 미동기화 10분이다.
나이는 요청 시작/PG 승인 기준이며 상태 체류시간이나 동일 결제의 연속 관측 시간이 아니다. 불변 설정 모델은 수동 확인·늦은 승인만 0초를 허용하고
나머지 임계는 1초 이상, 모두 최대 1일이다. 억제는 기본 1시간(1초~7일), 표본 나이는 기본 30초(1초~5분)다. 활성화 설정 바인딩은 아직 없다.

최초 정상 표본도 원천/로컬 워터마크를 보존한다. 양수이며 임계 이상이면 사건을 개시하고, 마지막 신호 로컬 시각에서 억제 경계 이상이면 반복한다.
UNKNOWN의 수동 확인 조건 첫 충족은 억제 중에도 CRITICAL로 한 번 승격하며 억제를 다시 시작한다. 최초부터 수동 확인이면 개시 신호 하나만 만든다.
최고 긴급도는 사건 종료까지 유지하고 부분집합 출몰로 재승격하지 않는다. 양수 감소·젊어짐은 사건을 유지하되 임계 미만이면 반복하지 않는다.
유효한 최신 0/null만 INFO 후보 소멸을 한 번 만들고, 이후 재개는 이전 억제와 무관한 새 사건이다. 후보 소멸은 PG·주문 정합성·취소 완료의 증명이 아니다.
처음 0, 실패·잘못된 버킷/부분집합, 같은/이전·오래된·미래 표본, 이전 수락 로컬 시각보다 역행한 판정은 신호 없이 전체 상태와 워터마크를 보존한다.

이 단계는 SOURCE 읽기와 순수 판정만 제공한다. 아래 A4b-2가 영속 상태·수신자 fanout 저장을 추가하며 스케줄러·SSE 연결은 후속이다.
HTTP 전/후/최종 Clock·nanoTime 검증은 A4b-3, 다섯 행의 원자 저장·공통 워터마크 검증은 A4b-2에서 연결한다.
정책 Map의 반복 순서는 계약이 아니다. 후속 최초 INSERT는 잠금 조회의 `ORDER BY family`와 동일한 명시 정렬을 사용해야 한다.

## 결제 이상 사건·전체 알림 원자 저장 (#477 A4b-2)

`PaymentAnomalyAlertStateProcessor`는 이미 검증한 원천 표본·정책 설정·양수이며 중복 없는 수신자 1~1,000명과 필수 timing을 받는 내부 저장 경계다.
수신자는 절삭하지 않으며 전체가 유효해야 한다. 전체 상태를 `ORDER BY family`·PESSIMISTIC_WRITE/NOWAIT로 한 번 읽은 뒤 최종 Clock과 ticker를 읽고 순수 정책을 적용한다.
0행 또는 허용된 다섯 키와 공통 원천/로컬 워터마크가 모두 일치할 때만 판정한다. 부분 행·잘못된 키·워터마크 손상은 쓰기 없이 실패하며 자동 보충하지 않는다.
최초 정상 표본도 상태 다섯 행을 INSERT하고 알림은 만들지 않는다. 빈 조회 뒤에는 경쟁자가 먼저 커밋해도 신규 INSERT를 유지한다.
기존 행은 같은 영속 컨텍스트에서 로딩·관리·쓰기 잠금이 확인된 Entity만 갱신해 감사 생성시각과 버전을 보존한다.

상태와 모든 알림은 `@Transactional(timeout=15)` 한 트랜잭션에 저장한다. 초기 INSERT 경합·잠금 충돌·중간 알림 실패는 전체 롤백한다.
NOWAIT는 기존 다섯 행 잠금 조회에 적용되며, 빈 조회가 존재하지 않는 키를 잠그거나 초기 PK INSERT 대기를 없애지는 않는다.
15초 설정은 모든 Java 처리·pool 획득·네트워크·커밋을 정확히 15초에 중단하는 보장이 아니다.
새 batch port는 호출자 트랜잭션을 요구하고 1~5,000개의 신규·미읽음·감사시각/버전 없는 알림과 ID 중복을 전체 사전검증한 뒤 `saveAllAndFlush`한다.
반환은 입력 ID·순서에 대응하는 불변 목록이다. 한 번의 명시 flush는 JDBC batch 또는 SQL 한 문장을 뜻하지 않는다.
각 알림은 `ANOMALY`, `PAYMENT_ANOMALY_ALERT`, 수신자별 Snowflake 알림 ID를 sourceId로 사용하며 기존 전역 source 유니크 제약을 유지한다.
문구는 고정 한국어이고 링크는 `/admin/payments`다. 후보 소멸 INFO는 업무 복구·결제/주문 정합성 완료를 선언하지 않는다.

배포 전 생성 DDL 스냅숏의 `payment_anomaly_alert_state` 신규 테이블·CHECK·주석만 적용한다. 전체 스냅숏을 기존 DB에 재실행하지 않는다.
운영 ddl-auto=none과 기존 단건·읽음·JOB·DLT·outbox 알림 경로는 유지한다. 새 Vault 비밀과 FE 응답 스키마 변경은 없다.
이 경계는 자동 실행되지 않는다. 실제 HTTP 전후 Clock/nanoTime 기록·수신자 조회·활성화·커밋 후 SSE 연결은 E3b 후속 범위다.
반환 목록 자체는 외부 트랜잭션이 있을 때 커밋 증거가 아니며, 후속 전달은 실제 커밋 이후에 연결해야 한다.
PG 시각 컬럼은 마이크로초 정밀도로 로컬 Clock의 나노초가 반올림될 수 있어 서브마이크로초 역행의 정확한 복원은 보장하지 않는다.
합성 1,000명 fanout의 시간·할당·2ms 샘플 heap은 해당 트랜잭션 관측값이며 SLA·retained heap·SSE 큐 포화 시 JVM 전체 안전성을 증명하지 않는다.

### 필수 시간 검증·미연결 전달 기반 (#477 E3a)

저장 메서드는 `accept(sample, recipients, settings, timing)` 단일 경계다. 불변 `PaymentAnomalyObservationTiming`의
`beforeHttp`, `afterHttp`, `startedNanos`는 필수이며 null이나 자동 대체 timing을 허용하지 않는다.
상태 잠금 조회 뒤 최종 Clock과 전용 `paymentAnomalyTicker`를 읽는다. HTTP 전후 wall-clock 역행, 최종 시각이 HTTP 종료보다 이른 경우,
음수 elapsed 또는 최대 관측 나이 초과면 상태·알림 전체 무쓰기다. 정확한 기본 30초 경계는 허용하며 기존 원천 freshness·워터마크 검증도 유지한다.
`nanoTime` 시작값의 부호는 제한하지 않고 정상 overflow wrap을 포함한 차이를 사용한다.
예산은 원천 HTTP 시작부터 잠금 후 판정까지다. auth 전체 조회나 flush·commit의 강제 종료 시한이 아니며 관측하지 않은 모든 시각 역행을 증명하지 않는다.

`PaymentAnomalyAlertPushProcessor → PaymentAnomalyAlertPushPort → PaymentAnomalyAlertPushAdapter`는 기존 `NotificationPushPort`로 전달하는
미연결 기반이다. StateProcessor·Facade·수집기에서 호출하지 않고 자동 실행·새 활성화 설정도 없다. 생성 시 worker 스레드는 0개다.
배치는 1~5,000건 전체 검증 뒤 불변 복사하며 배치 하나가 task 하나다. 단일 worker·대기 16배치·AbortPolicy로 제한한다.
개별 RuntimeException 뒤 나머지를 계속 전달하고 포화·종료 거절은 고정 event key와 reason만 WARN으로 남긴다. 식별자·본문·원문 예외는 기록하지 않는다.
거절된 전달을 DB에 재저장하거나 저장 결과를 취소하지 않는다. 실제 커밋 완료 프록시 반환 뒤 전달 연결은 E3b에서 검증한다.

Adapter가 plain ThreadPoolExecutor를 단독 소유하고 PreDestroy에서 신규 제출 차단, shutdownNow의 대기 큐 폐기·실행 중 interrupt 요청 후
최대 5초 종료 대기를 수행한다. 호출자 interrupt는 복원하고 반복 close는 추가 대기하지 않는다. 매 알림 전 closed·interrupt를 확인하되
검사 직후 send와의 경합으로 한 전송이 시작될 수 있다. 이미 진행 중인 동기 SSE 전송은 interrupt를 무시할 수 있으며 강제 5초 종료를 보장하지 않는다.
worker는 non-daemon이므로 비협조 전송은 JVM 종료를 지연시킬 수 있다. 5초 뒤 미종료는 경고하며 context 종료 이벤트 즉시 차단이나
후속 collector의 종료 순서는 이번 범위에서 보장하지 않는다.

서로 다른 최대 배치 17개의 실행·큐 점유와 18번째 거절·배출 시험은 기능 검증이다. 대기 80,000건·실행 5,000건 외에 PG 생산 중 일시 객체와
기존 SSE 연결·다른 작업까지 포함한 운영 heap 여유는 증명하지 않는다. PG 생산 중 포화·768MiB 통합 계측과 활성화 승인은 E3b 후속이다.

A3a(PR #548)는 payment-service의 읽기 전용 [outbox 원천 관측 API](outbox-observation-guide.md)를 제공한다. A3b는 아래의 operations 탐지기를 연결한다.

## 결제 outbox 지연·해소 관측 (#477 A3b)

기본 비활성인 `PAYMENT_OUTBOX_ALERT_ENABLED`는 messaging 활성화와 독립적이다. 릴레이가 꺼진 상태에서도 관측할 수 있다. operations 전용 단일 스레드 스케줄러가 초기 1분 후, 실행 종료마다 1분 간격으로 동작한다. 기존 작업 lease 회수·플래너·알림 보존 스케줄러와 HTTP 대기 스레드를 공유하지 않는다.

수집 락 이름은 `operations-payment-outbox-alert`, 기본 lease는 2분이다. 락 안에서 auth 활성 관리자 전체(100명 페이지, 최대 11회·총 1,000명)를 먼저 조회하고, 성공한 경우에만 payment의 최신 관측을 읽는다. 0명·불완전 페이지·상한 초과·인증 실패는 실패이며 아무 상태도 전진하지 않는다. 두 HTTP 호출은 DB 트랜잭션 밖이다. 기존 internal 서비스 토큰 발급기·Feign 설정과 connect 2초/read 3초, payment-service 서킷을 사용하고 HTTP 재시도하지 않는다. 페이지 전체 수집이나 전체 HTTP 구간에 대한 강제 종료 deadline을 보장하지 않는다.

설정은 `operations.notifications.payment-outbox-alert` 아래 `threshold=5m`, `suppression=1h`, `initial-delay=1m`, `fixed-delay=1m`, `max-observation-age=30s`, `lock-at-most=2m`이다. 기간은 양수·상한 검증을 받는다. 원천 count는 JSON 정수 long이며 음수·누락·null·문자열·소수·boolean·overflow를 거부한다. 시각은 ISO Instant 문자열(마이크로초 이하 정밀도), 0건이면 oldest=null, 양수이면 oldest<=observedAt여야 한다.

최종 처리에서는 `operations.outbox_alert_state`의 PAYMENT 한 행을 NOWAIT로 잠근 뒤 Clock을 읽는다. observedAt은 이전 수락 값보다 엄격히 증가해야 하고 미래가 아니며 최종 현재 시각에서 30초 이내여야 한다. 최종 로컬 시각은 이전 수락 시각보다 뒤이거나 같고, HTTP 전·후·최종 로컬 시각이 역행하지 않아야 한다. nanoTime으로 잰 원천 HTTP 시작부터 최종 처리까지도 30초 이내여야 한다. 관측하지 않은 시계 epoch 사이의 역행을 timestamp만으로 증명할 수는 없다.

NORMAL의 첫 정상 관측도 워터마크를 저장한다. 양수이고 `observedAt-oldestReadyAvailableAt>=5m`면 ACTIVE와 ANOMALY/CRITICAL을 저장한다. 첫 감지 시각은 oldest가 아닌 해당 observedAt이다. ACTIVE에서 같은 조건이 지속되면 실제 마지막 알림의 로컬 시각에서 1시간 경계 이상일 때만 반복한다. 억제 중 관측은 반복 시각을 늦추지 않는다. 양수지만 아직 임계 미만이면 ACTIVE를 유지하고 해소로 보지 않는다. ACTIVE의 신선한 0/null에서만 NORMAL과 ANOMALY/INFO 해소를 한 번 저장한다. 사고 전의 0건은 해소 알림이 없고 새 사고는 이전 사고의 억제 기간과 관계없이 즉시 알린다.

상태와 모든 수신자의 알림은 15초 상한 DB 트랜잭션 하나다. 수신자별 Snowflake 알림 ID를 sourceId에도 넣고 sourceType은 PAYMENT_OUTBOX_ALERT이다. 기존 전역 source 유니크 제약은 유지한다. 첫 INSERT 경쟁·잠금 충돌·중간 알림 실패는 전체 롤백하고 다음 실행이 다시 시도한다. ShedLock lease 만료 뒤 겹쳐도 DB 락·워터마크가 최종 정합성을 지킨다. 커밋 후 불변 수신자 알림 배치를 전용 단일 푸시 스레드의 최대 16개 배치 큐에 전달한다. 느린 SSE는 수집 스레드를 막지 않으며 큐 포화·전달 실패는 안전한 WARN과 함께 최선 전달을 포기하고 저장된 목록으로 복원한다. 인스턴스 간 SSE 브로드캐스트는 제공하지 않는다. 상태 행은 알림 보존 정리로 지우지 않는다.

수집 실패·비정상/오래된/같거나 이전/미래 관측·시계 역행은 기존 상태·사고 시각·워터마크를 그대로 보존한다. 수집 실패 WARN과 timing/freshness/watermark rejected WARN으로 관측 불가를 구분하고 auth·payment·DB·시계를 점검한다. 정상 또는 해소로 추정하지 않는다. 해소는 ready 미발행 행이 없다는 뜻뿐이며 downstream 반영·PG 결제 결과·릴레이 건강을 증명하지 않는다. 알림 문구는 고정 한국어이고 원문 응답·예외·토큰·URL을 로그/알림에 싣지 않는다.

배포 전 auth A1 API와 payment A3a(PR #548), 기존 internal 토큰 배포를 확인하고 Hibernate 생성 operations DDL 스냅숏의 outbox_alert_state 신규 테이블을 적용한다. 새 Vault 비밀은 없다. 현재 operations compose 서비스 정의가 없으므로 실제 배포 프로세스 환경에 플래그를 주입한다. FE 응답 스키마 변경은 없으며 기존 ANOMALY/CRITICAL·INFO 및 목록을 사용한다.

로컬 테스트는 정확한 억제 경계, 중복·억제 워터마크, 0명, 비정상·다중 페이지·상한 수신자, 메타데이터 전용 리스너, ACK/NACK, EmbeddedKafka → H2 → 실제 알림 스트림, 실패 재전달과 sentinel 원문 로그 미노출을 확인한다. PostgreSQL 17 테스트는 실제 JPQL NOWAIT, 동시 첫 INSERT·여러 파티션, 수신자 중간 실패 롤백·재시도를 확인한다. Testcontainers Kafka+PostgreSQL은 커밋 후 SSE 스트림 전달을 확인한다. Docker 테스트는 EnabledWithDockerOrOnCi로 로컬 Docker 부재 시 건너뛰고 CI는 Docker를 요구한다. DDL은 OperationsSchemaSnapshotTest로 대조하며 마지막에 operations cleanTest/check를 실행한다.
