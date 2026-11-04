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
operations 소비·임계값·주기 수집·알림·억제·해소 연결은 A4a 이후 후속 작업이며 이번 원천 API에는 포함하지 않는다.

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
