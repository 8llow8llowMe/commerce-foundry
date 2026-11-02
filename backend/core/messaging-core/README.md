# messaging-core

Kafka 메시징 공통 모듈 (#306). 설계 기준은 #304 설계 댓글 A 절이고, 코드 규칙은 [`coding-conventions.md` §12](../../docs/coding-conventions.md) 다.

- **transactional outbox + 폴링 릴레이** — 업무 트랜잭션과 같은 커밋으로 outbox 행을 저장하고, 릴레이가 주기마다 Kafka 로 보낸다. CDC 는 쓰지 않는다.
- **멱등 컨슈머(inbox)** — 처리한 eventId 를 업무와 같은 트랜잭션으로 기록해 같은 이벤트를 한 번만 처리한다.
- **재시도 · DLT** — 컨슈머 안 지수 백오프 뒤 `<topic>.dlt`.
- **이벤트 봉투** — `{eventId, eventType, schemaVersion, occurredAt, producer, aggregateType, aggregateId, payload}` JSON. 헤더 `cf-event-id` · `cf-event-type` · `cf-schema-version`.
  페이로드에 개인정보 · 비밀정보로 보이는 필드 이름(`name` · `*Phone` · `*Email` · `*Address` · `paymentKey` · `*Secret` …)이 있으면 저장 전에 `FORBIDDEN_FIELD` 로 막는다.

## 붙이는 법

1. `implementation project(':core:messaging-core')`, 테스트는 `testImplementation testFixtures(project(':core:messaging-core'))`.
2. `*PropertiesConfig` 에 `@Import(MessagingPropertiesConfig.class)`, `*BeansConfig` 에 `@Import(MessagingConfigurer.class)`.
3. 서비스 스키마에 아래 세 테이블의 **엔티티 · 리포지토리 · 어댑터**를 두고, 어댑터가 SPI 를 구현해 빈으로 둔다 (엔티티는 서비스가 소유한다 — #304 A-3).
   - 이벤트를 내면 `OutboxStore`, 받으면 `ProcessedEventStore`. 릴레이 · 기록 정리의 단일 실행 락은 ShedLock 테이블이다 — 받기만 하는 서비스도 둔다(정리가 쓴다).
4. `application.yml` 에 아래 설정을 둔다. 운영 DDL 은 서비스 DDL 스냅숏에서 나온다.

## 설정 (`commerce.messaging.*`, 0 이하 수치는 기동 실패)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `enabled` | false | `MESSAGING_ENABLED`. 꺼져 있으면 outbox 는 계속 저장하고 릴레이 · 리스너만 멈춘다 — 켜면 쌓인 행부터 나간다 |
| `namespace` | — | `KAFKA_NAMESPACE`, `commerce-foundry.<env>`. 마지막 토막이 활성 환경(local · dev · test, 없으면 prod)과 다르면 뜨지 않는다 |
| `service` | `spring.application.name` | 토픽 · 그룹 이름의 서비스 토막 |
| `required-topics` | [] | 기동 때 있는지 확인할 토픽(`<서비스>.<이벤트>`). 없으면 뜨지 않는다 |
| `max-payload-bytes` | 65536 | 봉투 한 건 상한 |
| `outbox.relay.enabled` | true | 인스턴스 단위 스위치(API 전용 인스턴스는 끈다). 끄면 릴레이와 기록 정리(outbox · inbox) 둘 다 이 인스턴스에서 돌지 않는다 |
| `outbox.relay.interval` · `batch-size` · `max-batches-per-run` | 1s · 100 · 10 | |
| `outbox.relay.publish-timeout` | 5s | 한 건 발행 응답 대기. 프로듀서 `max.block.ms`(토픽 메타데이터 대기)도 이 값이다 |
| `outbox.relay.lock-at-most-for` | 30s | 단일 실행 락 상한. `publish-timeout` 의 두 배 이상이어야 하고, 한 주기는 이 값의 절반 안에서 끝난다 |
| `outbox.relay.lock-at-least-for` | 500ms | 빠르게 끝난 실행도 락을 유지해 재획득 폭주를 막는다. 양수이며 `lock-at-most-for` 이하여야 한다 |
| `outbox.relay.oldest-age-warn` · `oldest-age-error` | 60s · 300s | 가장 오래된 미발행 나이 로그 임계. 양수이며 WARN < ERROR 여야 한다 |
| `outbox.relay.lock-table` | `shedlock` | 서비스 스키마를 붙인다(예 `orders.shedlock`). 기록 정리 락도 이 테이블 · `lock-at-most-for` 를 쓴다 — outbox 없는 서비스도 적는다 |
| `outbox.cleanup.enabled` · `interval` · `retention` · `batch-size` | true · 1h · 7d · 1000 | 발행 완료 outbox 행과 처리 기록(inbox) 정리. 키는 outbox 아래지만 inbox 정리에도 적용된다(하위 호환). `retention` 은 outbox 보존이고 Kafka 보존(7일)과 맞춘다 — 처리 기록 보존은 `inbox.retention` |
| `inbox.retention` | 7d | 처리 기록 보존 — 토픽 보존 + 재시도 창 이상 |
| `retry.attempts` · `initial-backoff` · `multiplier` · `max-backoff` | 5 · 1s · 2.0 · 30s | 컨슈머 재시도 |

Kafka 접속은 Boot `spring.kafka.*` 다 — `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:}`(Vault), `spring.kafka.admin.auto-create: false`(필수).
모듈이 강제하는 값: 멱등 프로듀서 · `acks=all` · `max.block.ms`, 자동 커밋 끔 · `auto.offset.reset=earliest` · 토픽 자동 생성 요청 끔, 키 · 값 (역)직렬화는 문자열(타입 헤더 미사용), 리스너 ack 모드 RECORD.
acks · 자동 커밋을 설정으로 덮거나 `NewTopic` 빈을 두면 기동 가드가 막는다. 켜져 있으면 리스너의 구독 토픽 · 그룹이 이 환경 namespace 로 시작해야 뜨고(토픽 패턴 구독 금지),
꺼져 있으면 `autoStartup` 을 적어도 리스너가 뜨지 않는다.
kafka-clients 의 `*Config values` INFO 로그에 접속 주소가 찍힌다 — 서비스 yml 에서 `org.apache.kafka.clients.producer.ProducerConfig` · `...consumer.ConsumerConfig` · `...admin.AdminClientConfig` 로거를 WARN 으로 둔다.
키 이름 · Vault 등록은 #310(`.env.example` · `secret-management.md`)이 정한다 — `KAFKA_BOOTSTRAP_SERVERS`(Vault) · `KAFKA_NAMESPACE` · `MESSAGING_ENABLED`.

## 서비스가 둘 테이블 (모양)

엔티티 · 인덱스 · 칸 설명은 서비스 규칙(`coding-conventions.md` §9)을 따른다. 아래는 SPI 가 기대하는 모양이다.

```text
outbox            id(Snowflake PK) · aggregate_type · aggregate_id · event_type · schema_version · payload(봉투 JSON, text)
                  · available_at · created_at · published_at · publish_attempts · last_error_code · version
                  인덱스: (published_at, available_at) 하나 — 미발행 조회(published_at is null and available_at <= now order by id) · 정리(published_at < cutoff)를 함께 받는다.
                          부분 인덱스 도입은 PostgreSQL 17 실측 뒤 판단한다. 기존 인덱스는 정리 · 지표 쿼리도 쓰므로 임의로 지우지 않는다
processed_event   (consumer_group, event_id) 유니크 · event_type · processed_at, 인덱스 (processed_at) — 정리
shedlock          name(PK) · lock_until · locked_at · locked_by   (ShedLock JDBC 표준 모양)
```

- `markPublished` 는 `published_at is null` 조건부 UPDATE, `markFailed` 는 `publish_attempts + 1` 조건부 UPDATE — 메서드마다 자기 트랜잭션이다(릴레이가 트랜잭션 밖에서 부른다).
- `append` · `ProcessedEventStore.exists` · `record` 는 호출자 트랜잭션에 합류한다.
- inbox 첫 구현은 product-service `JpaProcessedEventStore`(#309)다. order-service 도 같은 모양으로 둔다(#308, `orders.processed_event`).
- **기록 정리(`MessagingCleanup`)는 릴레이와 따로 돈다**(#378) — 서비스가 둔 저장소만 정리한다. outbox 가 있으면 발행 완료 행(`deletePublishedBefore`),
  inbox 가 있으면 처리 기록(`deleteProcessedBefore`)을 `batch-size` 씩 지운다(한 주기 최대 100 배치). 받기만 하는 서비스(지금 product)도 정리가 돈다.
  락 이름은 `commerce-messaging-outbox-cleanup` 이다(릴레이에 있던 때와 같다 — 롤링 배포 중 구 · 신 인스턴스가 같은 락을 쓴다).
- 릴레이 · 정리는 전용 스레드 하나(`messaging-background`, `MessagingBackgroundLifecycle`)에서 돈다. 메시징(`enabled`) · 인스턴스 스위치(`outbox.relay.enabled`) 중
  하나라도 꺼져 있으면 둘 다 돌지 않는다.

## 운영 전제

- 토픽 · DLT(`<topic>.dlt`)는 Infra 선언 스크립트가 만든다. 앱은 만들지 않고 `required-topics` 로 확인만 한다.
- 릴레이는 한 인스턴스만 돈다(ShedLock). 락이 만료돼 둘이 겹쳐도 표시는 조건부 UPDATE 로 한 번이고, 남은 중복 발행은 컨슈머 멱등이 흡수한다.
  현재 `shedlock-provider-jdbc` 의 `JdbcLockProvider` 는 앱(클라이언트) 시각으로 `lock_until` 을 비교한다. `usingDbTime()` 은
  `shedlock-provider-jdbc-template` 에만 있으므로 현재 provider 에 적용할 수 없다. 모든 인스턴스를 NTP 로 동기화하고 인스턴스 간 시계 차를
  `lock-at-most-for / 2` 미만(기본 15초 미만)으로 유지한다. 이는 운영 상한이며 락 중첩이 없다는 증명은 아니다 — 시계 차 · 긴 일시정지로 락이 만료될 수 있다.
  `lock-at-least-for`(기본 500ms)는 빠른 실행의 재획득을 늦출 뿐 시계 차를 보정하지 않는다. 다중 인스턴스 운영 때 jdbc-template + DB 시각 사용을 재검토한다.
- 발행이 실패하면 그 aggregateId 만 그 주기 동안 멈추고(같은 키의 순서 보존) 다른 키는 계속 보낸다. 실패 행은 시도 수 · 실패 코드(예외 타입)를 남기고
  다음 주기에 다시 보낸다 — `available_at` 백오프를 넣지 않는다. 1~9회 WARN, 10 · 100 · 1000회 ERROR 를 남기고, 그 사이와 이후 ERROR 는 행당 1분에 1회다.
  억제 상태는 실행 인스턴스의 메모리에만 있고 재시작 · 릴레이 소유자 변경 시 첫 실패 경고가 다시 나올 수 있다. 실패 카운터는 모든 전송 실패를 센다.
  Kafka 성공 뒤 `markPublished` 예외는 WARN + `commerce.messaging.outbox.publish.mark.failed.total` 이며 전송 실패 카운터와 구분한다.
  같은 키의 뒤 행은 이번 주기 동안 멈추고 다음 주기에 같은 eventId · 봉투를 재발행한다. 컨슈머 inbox 가 중복을 흡수해야 한다.
- **토픽과 함께 `<topic>.dlt` 도 있어야 한다.** DLT 가 없으면 재시도를 다 쓴 레코드를 보내지 못해 그 파티션이 같은 레코드를 계속 다시 처리하며 멈춘다.
  쓰는 토픽 · DLT 를 `required-topics` 에 적어 기동 때 확인한다.
- DLT 재처리는 사람이 한다 — Kafka UI 로 재전송하거나 outbox 행의 `published_at` 을 비워 릴레이가 다시 보내게 한다. DLT 헤더에는 예외 · 원인 클래스 이름만 있고 예외 메시지 · 스택트레이스는 없다.
- `auto.offset.reset=earliest` 라 이미 쌓인 토픽에 **새 컨슈머 그룹**을 붙이면 보존 기간(7일)치를 처음부터 처리한다. 지난 이벤트가 필요 없는 그룹은 리스너에 `properties = "auto.offset.reset=latest"` 를 둔다.
- **봉투에는 진위 확인 수단이 없다**(PLAINTEXT · ACL 없음 전제, #304 A-2). 돈 · 재고 상태를 바꾸는 컨슈머는 이벤트를 알림으로만 보고 원본 서비스 internal API 로 재조회해 확정한다(웹훅과 같은 원칙).
  서명 헤더를 둘지는 결제 이벤트(#308) 전에 정한다.
- 지표: `commerce.messaging.outbox.published.total` · `…outbox.publish.failed.total{error}` · `…outbox.publish.latency` · `commerce.messaging.consumer.dlt.total{topic}`.
  미발행 행 수 · 가장 오래된 미발행 나이 게이지(`commerce.messaging.outbox.unpublished.count` · `…unpublished.oldest.age`)는 저장소를 가진 서비스가 단다 —
  operations-service `OutboxMetrics` 참고.
  릴레이는 락을 획득한 주기의 시작에 이 **초 단위 나이 게이지를 한 번 읽어 DB 조회 1회**를 추가한다. 기본 60초 WARN · 300초 ERROR 이고
  심각도별로 1분에 1회만 남긴다(심각도 상승은 즉시, 정상 복귀 · 임계 진동도 억제 시각을 초기화하지 않음).
  레지스트리 · 게이지가 없거나 NaN · 조회 실패면 임계 로그를 생략하고 발행은 계속한다. 메시징/릴레이가 꺼졌거나 락을 못 잡으면 이 경고도 돌지 않으므로
  별도 스크랩 · 알림을 대체하지 않는다. 게이지 없는 서비스에는 임계 경고가 없다는 점을 배포 시 확인한다.
  operations · order · payment 의 oldest-age 공급자는 공통 `OutboxAgeMetrics.oldestAgeSeconds` 를 사용한다. DB 조회의 `RuntimeException` 은
  `commerce.messaging.outbox.unpublished.oldest.age.observation.failed.total` 을 올리고 NaN(관측 불가)을 반환한다. 정상 0(미발행 없음/미래 예약)과 다르다.
  예외를 Micrometer 로 넘기면 `DefaultGauge` 가 원문 throwable 을 WARN/DEBUG 로 기록하므로 공급자 내부에서 처리하며 원문 로그를 남기지 않는다.
  실패 카운터 기록도 실패하면 예외 없이 NaN 을 유지한다. 이 보호는 oldest-age 공급자에만 적용한다(count 게이지는 기존 동작 유지).
  NaN/관측 실패 카운터도 별도 알림 대상으로 둔다 — 나이를 읽지 못한 상태를 적체 없음으로 해석하지 않는다.

## 인덱스 실측 계획 (#476, 미완료)

현재 환경에는 Docker 실행 파일이 없어 PostgreSQL 17 `EXPLAIN (ANALYZE, BUFFERS)` 를 실행하지 못했다. 인덱스 · 엔티티 · DDL 은 변경하지 않았다.
측정 결과 없이 인덱스 완료 기준을 통과로 처리하지 않는다. Docker 가 있는 환경에서 다음 작업을 별도로 수행한다.

1. PostgreSQL 17 Testcontainers 에 실제 서비스 DDL 스냅숏을 적용하고, 미발행 0 / 1,000 / 100,000건을 만든다. 이미 발행한 행과 미래 예약 행도 일정 비율로 섞고
   데이터 규모 · 분포 · PostgreSQL 버전 · 설정을 기록한다. 각 구성에서 `ANALYZE` 후 같은 기준 시각 · batch-size 로 여러 번 측정한다.
2. 실제 저장소 질의(릴레이 `published_at is null and available_at <= ? order by id limit N`, 정리, count, oldest-age)를 모두
   `EXPLAIN (ANALYZE, BUFFERS)` 로 실행하고 실행 시간 · 정렬 · 실제 행 수 · shared hit/read 를 남긴다.
3. 기존 `(published_at, available_at)` 유지 상태와 추가 `(id) where published_at is null` 후보를 비교한다.
   `(available_at, id) where published_at is null` + 정렬 변경 후보는 같은 키의 순서 계약을 먼저 검증한다 — 측정만으로 순서 변경을 승인하지 않는다.
4. 개선을 확인한 경우에만 별도 인덱스 변경을 결정한다. 부분 인덱스는 `@Index` 로 표현할 수 없으므로 실제 스냅숏 관리 방식을 정하고,
   운영 DDL 은 `CREATE INDEX CONCURRENTLY` 를 트랜잭션 밖 단독 실행하도록 제공한다. 실패한 INVALID 인덱스는 확인 후 삭제 · 재생성한다.
   기존 인덱스는 정리 · 지표 쿼리 영향까지 확인하기 전 유지한다.

## 테스트

- 모듈 자체: 단위 테스트 + 내장 Kafka(`spring-kafka-test`, KRaft) 왕복 — 커밋하면 발행 / 롤백하면 미발행, 중복 1회 처리, 재시도 상한 뒤 DLT. Docker 가 필요 없다.
- testFixtures: `InMemoryOutboxStore` · `InMemoryProcessedEventStore`(트랜잭션 커밋 · 롤백 흉내 — 동시성 · 예외 타입은 흉내 내지 않는다), `NoOpTransactionManager`,
  `MessagingKafkaContainers`(apache/kafka 4.3.1 Testcontainers).
- 서비스 JPA 어댑터의 SPI 계약(조건부 표시 · id 순 · available_at · 정리)은 testFixtures `OutboxStoreContract` 다(#308) — 서비스 `JpaOutboxStoreTest`(PostgreSQL)가
  상속해 저장소만 넘기고, 트랜잭션 밖 append 거절 · 칸 길이 · 지표 쿼리는 서비스 테스트가 더 본다(operations · payment). `InMemoryOutboxStore` 도 같은 계약을 모듈 테스트로 돈다.
  order-service 의 outbox 는 만료 · 취소 영속성 계약 테스트가 함께 보고, 아직 이 계약을 상속하지 않는다.
