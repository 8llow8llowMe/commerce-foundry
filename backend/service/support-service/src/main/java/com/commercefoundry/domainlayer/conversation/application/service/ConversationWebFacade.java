package com.commercefoundry.domainlayer.conversation.application.service;

import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response.ConversationWebResponses;
import com.commercefoundry.domainlayer.conversation.adapter.in.web.presenter.ConversationWebPresenter;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationWebUseCase;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationProcessor;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationMessageProcessor;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ConversationWebFacade implements ConversationWebUseCase {
    private final ConversationProcessor conversations;
    private final ConversationMessageProcessor messages;
    private final GuestSessionProcessor guests;
    private final ConversationWebPresenter presenter;

    // Guest reads require a write transaction: credential locks survive through ownership and message reads.
    @Override
    public ConversationWebResponses.Detail get(long conversationId, Long memberId, String guestToken) {
        return presenter.detail(memberId != null ? conversations.getForMember(conversationId, memberId)
            : conversations.getForGuest(conversationId, guests.resolveForUpdate(guestToken)));
    }

    @Override
    public ConversationWebResponses.MessagePage messages(long conversationId, Long memberId, String guestToken, long cursor, int size) {
        return presenter.page(memberId != null ? messages.getForMember(conversationId, memberId, cursor, size)
            : messages.getForGuest(conversationId, guests.resolveForUpdate(guestToken), cursor, size));
    }

    @Override
    public ConversationWebResponses.Message append(long conversationId, Long memberId, String guestToken, String key, String content) {
        return presenter.message(memberId != null ? messages.appendForMember(conversationId, memberId, key, content)
            : messages.appendForGuest(conversationId, guests.resolveForUpdate(guestToken), key, content));
    }
}
