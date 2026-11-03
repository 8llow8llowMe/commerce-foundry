package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.MessageEntity;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    @Query("select m from MessageEntity m where m.conversationId = :conversationId and m.seq > :afterSeq and m.seq <= :lastSeq order by m.seq asc")
    List<MessageEntity> findByConversationIdAndSequenceRange(long conversationId, long afterSeq, long lastSeq, Pageable pageable);
    Optional<MessageEntity> findByConversationIdAndClientMessageId(long conversationId, String clientMessageId);
}
