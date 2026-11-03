package com.commercefoundry.domainlayer.conversation.domain.model;

import java.util.Objects;

public record Message(long id, long conversationId, long seq, MessageAuthorType authorType, Long authorMemberId, Long authorGuestSessionId,
                      MessageStatus status, String clientMessageId, String content) {
    public Message {
        if (id <= 0 || conversationId <= 0 || seq <= 0 || (authorMemberId == null) == (authorGuestSessionId == null)
            || authorMemberId != null && authorMemberId <= 0 || authorGuestSessionId != null && authorGuestSessionId <= 0) {
            throw new IllegalArgumentException("Invalid message state");
        }
        Objects.requireNonNull(authorType);
        Objects.requireNonNull(status);
        if (clientMessageId == null || !clientMessageId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || content == null || content.isBlank() || content.length() > 2000) {
            throw new IllegalArgumentException("Invalid message content or key");
        }
    }

    @Override
    public String toString() {
        return "Message[id=" + id + ", conversationId=" + conversationId + ", seq=" + seq + "]";
    }
}
