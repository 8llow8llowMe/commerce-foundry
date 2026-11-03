package com.commercefoundry.domainlayer.conversation.application.port.out;

import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import java.util.Optional;

public interface MessageRepositoryPort {
    Optional<Message> findByConversationIdAndClientMessageId(long conversationId, String clientMessageId);
    Message insertAndFlush(Message message);
}
