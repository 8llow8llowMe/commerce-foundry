package com.commercefoundry.domainlayer.conversation.application.port.out;

import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import java.util.Optional;

public interface ConversationRepositoryPort {
    Conversation saveAndFlush(Conversation conversation);
    Optional<Conversation> findByIdAndMemberId(long id, long memberId);
    Optional<Conversation> findByIdAndGuestSessionId(long id, long guestSessionId);
}
