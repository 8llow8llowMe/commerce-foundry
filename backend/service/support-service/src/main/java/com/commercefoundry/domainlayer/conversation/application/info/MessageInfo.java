package com.commercefoundry.domainlayer.conversation.application.info;

import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageAuthorType;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageStatus;

public record MessageInfo(long id, long conversationId, long seq, MessageAuthorType authorType, MessageStatus status, String content) {
    public static MessageInfo from(Message message) {
        return new MessageInfo(message.id(), message.conversationId(), message.seq(), message.authorType(), message.status(), message.content());
    }

    @Override
    public String toString() {
        return "MessageInfo[id=" + id + ", conversationId=" + conversationId + ", seq=" + seq + "]";
    }
}
