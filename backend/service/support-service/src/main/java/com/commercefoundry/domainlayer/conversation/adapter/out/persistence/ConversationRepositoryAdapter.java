package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapper;
import com.commercefoundry.domainlayer.conversation.application.port.out.ConversationRepositoryPort;
import com.commercefoundry.domainlayer.conversation.domain.model.Conversation;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ConversationRepositoryAdapter implements ConversationRepositoryPort {
    private final ConversationRepository repository;
    private final ConversationMapper mapper;

    @Override
    public Conversation saveAndFlush(Conversation conversation) {
        return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(conversation)));
    }

    @Override
    public Optional<Conversation> findByIdAndMemberId(long id, long memberId) {
        return repository.findByIdAndMemberId(id, memberId).map(mapper::toDomain);
    }

    @Override
    public Optional<Conversation> findByIdAndGuestSessionId(long id, long guestSessionId) {
        return repository.findByIdAndGuestSessionId(id, guestSessionId).map(mapper::toDomain);
    }
}
