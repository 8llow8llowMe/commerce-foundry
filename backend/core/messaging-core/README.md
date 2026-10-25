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
   - 이벤트를 내면 `OutboxStore`, 받으면 `ProcessedEventStore`. 릴레이 단일 실행 락은 ShedLock 테이블이다.
4. `application.yml` 에 아래 설정을 둔다. 운영 DDL 은 서비스 DDL 스냅숏에서 나온다.

## 설정 (`commerce.messaging.*`, 0 이하 수치는 기동 실패)

| 키 | 기본값 | 뜻 |
|---|---|---|
| `enabled` | false | `MESSAGING_ENABLED`. 꺼져 있으면 outbox 는 계속 저장하고 릴레이 · 리스너만 멈춘다 — 켜면 쌓인 행부터 나간다 |
| `namespace` | — | `KAFKA_NAMESPACE`, `commerce-foundry.<env>`. 마지막 토막이 활성 환경(local · dev · test, 없으면 prod)과 다르면 뜨지 않는다 |
| `service` | `spring.application.name` | 토픽 · 그룹 이름의 서비스 토막 |
| `required-topics` | [] | 기동 때 있는지 확인할 토픽(`<서비스>.<이벤트>`). 없으면 뜨지 않는다 |
| `max-payload-bytes` | 65536 | 봉투 한 건 상한 |
| `outbox.relay.enabled` | true | 인스턴스 단위 스위치(API 전용 인스턴스는 끈다) |
| `outbox.relay.interval` · `batch-size` · `max-batches-per-run` | 1s · 100 · 10 | |
| `outbox.relay.publish-timeout` | 5s | 한 건 발행 응답 대기. 프로듀서 `max.block.ms`(토픽 메타데이터 대기)도 이 값이다 |
| `outbox.relay.lock-at-most-for` | 30s | 단일 실행 락 상한. `publish-timeout` 의 두 배 이상이어야 하고, 한 주기는 이 값의 절반 안에서 끝난다 |
| `outbox.relay.lock-table` | `shedlock` | 서비스 스키마를 붙인다(예 `orders.shedlock`) |
| `outbox.cleanup.enabled` · `interval` · `retention` · `batch-size` | true · 1h · 7d · 1000 | 발행 완료 행 정리. 보존은 Kafka 보존(7일)과 맞춘다 |
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
                  인덱스: 부분 인덱스 (available_at, id) where published_at is null — 미발행 조회(published_at is null and available_at <= now order by id)
                          · (published_at) — 정리
processed_event   (consumer_group, event_id) 유니크 · event_type · processed_at, 인덱스 (processed_at) — 정리
shedlock          name(PK) · lock_until · locked_at · locked_by   (ShedLock JDBC 표준 모양)
```

- `markPublished` 는 `published_at is null` 조건부 UPDATE, `markFailed` 는 `publish_attempts + 1` 조건부 UPDATE — 메서드마다 자기 트랜잭션이다(릴레이가 트랜잭션 밖에서 부른다).
- `append` · `ProcessedEventStore.exists` · `record` 는 호출자 트랜잭션에 합류한다.

## 운영 전제

- 토픽 · DLT(`<topic>.dlt`)는 Infra 선언 스크립트가 만든다. 앱은 만들지 않고 `required-topics` 로 확인만 한다.
- 릴레이는 한 인스턴스만 돈다(ShedLock). 락이 만료돼 둘이 겹쳐도 표시는 조건부 UPDATE 로 한 번이고, 남은 중복 발행은 컨슈머 멱등이 흡수한다.
- 발행이 실패하면 그 aggregateId 만 그 주기 동안 멈추고(같은 키의 순서 보존) 다른 키는 계속 보낸다. 실패 행은 시도 수 · 실패 코드(예외 타입)를 남기고
  다음 주기에 다시 보낸다 — 10회부터 ERROR 로그다.
- **토픽과 함께 `<topic>.dlt` 도 있어야 한다.** DLT 가 없으면 재시도를 다 쓴 레코드를 보내지 못해 그 파티션이 같은 레코드를 계속 다시 처리하며 멈춘다.
  쓰는 토픽 · DLT 를 `required-topics` 에 적어 기동 때 확인한다.
- DLT 재처리는 사람이 한다 — Kafka UI 로 재전송하거나 outbox 행의 `published_at` 을 비워 릴레이가 다시 보내게 한다. DLT 헤더에는 예외 · 원인 클래스 이름만 있고 예외 메시지 · 스택트레이스는 없다.
- `auto.offset.reset=earliest` 라 이미 쌓인 토픽에 **새 컨슈머 그룹**을 붙이면 보존 기간(7일)치를 처음부터 처리한다. 지난 이벤트가 필요 없는 그룹은 리스너에 `properties = "auto.offset.reset=latest"` 를 둔다.
- **봉투에는 진위 확인 수단이 없다**(PLAINTEXT · ACL 없음 전제, #304 A-2). 돈 · 재고 상태를 바꾸는 컨슈머는 이벤트를 알림으로만 보고 원본 서비스 internal API 로 재조회해 확정한다(웹훅과 같은 원칙).
  서명 헤더를 둘지는 결제 이벤트(#308) 전에 정한다.
- 지표: `commerce.messaging.outbox.published.total` · `…outbox.publish.failed.total{error}` · `…outbox.publish.latency` · `commerce.messaging.consumer.dlt.total{topic}`.
  미발행 행 수 · 가장 오래된 미발행 나이 게이지는 서비스 저장소가 생기는 #307 에서 더한다.

## 테스트

- 모듈 자체: 단위 테스트 + 내장 Kafka(`spring-kafka-test`, KRaft) 왕복 — 커밋하면 발행 / 롤백하면 미발행, 중복 1회 처리, 재시도 상한 뒤 DLT. Docker 가 필요 없다.
- testFixtures: `InMemoryOutboxStore` · `InMemoryProcessedEventStore`(트랜잭션 커밋 · 롤백 흉내 — 동시성 · 예외 타입은 흉내 내지 않는다), `NoOpTransactionManager`,
  `MessagingKafkaContainers`(apache/kafka 4.3.1 Testcontainers).
- 서비스 JPA 어댑터가 SPI 계약(조건부 표시 · id 순 · available_at · 유니크)을 지키는지 보는 재사용 계약 테스트는 첫 사용처(#307)에서 더한다.
