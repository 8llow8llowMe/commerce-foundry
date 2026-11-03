package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.MessageRepository;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.mapper.MessageMapper;
import com.commercefoundry.domainlayer.conversation.application.port.out.MessageRepositoryPort;
import com.commercefoundry.domainlayer.conversation.domain.model.Message;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MessageRepositoryAdapter implements MessageRepositoryPort {
    private final MessageRepository repository;
    private final MessageMapper mapper;

    @Override
    public Optional<Message> findByConversationIdAndClientMessageId(long conversationId, String clientMessageId) {
        return repository.findByConversationIdAndClientMessageId(conversationId, clientMessageId).map(mapper::toDomain);
    }

    @Override
    public List<Message> findByConversationIdAndSequenceRange(long conversationId, long afterSeq, long lastSeq, int limit) {
        return repository.findByConversationIdAndSequenceRange(conversationId, afterSeq, lastSeq, PageRequest.of(0, limit))
            .stream().map(mapper::toDomain).toList();
    }

    @Override
    public Message insertAndFlush(Message message) {
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(message)));
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, "uk_support_message_conversation_id_seq")
                || ConstraintViolations.violates(exception, "uk_support_message_conversation_id_client_message_id")) {
                throw new ConversationException(ConversationErrorCode.MESSAGE_CONFLICT);
            }
            throw exception;
        }
    }
}
