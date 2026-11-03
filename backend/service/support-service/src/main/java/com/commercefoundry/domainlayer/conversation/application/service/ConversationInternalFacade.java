package com.commercefoundry.domainlayer.conversation.application.service;

import com.commercefoundry.domainlayer.conversation.application.info.ConversationInfo;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationInternalUseCase;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationProcessor;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationInternalFacade implements ConversationInternalUseCase {
    private final ConversationProcessor conversations;
    private final GuestSessionProcessor guests;

    @Override
    public ConversationInfo createForMember(long memberId, ConversationLocale locale) {
        return conversations.createForMember(memberId, locale);
    }

    @Override
    public ConversationInfo createForGuest(String token, ConversationLocale locale) {
        return conversations.createForGuest(guests.resolveForUpdate(token), locale);
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationInfo getForMember(long conversationId, long memberId) {
        return conversations.getForMember(conversationId, memberId);
    }

    /** Guest authorization retains the credential lock through the conversation read. */
    @Override
    public ConversationInfo getForGuest(long conversationId, String token) {
        return conversations.getForGuest(conversationId, guests.resolveForUpdate(token));
    }
}
