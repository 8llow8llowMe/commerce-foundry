package com.commercefoundry.domainlayer.conversation.application.service;

import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationMessageInternalUseCase;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationMessageProcessor;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationMessageInternalFacade implements ConversationMessageInternalUseCase {
    private final ConversationMessageProcessor messages;
    private final GuestSessionProcessor guests;

    @Override
    public MessageInfo appendForMember(long conversationId, long memberId, String clientMessageId, String rawContent) {
        return messages.appendForMember(conversationId, memberId, clientMessageId, rawContent);
    }

    @Override
    public MessageInfo appendForGuest(long conversationId, String token, String clientMessageId, String rawContent) {
        return messages.appendForGuest(conversationId, guests.resolveForUpdate(token), clientMessageId, rawContent);
    }
}
