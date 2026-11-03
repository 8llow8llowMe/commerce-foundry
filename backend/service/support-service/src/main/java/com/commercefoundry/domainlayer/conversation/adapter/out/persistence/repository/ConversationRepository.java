package com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    Optional<ConversationEntity> findByIdAndMemberId(long id, long memberId);
    Optional<ConversationEntity> findByIdAndGuestSessionId(long id, long guestSessionId);
    @Query("select c.id from ConversationEntity c where c.id = :id and c.memberId = :memberId")
    Optional<Long> findIdByIdAndMemberId(long id, long memberId);
    @Query("select c.id from ConversationEntity c where c.id = :id and c.guestSessionId = :guestSessionId")
    Optional<Long> findIdByIdAndGuestSessionId(long id, long guestSessionId);
}
