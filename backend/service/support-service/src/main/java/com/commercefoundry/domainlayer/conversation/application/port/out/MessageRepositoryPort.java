package com.commercefoundry.domainlayer.conversation.application.port.out;

import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import java.util.Optional;
import java.util.List;

public interface MessageRepositoryPort {
    Optional<Message> findByConversationIdAndClientMessageId(long conversationId, String clientMessageId);
    List<Message> findByConversationIdAndSequenceRange(long conversationId, long afterSeq, long lastSeq, int limit);
    Message insertAndFlush(Message message);
}
