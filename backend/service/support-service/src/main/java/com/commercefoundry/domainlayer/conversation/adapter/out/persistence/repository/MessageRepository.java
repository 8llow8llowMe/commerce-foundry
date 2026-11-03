package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.MessageEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    Optional<MessageEntity> findByConversationIdAndClientMessageId(long conversationId, String clientMessageId);
}
