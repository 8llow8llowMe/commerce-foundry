package com.commercefoundry.domainlayer.conversation.application.port.in;

import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response.ConversationWebResponses;

public interface ConversationWebUseCase {
    ConversationWebResponses.Detail get(long conversationId, Long memberId, String guestToken);
    ConversationWebResponses.MessagePage messages(long conversationId, Long memberId, String guestToken, long cursor, int size);
    ConversationWebResponses.Message append(long conversationId, Long memberId, String guestToken, String key, String content);
}
