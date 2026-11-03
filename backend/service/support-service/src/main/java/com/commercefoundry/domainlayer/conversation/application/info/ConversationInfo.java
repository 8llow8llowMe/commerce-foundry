package com.commercefoundry.domainlayer.conversation.application.info;

import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationChannel;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;

public record ConversationInfo(long id, ConversationLocale locale, ConversationChannel channel, ConversationStatus status, long lastSeq) {
    public static ConversationInfo from(Conversation conversation) {
        return new ConversationInfo(conversation.id(), conversation.locale(), conversation.channel(), conversation.status(), conversation.lastSeq());
    }
}
