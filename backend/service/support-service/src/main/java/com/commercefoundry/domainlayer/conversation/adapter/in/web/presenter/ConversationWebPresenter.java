package com.commercefoundry.domainlayer.conversation.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.conversation.adapter.in.web.dto.response.ConversationWebResponses;
import com.commercefoundry.domainlayer.conversation.application.info.ConversationInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessagePageInfo;
import org.springframework.stereotype.Component;

@Component
public class ConversationWebPresenter {
    public ConversationWebResponses.Detail detail(ConversationInfo info) {
        return new ConversationWebResponses.Detail(String.valueOf(info.id()), info.locale().name(), info.channel().name(), info.status().name(), info.lastSeq());
    }

    public ConversationWebResponses.Message message(MessageInfo info) {
        return new ConversationWebResponses.Message(String.valueOf(info.id()), String.valueOf(info.conversationId()), info.seq(),
            info.authorType().name(), info.status().name(), info.content());
    }

    public ConversationWebResponses.MessagePage page(MessagePageInfo info) {
        return new ConversationWebResponses.MessagePage(info.messages().stream().map(this::message).toList(), info.hasNext(), info.nextCursor(), info.lastSeq());
    }
}
