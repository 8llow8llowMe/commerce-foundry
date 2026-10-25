package com.commercefoundry.messaging.event;

import java.util.Objects;

/**
 * 공유 클러스터의 이름 규칙 (#304 A-2) — 코드에 namespace · 스토어 이름을 고정하지 않는다.
 * <ul>
 *   <li>토픽 {@code <ns>.<생산 서비스>.<aggregate>-<event>}, DLT 는 {@code <topic>.dlt}</li>
 *   <li>컨슈머 그룹 {@code <ns>.<소비 서비스>.<목적>}</li>
 * </ul>
 */
public record TopicNames(String namespace, String service) {

    public static final String DLT_SUFFIX = ".dlt";

    public TopicNames {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(service, "service");
    }

    /** 이 서비스가 내는 이벤트의 토픽. */
    public String topic(String eventType) {
        return topic(service, eventType);
    }

    /** 다른 서비스가 내는 이벤트의 토픽 — 컨슈머가 구독할 때 쓴다. */
    public String topic(String producerService, String eventType) {
        return namespace + "." + producerService + "." + eventType;
    }

    /** {@code namespace} 뒤 부분({@code <서비스>.<이벤트>})을 전체 이름으로. */
    public String qualified(String relativeTopic) {
        return namespace + "." + relativeTopic;
    }

    /** 이 서비스의 컨슈머 그룹. */
    public String consumerGroup(String purpose) {
        return namespace + "." + service + "." + purpose;
    }

    public static String deadLetterTopic(String topic) {
        return topic + DLT_SUFFIX;
    }
}
