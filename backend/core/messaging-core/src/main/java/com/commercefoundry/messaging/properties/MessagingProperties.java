package com.commercefoundry.messaging.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka 메시징 설정 ({@code commerce.messaging.*}, #306 · #304 설계 A). 접속(bootstrap-servers · 보안 프로토콜)은 Boot {@code spring.kafka.*} 가 맡고,
 * 여기는 이름 규칙 · outbox 릴레이 · 재시도 같은 뜻 설정만 둔다.
 *
 * <p>기본값은 닫힌 쪽이다 — {@code enabled} 가 없으면 꺼진 채 뜬다. 꺼져 있어도 outbox 행은 업무 트랜잭션과 함께 저장된다(릴레이 · 리스너만 멈춘다) —
 * 결제 승인처럼 이미 일어난 일을 기록하는 트랜잭션이 메시징 꺼짐 때문에 실패하면 더 나쁜 상태가 된다. 켜면 쌓인 행부터 내보낸다.
 * 검사 규칙은 {@link #violations(String)} 한 곳이다 — {@code MessagingStartupGuard} 가 기동 때 부른다. 0 이하 수치는 기동 실패다.
 *
 * @param enabled  켜면 릴레이 · 리스너가 돈다 ({@code MESSAGING_ENABLED}). {@code Boolean} 으로 받아 빈 값을 꺼짐으로 정규화한다
 * @param namespace 토픽 · 컨슈머 그룹 이름의 앞부분 {@code commerce-foundry.<env>} ({@code KAFKA_NAMESPACE}). 마지막 토막이 활성 환경과 같아야 한다
 * @param service  이 서비스 이름 — 토픽 {@code <ns>.<service>.<event>} · 그룹 {@code <ns>.<service>.<목적>} 의 가운데 토막. 비면 {@code spring.application.name}
 * @param requiredTopics 기동 때 있는지 확인할 토픽(namespace 뒤 부분, 예 {@code payment-service.payment-approved}). 앱은 토픽을 만들지 않는다
 * @param maxPayloadBytes 봉투(JSON) 한 건의 상한(UTF-8 바이트). 넘으면 발행 요청이 실패한다 — 큰 데이터는 아이디만 싣고 컨슈머가 읽는다
 */
@ConfigurationProperties(prefix = "commerce.messaging")
public record MessagingProperties(
    Boolean enabled,
    String namespace,
    String service,
    List<String> requiredTopics,
    @DefaultValue("65536") int maxPayloadBytes,
    @DefaultValue Outbox outbox,
    @DefaultValue Inbox inbox,
    @DefaultValue Retry retry
) {

    public static final String PREFIX = "commerce.messaging";
    public static final String ENABLED = PREFIX + ".enabled";
    public static final String NAMESPACE = PREFIX + ".namespace";

    /** {@code commerce-foundry.local} 처럼 토막 둘 이상, 소문자 · 숫자 · {@code -}. 고객사가 달라지면 첫 토막만 바꾼다. */
    private static final Pattern NAMESPACE_SHAPE = Pattern.compile("[a-z0-9][a-z0-9-]*(\\.[a-z0-9][a-z0-9-]*)+");
    private static final Pattern NAME_PART = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public MessagingProperties {
        enabled = Boolean.TRUE.equals(enabled);
        namespace = namespace == null || namespace.isBlank() ? null : namespace.strip();
        service = service == null || service.isBlank() ? null : service.strip();
        requiredTopics = requiredTopics == null ? List.of() : List.copyOf(requiredTopics);
        outbox = outbox == null ? new Outbox(null, null) : outbox;
        inbox = inbox == null ? new Inbox(null) : inbox;
        retry = retry == null ? new Retry(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)) : retry;
    }

    /**
     * 기동 조건. 꺼져 있으면 수치만 본다(값이 비어도 뜬다).
     *
     * @param expectedEnvironment 활성 환경(local · dev · test, 없으면 prod) — namespace 마지막 토막과 같아야 한다.
     *                            dev 클러스터를 prod 설정으로 쓰는 실수를 기동에서 막는다
     * @return 위반 설명 — 설정값을 넣지 않는다
     */
    public List<String> violations(String expectedEnvironment) {
        List<String> violations = new ArrayList<>();
        if (maxPayloadBytes <= 0) {
            violations.add(PREFIX + ".max-payload-bytes 는 1 이상이어야 합니다");
        }
        outbox.violations(violations);
        inbox.violations(violations);
        retry.violations(violations);
        if (!enabled) {
            return violations;
        }
        if (namespace == null || namespace.contains("${")) {
            violations.add(NAMESPACE + "(KAFKA_NAMESPACE) 가 비어 있습니다");
        } else if (!NAMESPACE_SHAPE.matcher(namespace).matches()) {
            violations.add(NAMESPACE + " 가 이름 규칙(소문자 · 숫자 · '-' 토막을 '.' 로 이은 둘 이상)에 맞지 않습니다");
        } else if (!namespace.endsWith("." + expectedEnvironment)) {
            violations.add(NAMESPACE + " 의 마지막 토막이 활성 환경(" + expectedEnvironment + ")과 다릅니다 — 다른 환경의 토픽에 붙지 않게 막습니다");
        }
        if (service != null && !NAME_PART.matcher(service).matches()) {
            violations.add(PREFIX + ".service 가 이름 규칙(소문자 · 숫자 · '-')에 맞지 않습니다");
        }
        for (String topic : requiredTopics) {
            if (topic == null || topic.isBlank() || topic.contains("${")) {
                violations.add(PREFIX + ".required-topics 에 빈 항목이 있습니다");
            }
        }
        return violations;
    }

    private static void positive(Duration value, String property, List<String> violations) {
        if (value == null || value.isZero() || value.isNegative()) {
            violations.add(PREFIX + "." + property + " 는 0 보다 길어야 합니다");
        }
    }

    private static void positive(int value, String property, List<String> violations) {
        if (value <= 0) {
            violations.add(PREFIX + "." + property + " 는 1 이상이어야 합니다");
        }
    }

    /** outbox 릴레이 · 정리. */
    public record Outbox(@DefaultValue Relay relay, @DefaultValue Cleanup cleanup) {

        public Outbox {
            relay = relay == null ? new Relay(true, Duration.ofSeconds(1), 100, 10, Duration.ofSeconds(5), Duration.ofSeconds(30), "shedlock") : relay;
            cleanup = cleanup == null ? new Cleanup(true, Duration.ofHours(1), Duration.ofDays(7), 1000) : cleanup;
        }

        void violations(List<String> violations) {
            positive(relay.interval(), "outbox.relay.interval", violations);
            positive(relay.batchSize(), "outbox.relay.batch-size", violations);
            positive(relay.maxBatchesPerRun(), "outbox.relay.max-batches-per-run", violations);
            positive(relay.publishTimeout(), "outbox.relay.publish-timeout", violations);
            positive(relay.lockAtMostFor(), "outbox.relay.lock-at-most-for", violations);
            // 한 주기는 lock-at-most-for 의 절반 안에서 끝낸다 — 한 건 대기가 그보다 길면 락이 쥔 채로 만료된다
            if (relay.publishTimeout() != null && relay.lockAtMostFor() != null
                && relay.publishTimeout().multipliedBy(2).compareTo(relay.lockAtMostFor()) > 0) {
                violations.add(PREFIX + ".outbox.relay.lock-at-most-for 는 publish-timeout 의 두 배 이상이어야 합니다");
            }
            if (relay.lockTable() == null || relay.lockTable().isBlank()) {
                violations.add(PREFIX + ".outbox.relay.lock-table 이 비어 있습니다");
            }
            positive(cleanup.interval(), "outbox.cleanup.interval", violations);
            positive(cleanup.retention(), "outbox.cleanup.retention", violations);
            positive(cleanup.batchSize(), "outbox.cleanup.batch-size", violations);
        }
    }

    /**
     * @param enabled 인스턴스 단위 스위치 — API 전용 인스턴스는 끈다. 끄면 릴레이와 기록 정리(outbox · inbox) 둘 다 이 인스턴스에서 돌지 않는다.
     *                전체 스위치는 {@code commerce.messaging.enabled}
     * @param interval 릴레이 주기
     * @param batchSize 한 번에 읽는 행 수
     * @param maxBatchesPerRun 한 주기에 도는 배치 수 — 밀린 행을 한 주기에 다 비우려 하지 않는다
     * @param publishTimeout 한 건 발행 응답 대기 상한 — 프로듀서 {@code max.block.ms}(토픽 메타데이터 대기)도 이 값으로 맞춘다
     * @param lockAtMostFor 단일 실행 락 상한 — 락을 쥔 인스턴스가 죽으면 이 시간 뒤 다른 인스턴스가 잇는다. 한 주기는 이 값의 절반 안에서 끝난다. 기록 정리 락도 이 값이다
     * @param lockTable ShedLock 테이블(서비스 스키마, 예 {@code orders.shedlock}). 기록 정리도 같은 테이블을 쓴다 — 받기만 하는 서비스도 둔다
     */
    public record Relay(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10") int maxBatchesPerRun,
        @DefaultValue("5s") Duration publishTimeout,
        @DefaultValue("30s") Duration lockAtMostFor,
        @DefaultValue("shedlock") String lockTable
    ) {
    }

    /**
     * 기록 정리 — 발행 완료 outbox 행과 처리 기록(inbox, 보존은 {@link Inbox#retention()})을 함께 지운다. 키는 {@code outbox} 아래지만
     * 받기만 하는 서비스(outbox 없음)의 처리 기록 정리에도 적용된다(#378 — 하위 호환으로 키 이름을 두었다).
     *
     * @param enabled 끄면 outbox · inbox 정리 둘 다 돌지 않는다
     * @param interval 정리 주기
     * @param retention 발행 완료 행 보존 — Kafka 보존(7일)과 맞춰 DLT 복구 때 outbox 에서 다시 낼 수 있게 한다
     * @param batchSize 한 번에 지우는 행 수(outbox · inbox 각각)
     */
    public record Cleanup(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration interval,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1000") int batchSize
    ) {
    }

    /** @param retention 처리 기록 보존 — 토픽 보존 + 재시도 창 이상이어야 늦게 온 중복을 막는다 */
    public record Inbox(@DefaultValue("7d") Duration retention) {

        public Inbox {
            retention = retention == null ? Duration.ofDays(7) : retention;
        }

        void violations(List<String> violations) {
            positive(retention, "inbox.retention", violations);
        }
    }

    /**
     * 컨슈머 안 재시도(일시 오류) — 지수 백오프로 {@code attempts} 번까지 시도하고 넘으면 {@code <topic>.dlt} 로 보낸다.
     *
     * @param attempts 첫 시도 포함 횟수
     */
    public record Retry(
        @DefaultValue("5") int attempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30s") Duration maxBackoff
    ) {

        void violations(List<String> violations) {
            positive(attempts, "retry.attempts", violations);
            positive(initialBackoff, "retry.initial-backoff", violations);
            positive(maxBackoff, "retry.max-backoff", violations);
            if (multiplier < 1.0) {
                violations.add(PREFIX + ".retry.multiplier 는 1.0 이상이어야 합니다");
            }
        }
    }
}
