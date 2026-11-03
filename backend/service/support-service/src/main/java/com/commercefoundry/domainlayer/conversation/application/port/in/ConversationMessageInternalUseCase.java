package com.commercefoundry.domainlayer.conversation.application.port.in;

import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessagePageInfo;

public interface ConversationMessageInternalUseCase {
    MessagePageInfo getForMember(long conversationId, long memberId, long afterSeq, int size);
    MessagePageInfo getForGuest(long conversationId, String token, long afterSeq, int size);
    MessageInfo appendForMember(long conversationId, long memberId, String clientMessageId, String rawContent);
    MessageInfo appendForGuest(long conversationId, String token, String clientMessageId, String rawContent);
}
