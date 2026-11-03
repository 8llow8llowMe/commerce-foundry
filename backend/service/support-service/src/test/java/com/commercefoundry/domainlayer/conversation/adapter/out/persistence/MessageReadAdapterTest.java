package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.MessageRepository;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapper;
import com.commercefoundry.domainlayer.conversation.application.mapper.MessageMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class MessageReadAdapterTest {
    @Test
    void pageLimitIsAppliedInPersistenceWithoutPageOrCountResult() {
        var repository = mock(MessageRepository.class);
        var adapter = new MessageRepositoryAdapter(repository, mock(MessageMapper.class));
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findByConversationIdAndSequenceRange(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(20L), org.mockito.ArgumentMatchers.any(Pageable.class))).thenReturn(List.of());
        assertThat(adapter.findByConversationIdAndSequenceRange(1, 7, 20, 101)).isEmpty();
        verify(repository).findByConversationIdAndSequenceRange(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.eq(20L), pageable.capture());
        assertThat(pageable.getValue().getOffset()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(101);
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void scalarProjectionDistinguishesEmptyConversationFromAbsentOwner() {
        var repository = mock(ConversationRepository.class);
        var adapter = new ConversationRepositoryAdapter(repository, mock(ConversationMapper.class), mock(EntityManager.class));
        when(repository.findLastSeqByIdAndMemberId(1, 11)).thenReturn(Optional.of(0L));
        when(repository.findLastSeqByIdAndGuestSessionId(1, 12)).thenReturn(Optional.empty());
        assertThat(adapter.findLastSeqByIdAndMemberId(1, 11)).contains(0L);
        assertThat(adapter.findLastSeqByIdAndGuestSessionId(1, 12)).isEmpty();
        verify(repository).findLastSeqByIdAndMemberId(1, 11);
        verify(repository).findLastSeqByIdAndGuestSessionId(1, 12);
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
    }
}
