package com.commercefoundry.domainlayer.conversation.application.port.in;

import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;

public interface ConversationMessageInternalUseCase {
    MessageInfo appendForMember(long conversationId, long memberId, String clientMessageId, String rawContent);
    MessageInfo appendForGuest(long conversationId, String token, String clientMessageId, String rawContent);
}
