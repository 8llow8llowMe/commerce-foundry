package com.commercefoundry.domainlayer.conversation.domain.model;

/**
 * 허용 전이 표 밖의 상태 전이. Processor 가 409 SUPPORT_020 으로 바꾼다.
 */
public class ConversationTransitionException extends RuntimeException {
    private final ConversationStatus from;
    private final ConversationStatus to;
    private final ConversationActorType actorType;

    public ConversationTransitionException(ConversationStatus from, ConversationStatus to, ConversationActorType actorType) {
        super("Conversation transition not allowed: " + from + " -> " + to + " by " + actorType);
        this.from = from;
        this.to = to;
        this.actorType = actorType;
    }

    public ConversationStatus from() {
        return from;
    }

    public ConversationStatus to() {
        return to;
    }

    public ConversationActorType actorType() {
        return actorType;
    }
}
