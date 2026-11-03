package com.commercefoundry.domainlayer.conversation.domain.model;

import java.util.Objects;

public record Conversation(long id, Long memberId, Long guestSessionId, ConversationLocale locale, ConversationChannel channel,
                           ConversationStatus status, long lastSeq, Long version) {
    public Conversation {
        if (id <= 0 || (memberId == null) == (guestSessionId == null) || memberId != null && memberId <= 0
            || guestSessionId != null && guestSessionId <= 0 || lastSeq < 0) {
            throw new IllegalArgumentException("Invalid conversation state");
        }
        Objects.requireNonNull(locale);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(status);
    }

    public static Conversation create(long id, Long memberId, Long guestSessionId, ConversationLocale locale) {
        return new Conversation(id, memberId, guestSessionId, locale, ConversationChannel.WEB, ConversationStatus.AI_ACTIVE, 0, null);
    }

    public Conversation advanceSequence() {
        return new Conversation(id, memberId, guestSessionId, locale, channel, status, Math.addExact(lastSeq, 1), version);
    }
}
