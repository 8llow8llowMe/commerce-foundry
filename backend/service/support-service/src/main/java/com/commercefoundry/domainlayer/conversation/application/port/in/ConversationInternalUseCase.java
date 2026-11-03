package com.commercefoundry.domainlayer.conversation.application.port.in;

import com.commercefoundry.domainlayer.conversation.application.info.ConversationInfo;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;

/** In-process foundation; member identifiers must come from a trusted authentication boundary. */
public interface ConversationInternalUseCase {
    ConversationInfo createForMember(long memberId, ConversationLocale locale);
    ConversationInfo createForGuest(String token, ConversationLocale locale);
    ConversationInfo getForMember(long conversationId, long memberId);
    ConversationInfo getForGuest(long conversationId, String token);
}
