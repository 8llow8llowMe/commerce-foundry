package com.commercefoundry.domainlayer.conversation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 대화 상태 전이 기록(append-only). 전이와 같은 트랜잭션에 쓴다.
 */
public record ConversationEvent(long conversationId, ConversationEventKind kind, ConversationStatus fromStatus, ConversationStatus toStatus,
                                ConversationActorType actorType, Long actorMemberId, Long assigneeMemberId, HandoffReason reason,
                                Instant occurredAt) {
    public ConversationEvent {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(fromStatus);
        Objects.requireNonNull(toStatus);
        Objects.requireNonNull(actorType);
        Objects.requireNonNull(occurredAt);
        if (conversationId <= 0 || fromStatus == toStatus || actorMemberId != null && actorMemberId <= 0
            || assigneeMemberId != null && assigneeMemberId <= 0) {
            throw new IllegalArgumentException("Invalid conversation event");
        }
        if (actorType == ConversationActorType.ADMIN && actorMemberId == null
            || (actorType == ConversationActorType.GUEST || actorType == ConversationActorType.SYSTEM) && actorMemberId != null) {
            throw new IllegalArgumentException("Invalid conversation event actor");
        }
        if ((kind == ConversationEventKind.HANDOFF_REQUESTED) != (reason != null)
            || (kind == ConversationEventKind.ASSIGNED) != (assigneeMemberId != null)) {
            throw new IllegalArgumentException("Invalid conversation event payload");
        }
    }
}
