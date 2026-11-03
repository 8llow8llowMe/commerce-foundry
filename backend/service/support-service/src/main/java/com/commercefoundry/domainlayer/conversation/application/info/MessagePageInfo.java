package com.commercefoundry.domainlayer.conversation.application.info;

import java.util.List;

public record MessagePageInfo(List<MessageInfo> messages, boolean hasNext, long nextCursor, long lastSeq) {
    public MessagePageInfo {
        messages = List.copyOf(messages);
    }

    @Override
    public String toString() {
        return "MessagePageInfo[count=" + messages.size() + ", hasNext=" + hasNext + ", nextCursor=" + nextCursor + ", lastSeq=" + lastSeq + "]";
    }
}
