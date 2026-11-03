package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    Optional<ConversationEntity> findByIdAndMemberId(long id, long memberId);
    Optional<ConversationEntity> findByIdAndGuestSessionId(long id, long guestSessionId);
}
