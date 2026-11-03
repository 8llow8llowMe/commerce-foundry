package com.commercefoundry.domainlayer.conversation.application.service.processor;

import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessagePageInfo;
import com.commercefoundry.domainlayer.conversation.application.port.out.ConversationRepositoryPort;
import com.commercefoundry.domainlayer.conversation.application.port.out.MessageRepositoryPort;
import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;
import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageAuthorType;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageStatus;
import com.commercefoundry.domainlayer.guestsession.domain.model.GuestSessionIdentity;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ConversationMessageProcessor {
    private final ConversationRepositoryPort conversations;
    private final MessageRepositoryPort messages;
    private final MessageContentPolicy contentPolicy;
    private final SnowflakeIdGenerator ids;

    public MessagePageInfo getForMember(long id, long memberId, long afterSeq, int size) {
        if (memberId <= 0) {
            throw new ConversationException(ConversationErrorCode.INVALID_OWNER);
        }
        long lastSeq = conversations.findLastSeqByIdAndMemberId(id, memberId)
            .orElseThrow(() -> new ConversationException(ConversationErrorCode.NOT_FOUND));
        return page(id, afterSeq, size, lastSeq);
    }

    public MessagePageInfo getForGuest(long id, GuestSessionIdentity guest, long afterSeq, int size) {
        long lastSeq = conversations.findLastSeqByIdAndGuestSessionId(id, guest.id())
            .orElseThrow(() -> new ConversationException(ConversationErrorCode.NOT_FOUND));
        return page(id, afterSeq, size, lastSeq);
    }

    private MessagePageInfo page(long id, long afterSeq, int size, long lastSeq) {
        if (afterSeq < 0 || afterSeq > lastSeq) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE_CURSOR);
        }
        if (size < 1 || size > 100) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE_PAGE_SIZE);
        }
        if (afterSeq == lastSeq) {
            return new MessagePageInfo(java.util.List.of(), false, afterSeq, lastSeq);
        }
        var found = messages.findByConversationIdAndSequenceRange(id, afterSeq, lastSeq, size + 1);
        boolean hasNext = found.size() > size;
        var items = found.stream().limit(size).map(MessageInfo::from).toList();
        long nextCursor = items.isEmpty() ? afterSeq : items.getLast().seq();
        return new MessagePageInfo(items, hasNext, nextCursor, lastSeq);
    }

    public MessageInfo appendForMember(long id, long memberId, String key, String raw) {
        if (memberId <= 0) {
            throw new ConversationException(ConversationErrorCode.INVALID_OWNER);
        }
        Conversation conversation = conversations.findByIdAndMemberIdForUpdate(id, memberId)
            .orElseThrow(() -> new ConversationException(ConversationErrorCode.NOT_FOUND));
        return append(conversation, key, raw);
    }

    public MessageInfo appendForGuest(long id, GuestSessionIdentity guest, String key, String raw) {
        Conversation conversation = conversations.findByIdAndGuestSessionIdForUpdate(id, guest.id())
            .orElseThrow(() -> new ConversationException(ConversationErrorCode.NOT_FOUND));
        return append(conversation, key, raw);
    }

    private MessageInfo append(Conversation conversation, String key, String raw) {
        contentPolicy.validateKey(key);
        String content = contentPolicy.canonicalize(raw);
        var existing = messages.findByConversationIdAndClientMessageId(conversation.id(), key);
        if (existing.isPresent()) {
            if (!existing.get().content().equals(content)) {
                throw new ConversationException(ConversationErrorCode.MESSAGE_CONFLICT);
            }
            return MessageInfo.from(existing.get());
        }
        boolean allowed = conversation.status() == ConversationStatus.AI_ACTIVE
            || conversation.memberId() != null && (conversation.status() == ConversationStatus.WAITING_AGENT
                || conversation.status() == ConversationStatus.HUMAN_ACTIVE);
        if (!allowed) {
            throw new ConversationException(ConversationErrorCode.MESSAGE_STATE_UNAVAILABLE);
        }
        Conversation advanced;
        try {
            advanced = conversation.advanceSequence();
        } catch (ArithmeticException exception) {
            throw new ConversationException(ConversationErrorCode.SEQUENCE_EXHAUSTED);
        }
        conversations.saveAndFlush(advanced);
        Message message = new Message(ids.generateId(), conversation.id(), advanced.lastSeq(), MessageAuthorType.CUSTOMER, conversation.memberId(),
            conversation.guestSessionId(), MessageStatus.COMPLETED, key, content);
        return MessageInfo.from(messages.insertAndFlush(message));
    }
}
